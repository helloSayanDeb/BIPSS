"""
BIPSS band firmware v2 - CircuitPython, Seeed XIAO nRF52840 Sense.

*** UNTESTED ON HARDWARE ***
This is the reference implementation of docs/ble-protocol-v2.md, derived from
the v1 full_system/code.py. It has been checked against the protocol document
and the phone-side parser, but it has never been flashed onto a band. Expect to
tune loop timing and I2C error handling on first bring-up.

What changed from v1 (see the protocol doc for why):
  * Two 20-byte packets instead of one 24-byte frame, so nothing depends on
    MTU negotiation: a 1 Hz status packet on 6E400003 and a 12.5 Hz waveform
    packet on the new characteristic 6E400004.
  * Timing from time.monotonic_ns() (integer), not float time.monotonic(),
    which loses millisecond precision after about an hour of uptime.
  * MAX30102 hardware averaging SMP_AVE=2 -> 50 Hz output; burst FIFO reads;
    OVF_COUNTER advances the sample index, so the phone sees lost samples.
  * MPU6050 digital low-pass (DLPF_CFG=5) and a real activity count.
  * AHT21 read as a trigger-then-poll state machine, so the loop never blocks
    for the 80 ms conversion.
  * Preallocated buffers packed with struct.pack_into (no per-packet garbage).
  * Battery reports 0xFF (unknown) when it cannot be read - never a made-up 100.

Sensor status carried over from v1 (read before trusting any field):
  MPU6050, MAX30102, TMP117  - confirmed working on the previous build
  AHT21, ENS160              - NEVER SUCCESSFULLY READ; code included, unproven
"""

import os
from step_counter import StepCounter
from sensor_validation import decode_aht21, ens_ready, ens_compensation, pulse_is_fresh

import board
import busio
import digitalio
import struct
import time

from adafruit_ble import BLERadio
from adafruit_ble.advertising.standard import ProvideServicesAdvertisement
from adafruit_ble.attributes import Attribute
from adafruit_ble.characteristics import Characteristic
from adafruit_ble.services import Service
from adafruit_ble.uuid import VendorUUID

MPU6050_ADDR = 0x68
MAX30102_ADDR = 0x57
TMP117_ADDR = 0x48
AHT21_ADDR = 0x38
ENS160_CANDIDATES = (0x53, 0x52)

PPG_PERIOD_MS = 20          # nominal: 100 Hz with SMP_AVE=2
ACCEL_PERIOD_NS = 40_000_000  # 25 Hz
STATUS_PERIOD_NS = 1_000_000_000
FRESH_MS = 5000             # a reading older than this is not sent as current

# Quality bits (status byte 19)
Q_HR, Q_SPO2, Q_SKIN, Q_AHT, Q_ENS, Q_MPU, Q_WAVE = 1, 2, 4, 8, 16, 32, 64
# Waveform flags (waveform byte 3)
F_CONTACT, F_SATURATED, F_ACCEL_VALID, F_FIFO_OVF = 1, 2, 4, 8


def now_ms():
    return time.monotonic_ns() // 1_000_000


# ---------------------------------------------------------------- I2C ------
def lock_i2c():
    deadline = now_ms() + 10
    while not i2c.try_lock():
        if now_ms() >= deadline:
            raise OSError("I2C lock timeout")


def i2c_bus_recovery():
    """Clock out a slave stuck holding SDA low, then issue a STOP."""
    scl = digitalio.DigitalInOut(board.SCL)
    sda = digitalio.DigitalInOut(board.SDA)
    scl.direction = digitalio.Direction.OUTPUT
    sda.direction = digitalio.Direction.INPUT
    scl.value = True
    for _ in range(9):
        if sda.value:
            break
        scl.value = False
        time.sleep(0.00005)
        scl.value = True
        time.sleep(0.00005)
    sda.direction = digitalio.Direction.OUTPUT
    sda.value = False
    time.sleep(0.00005)
    scl.value = True
    time.sleep(0.00005)
    sda.value = True
    scl.deinit()
    sda.deinit()


time.sleep(1.0)
i2c_bus_recovery()
i2c = busio.I2C(board.SCL, board.SDA, frequency=400000)

lock_i2c()
found = i2c.scan()
i2c.unlock()
print("found:", [hex(a) for a in found])

mpu_ok = MPU6050_ADDR in found
max_ok = MAX30102_ADDR in found
tmp_ok = TMP117_ADDR in found
aht_ok = AHT21_ADDR in found
ens_addr = next((a for a in ENS160_CANDIDATES if a in found), None)
ens_ok = ens_addr is not None

_reg = bytearray(1)


def i2c_write(addr, data):
    lock_i2c()
    try:
        i2c.writeto(addr, data)
    finally:
        i2c.unlock()


def i2c_read_into(addr, reg, buf, end=None):
    _reg[0] = reg
    lock_i2c()
    try:
        i2c.writeto_then_readfrom(addr, _reg, buf, in_end=end if end is not None else len(buf))
    finally:
        i2c.unlock()


# ---------------------------------------------------------- MPU6050 -------
accel_buf = bytearray(6)
if mpu_ok:
    try:
        i2c_write(MPU6050_ADDR, bytes((0x6B, 0x00)))  # wake
        time.sleep(0.05)
        i2c_write(MPU6050_ADDR, bytes((0x1A, 0x05)))  # CONFIG: DLPF_CFG=5 (~10 Hz), no aliasing at 25 Hz
        i2c_write(MPU6050_ADDR, bytes((0x1C, 0x00)))  # ACCEL_CONFIG: +-2 g, 16384 LSB/g
    except OSError as e:
        mpu_ok = False
        print("mpu_ok initialization failed:", e)


def read_accel_raw():
    """Returns (x, y, z) in LSB (16384 per g), or None on an I2C error."""
    try:
        i2c_read_into(MPU6050_ADDR, 0x3B, accel_buf)
    except OSError:
        return None
    return struct.unpack(">hhh", accel_buf)


class ActivityCounter:
    """Protocol-doc activity count: per-axis high-pass, vector magnitude,
    10 mg dead-band, integrated over one second -> mg*s."""
    HP_ALPHA = 0.94  # first-order high-pass, fc ~= 0.25 Hz at 25 Hz
    DEAD_BAND_MG = 10.0

    def __init__(self):
        self.prev_in = [0.0, 0.0, 0.0]
        self.prev_out = [0.0, 0.0, 0.0]
        self.primed = False
        self.acc = 0.0

    def add(self, xyz_mg, dt_s):
        if not self.primed:
            self.prev_in = list(xyz_mg)
            self.primed = True
            return
        mag2 = 0.0
        for i in range(3):
            out = self.HP_ALPHA * (self.prev_out[i] + xyz_mg[i] - self.prev_in[i])
            self.prev_in[i] = xyz_mg[i]
            self.prev_out[i] = out
            mag2 += out * out
        mag = mag2 ** 0.5 - self.DEAD_BAND_MG
        if mag > 0:
            self.acc += mag * dt_s

    def take(self):
        v = int(self.acc + 0.5)
        self.acc = 0.0
        return min(v, 0xFFFE)


activity = ActivityCounter()

# --------------------------------------------------------- MAX30102 -------
fifo_ptrs = bytearray(3)          # WR_PTR, OVF_COUNTER, RD_PTR
fifo_data = bytearray(32 * 6)     # whole FIFO, red+IR, 3 bytes each
if max_ok:
    try:
        i2c_write(MAX30102_ADDR, bytes((0x09, 0x40)))  # reset
        time.sleep(0.1)
        i2c_write(MAX30102_ADDR, bytes((0x08, 0x3F)))  # FIFO: SMP_AVE=2, rollover, A_FULL=15
        i2c_write(MAX30102_ADDR, bytes((0x09, 0x03)))  # SpO2 mode (red + IR)
        i2c_write(MAX30102_ADDR, bytes((0x0A, 0x27)))  # 4096 nA range, 100 Hz, 411 us / 18-bit
        i2c_write(MAX30102_ADDR, bytes((0x0C, 0x3F)))  # red LED current
        i2c_write(MAX30102_ADDR, bytes((0x0D, 0x3F)))  # IR LED current
        for reg in (0x04, 0x05, 0x06):
            i2c_write(MAX30102_ADDR, bytes((reg, 0x00)))
    except OSError as e:
        max_ok = False
        print("max_ok initialization failed:", e)


def read_fifo():
    """Burst-reads every pending sample. Returns (count, lost) where the
    samples are in fifo_data; lost > 0 means the FIFO overflowed and that
    many samples were discarded before these."""
    try:
        i2c_read_into(MAX30102_ADDR, 0x04, fifo_ptrs)
        pending = (fifo_ptrs[0] - fifo_ptrs[2]) & 0x1F
        lost = fifo_ptrs[1]
        if lost:
            pending = 32  # FIFO is full when it has overflowed
        if pending:
            # FIFO_DATA does not auto-increment: a multi-byte read walks the FIFO.
            i2c_read_into(MAX30102_ADDR, 0x07, fifo_data, end=pending * 6)
        return pending, lost
    except OSError:
        return 0, 0


IR_CONTACT = 10000
IR_SATURATED = 0x3FF00


class HRProcessor:
    """v1 beat detector, now timed by sample index instead of arrival time."""
    REFRACTORY_MS = 300
    HP_ALPHA = 0.95
    LP_ALPHA = 0.80
    ACQUIRE = 50  # 1 s at 50 Hz

    def __init__(self):
        self.reset()

    def reset(self):
        self.bpm = 0
        self.rmssd = 0
        self.rr = []
        self.n = 0
        self.hp_raw = self.hp_out = self.lp = 0.0
        self.last_peak_t = -1
        self.last_rr_t = -1   # sample time of the last accepted interval
        self.prev_f = self.prev_d = 0.0
        self.amps = []

    def update(self, ir, t_ms):
        self.n += 1
        hp = self.HP_ALPHA * (self.hp_out + ir - self.hp_raw)
        self.hp_raw, self.hp_out = ir, hp
        f = (1.0 - self.LP_ALPHA) * hp + self.LP_ALPHA * self.lp
        self.lp = f
        if self.n < self.ACQUIRE:
            self.prev_f, self.prev_d = f, 0.0
            return
        d = f - self.prev_f
        refractory_ok = self.last_peak_t < 0 or t_ms - self.last_peak_t > self.REFRACTORY_MS
        if self.prev_d > 0 and d <= 0 and refractory_ok and self.prev_f > 0:
            peak = self.prev_f
            if not self.amps or peak >= 0.3 * sum(self.amps) / len(self.amps):
                if self.last_peak_t >= 0:
                    rr = t_ms - self.last_peak_t
                    if 333 < rr < 2000:
                        self.last_rr_t = t_ms
                        self.rr.append(rr)
                        if len(self.rr) > 12:
                            self.rr.pop(0)
                        self._compute()
                self.amps.append(peak)
                if len(self.amps) > 8:
                    self.amps.pop(0)
                self.last_peak_t = t_ms
        self.prev_f, self.prev_d = f, d

    def _compute(self):
        if len(self.rr) < 4:
            return
        bpm = round(60000 / (sum(self.rr) / len(self.rr)))
        if 30 <= bpm <= 220:
            self.bpm = bpm
        sq = [(self.rr[i + 1] - self.rr[i]) ** 2 for i in range(len(self.rr) - 1)]
        self.rmssd = round((sum(sq) / len(sq)) ** 0.5)


class SpO2Processor:
    WINDOW = 200  # 4 s at 50 Hz

    def __init__(self):
        self.reset()

    def reset(self):
        self.red = []
        self.ir = []
        self.spo2 = 0
        self.updated_t = -1

    def update(self, red, ir, t_ms):
        self.red.append(red)
        self.ir.append(ir)
        if len(self.red) > self.WINDOW:
            self.red.pop(0)
            self.ir.pop(0)
        if len(self.red) < self.WINDOW:
            return
        red_dc = sum(self.red) / self.WINDOW
        ir_dc = sum(self.ir) / self.WINDOW
        red_ac = max(self.red) - min(self.red)
        ir_ac = max(self.ir) - min(self.ir)
        if red_dc <= 0 or ir_dc <= 0 or ir_ac < 50 or red_ac <= 0:
            return
        r = (red_ac / red_dc) / (ir_ac / ir_dc)
        if not 0.4 <= r <= 1.2:
            return
        # Uncalibrated textbook curve - the phone labels SpO2 as an estimate.
        # Outside 70-100 the curve is extrapolating: drop the value rather
        # than clamp it, which would report an invented 70 or 100 as measured.
        v = round(-45.060 * r * r + 30.354 * r + 94.845)
        if not 70 <= v <= 100:
            return
        self.spo2 = v if self.spo2 == 0 else round(self.spo2 * 0.7 + v * 0.3)
        self.updated_t = t_ms


hr_proc = HRProcessor()
spo2_proc = SpO2Processor()

# ----------------------------------------------------------- TMP117 -------
tmp_buf = bytearray(2)


def read_skin_c():
    try:
        i2c_read_into(TMP117_ADDR, 0x00, tmp_buf)
    except OSError:
        return None
    raw = (tmp_buf[0] << 8) | tmp_buf[1]
    if raw & 0x8000:
        raw -= 65536
    return raw * 0.0078125


# ---------------------------------------------- AHT21 (unproven) ----------
aht_buf = bytearray(7)
AHT_IDLE, AHT_MEASURING = 0, 1
aht_state = AHT_IDLE
aht_started_ms = 0
if aht_ok:
    try:
        time.sleep(0.1)
        i2c_write(AHT21_ADDR, bytes((0x71,)))
        st = bytearray(1)
        lock_i2c()
        try:
            i2c.readfrom_into(AHT21_ADDR, st)
        finally:
            i2c.unlock()
        if not st[0] & 0x08:
            i2c_write(AHT21_ADDR, bytes((0xBE, 0x08, 0x00)))
            time.sleep(0.5)
    except OSError as e:
        print("AHT21 init failed:", e)
        aht_ok = False


def aht_step(t_ms):
    """Non-blocking: trigger, then poll on later loops. Returns (temp, rh) when
    a conversion completes, else None."""
    global aht_state, aht_started_ms
    try:
        if aht_state == AHT_IDLE:
            i2c_write(AHT21_ADDR, bytes((0xAC, 0x33, 0x00)))
            aht_state, aht_started_ms = AHT_MEASURING, t_ms
            return None
        if t_ms - aht_started_ms < 80:
            return None
        lock_i2c()
        try:
            i2c.readfrom_into(AHT21_ADDR, aht_buf)
        finally:
            i2c.unlock()
        if aht_buf[0] & 0x80:
            if t_ms - aht_started_ms > 300:
                aht_state = AHT_IDLE  # give up on this conversion
            return None
        aht_state = AHT_IDLE
        return decode_aht21(aht_buf)
    except OSError:
        aht_state = AHT_IDLE
        return None


# --------------------------------------------- ENS160 (unproven) ----------
ens_status = bytearray(1)
ens_aqi = bytearray(1)
if ens_ok:
    try:
        identity = bytearray(2)
        i2c_read_into(ens_addr, 0x00, identity)
        if identity != bytes((0x60, 0x01)):
            raise OSError("ENS160 identity mismatch")
        for mode, pause in ((0xF0, 0.25), (0x01, 0.1), (0x02, 0.1)):  # reset, idle, standard
            i2c_write(ens_addr, bytes((0x10, mode)))
            time.sleep(pause)
    except OSError as e:
        print("ENS160 init failed:", e)
        ens_ok = False


def ens_compensate(temp_c, rh):
    packet = ens_compensation(temp_c, rh)
    if packet is None:
        return
    try:
        i2c_write(ens_addr, packet)
    except OSError:
        pass


def read_ens_uba():
    try:
        i2c_read_into(ens_addr, 0x20, ens_status)
        if ens_ready(ens_status[0]):
            i2c_read_into(ens_addr, 0x21, ens_aqi)
            uba = ens_aqi[0] & 0x07
            return uba if 1 <= uba <= 5 else None
    except OSError:
        pass
    return None


# ---------------------------------------------------------- battery -------
def read_battery_pct():
    """0xFF when the battery cannot be read - never a made-up 100."""
    try:
        import analogio
        en = digitalio.DigitalInOut(board.READ_BATT_ENABLE)
        en.direction = digitalio.Direction.OUTPUT
        en.value = False
        pin = analogio.AnalogIn(board.VBATT)
        v = (pin.value * 3.3 / 65536) * 2.961
        pin.deinit()
        en.deinit()
        return max(0, min(100, int((v - 3.30) / (4.20 - 3.30) * 100)))
    except Exception:
        return 0xFF


# -------------------------------------------------------------- BLE -------
class BandService(Service):
    uuid = VendorUUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    status = Characteristic(
        uuid=VendorUUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
        properties=Characteristic.NOTIFY, read_perm=Attribute.OPEN,
        write_perm=Attribute.NO_ACCESS, max_length=20, fixed_length=True,
        initial_value=bytes(20))
    waveform = Characteristic(
        uuid=VendorUUID("6E400004-B5A3-F393-E0A9-E50E24DCCA9E"),
        properties=Characteristic.NOTIFY, read_perm=Attribute.OPEN,
        write_perm=Attribute.NO_ACCESS, max_length=20, fixed_length=True,
        initial_value=bytes(20))
    control = Characteristic(
        uuid=VendorUUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
        properties=Characteristic.WRITE, read_perm=Attribute.NO_ACCESS,
        write_perm=Attribute.OPEN, max_length=20, fixed_length=False,
        initial_value=b"")


ble = BLERadio()
ble.name = "BIPSS1"  # <= 8 chars: advertisement size limit
band = BandService()
adv = ProvideServicesAdvertisement(band)
ble.start_advertising(adv)

STATUS_FMT = "<BIHBBBhhBBHBB"
WAVE_FMT = "<BHB4H6bBB"
assert struct.calcsize(STATUS_FMT) == 20
assert struct.calcsize(WAVE_FMT) == 20
step_counter = StepCounter()
step_boot_id = struct.unpack("<I", os.urandom(4))[0]
step_pkt = bytearray(20)
status_pkt = bytearray(20)
wave_pkt = bytearray(20)


def i16_centi(v):
    return -0x8000 if v is None else max(-0x7FFF, min(0x7FFF, int(round(v * 100))))


def clamp_i8(v):
    return max(-128, min(127, v))


# -------------------------------------------------------- main loop -------
sample_index = 0          # 50 Hz PPG counter; the phone unwraps the u16
wave_ppg = [0, 0, 0, 0]
wave_accel = [0, 0, 0, 0, 0, 0]
wave_fill = 0
wave_first = 0
wave_flags = 0
wave_counter = 0
wave_accel_ok = True      # every held accel sample in this packet was real
last_accel = None         # latest accel, 1/64 g units
accel_valid = False

boot_ns = time.monotonic_ns()
next_status = boot_ns + STATUS_PERIOD_NS
next_accel = boot_ns
last_accel_ns = boot_ns
next_battery = boot_ns
battery = 0xFF
amb_c = rh = None
amb_t = -1
uba = None
uba_t = -1
last_comp_ms = 0
next_steps_ms = 0
next_ens_ms = 0
last_ppg_wall_ms = -1

while True:
    t_ns = time.monotonic_ns()
    t_ms = (t_ns - boot_ns) // 1_000_000

    # Accelerometer at 25 Hz: feeds the activity count and the waveform packet.
    if mpu_ok and t_ns >= next_accel:
        next_accel += ACCEL_PERIOD_NS
        if next_accel < t_ns:  # fell behind (e.g. a slow notify): don't burst to catch up
            next_accel = t_ns + ACCEL_PERIOD_NS
        raw = read_accel_raw()
        if raw is None:
            accel_valid = False
            step_counter.reset_window()
        else:
            dt = (t_ns - last_accel_ns) / 1e9
            last_accel_ns = t_ns
            step_counter.add(raw[0] / 16384.0, raw[1] / 16384.0, raw[2] / 16384.0, t_ms)
            activity.add([c * 1000.0 / 16384.0 for c in raw], dt)
            last_accel = [clamp_i8(round(c / 256)) for c in raw]  # 16384/64 = 256 LSB per 1/64 g
            accel_valid = True

    # PPG: every pending sample, each advancing the index by one.
    if max_ok:
        count, lost = read_fifo()
        if count > 0:
            last_ppg_wall_ms = t_ms
        if lost:
            sample_index += lost
            wave_flags |= F_FIFO_OVF
            wave_fill = 0  # the half-built packet is no longer contiguous
            wave_accel_ok = True
        for i in range(count):
            o = i * 6
            red = ((fifo_data[o] & 0x03) << 16) | (fifo_data[o + 1] << 8) | fifo_data[o + 2]
            ir = ((fifo_data[o + 3] & 0x03) << 16) | (fifo_data[o + 4] << 8) | fifo_data[o + 5]
            contact = ir >= IR_CONTACT
            if contact:
                hr_proc.update(ir, sample_index * PPG_PERIOD_MS)
                spo2_proc.update(red, ir, sample_index * PPG_PERIOD_MS)
            else:
                hr_proc.reset()
                spo2_proc.reset()

            if wave_fill == 0:
                wave_first = sample_index
            wave_ppg[wave_fill] = ir >> 2
            if contact:
                wave_flags |= F_CONTACT
            if ir >= IR_SATURATED:
                wave_flags |= F_SATURATED
            if wave_fill % 2 == 0:  # accel sample-and-hold against PPG samples 0 and 2
                j = (wave_fill // 2) * 3
                if accel_valid and last_accel:
                    a = last_accel
                else:
                    # No reading to hold: the packet carries zeros and must not
                    # claim a valid accelerometer.
                    a = (0, 0, 0)
                    wave_accel_ok = False
                wave_accel[j], wave_accel[j + 1], wave_accel[j + 2] = a[0], a[1], a[2]
            wave_fill += 1
            sample_index += 1

            if wave_fill == 4:
                flags = wave_flags | (F_ACCEL_VALID if (mpu_ok and wave_accel_ok) else 0)
                struct.pack_into(WAVE_FMT, wave_pkt, 0, 0x11, wave_first & 0xFFFF, flags,
                                 wave_ppg[0], wave_ppg[1], wave_ppg[2], wave_ppg[3],
                                 wave_accel[0], wave_accel[1], wave_accel[2],
                                 wave_accel[3], wave_accel[4], wave_accel[5],
                                 hr_proc.bpm & 0xFF, wave_counter & 0xFF)
                if ble.connected:
                    try:
                        band.waveform = wave_pkt
                    except Exception as e:
                        print("waveform notify failed:", e)
                wave_counter += 1
                wave_fill = 0
                wave_flags = 0
                wave_accel_ok = True

    # Slow sensors.
    if aht_ok:
        res = aht_step(t_ms)
        if res is not None:
            amb_c, rh = res
            amb_t = t_ms
            if ens_ok and t_ms - last_comp_ms > 60_000:
                ens_compensate(amb_c, rh)
                last_comp_ms = t_ms
    if ens_ok and t_ms >= next_ens_ms:
        next_ens_ms = t_ms + 1000
        v = read_ens_uba()
        if v is not None:
            uba = v
            uba_t = t_ms

    if t_ns >= next_battery:
        next_battery = t_ns + 60 * STATUS_PERIOD_NS
        battery = read_battery_pct()

    # Status packet at 1 Hz.
    if t_ns >= next_status:
        next_status += STATUS_PERIOD_NS
        if next_status < t_ns:
            next_status = t_ns + STATUS_PERIOD_NS
        # A value is only sent while it is fresh. The processors keep their
        # last result when the signal goes flat (saturation, weak pulse), and
        # repeating it with its quality bit set would report a stale number
        # as a new measurement.
        now_sample_t = sample_index * PPG_PERIOD_MS
        pulse_fresh = max_ok and hr_proc.n > 0 and pulse_is_fresh(t_ms, last_ppg_wall_ms, now_sample_t, hr_proc.last_rr_t, FRESH_MS)
        spo2_fresh = max_ok and pulse_is_fresh(t_ms, last_ppg_wall_ms, now_sample_t, spo2_proc.updated_t, FRESH_MS)
        hr = hr_proc.bpm if pulse_fresh and hr_proc.bpm else 0
        prv = hr_proc.rmssd if pulse_fresh and 0 < hr_proc.rmssd < 255 else 0xFF
        spo2 = spo2_proc.spo2 if spo2_fresh and spo2_proc.spo2 else 0xFF
        amb_fresh = amb_t >= 0 and t_ms - amb_t < FRESH_MS
        uba_fresh = uba_t >= 0 and t_ms - uba_t < 2 * FRESH_MS
        skin = read_skin_c() if tmp_ok else None
        act = activity.take() if mpu_ok else 0xFFFF

        q = 0
        if hr:
            q |= Q_HR
        if spo2 != 0xFF:
            q |= Q_SPO2
        if skin is not None:
            q |= Q_SKIN
        if amb_c is not None and amb_fresh:
            q |= Q_AHT
        if uba is not None and uba_fresh:
            q |= Q_ENS
        if mpu_ok:
            q |= Q_MPU
        if max_ok:
            q |= Q_WAVE

        struct.pack_into(STATUS_FMT, status_pkt, 0, 0x21,
                         t_ms & 0xFFFFFFFF, (sample_index - 1) & 0xFFFF,
                         hr, prv, spo2, i16_centi(skin), i16_centi(amb_c if amb_fresh else None),
                         0xFF if (rh is None or not amb_fresh) else max(0, min(100, int(round(rh)))),
                         (uba or 0) if uba_fresh else 0, act, battery, q)
        if ble.connected:
            try:
                band.status = status_pkt
                if t_ms >= next_steps_ms:
                    struct.pack_into("<BBIIIB5x", step_pkt, 0, 0x31, 1, step_boot_id,
                                     t_ms & 0xffffffff, step_counter.total,
                                     1 if accel_valid and t_ns - last_accel_ns < 160_000_000 else 0)
                    band.status = step_pkt
                    next_steps_ms = t_ms + 5000
            except Exception as e:
                print("status notify failed:", e)

    if not ble.connected and not ble.advertising:
        try:
            ble.start_advertising(adv)
        except Exception:
            pass

    time.sleep(0.005)
