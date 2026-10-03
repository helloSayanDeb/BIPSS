"""Pure validation helpers for the AHT21/ENS160 wire formats."""
import math

def crc8(data):
    crc = 0xff
    for value in data:
        crc ^= value
        for _ in range(8):
            crc = ((crc << 1) ^ (0x31 if crc & 0x80 else 0)) & 0xff
    return crc

def decode_aht21(frame):
    if len(frame) != 7 or frame[0] & 0x80 or not frame[0] & 0x08:
        return None
    if crc8(frame[:6]) != frame[6]:
        return None
    humidity = ((frame[1] << 12) | (frame[2] << 4) | (frame[3] >> 4)) / 1048576.0 * 100
    temperature = (((frame[3] & 15) << 16) | (frame[4] << 8) | frame[5]) / 1048576.0 * 200 - 50
    return temperature, humidity

def ens_ready(status):
    return bool(status & 0x80) and not status & 0x40 and (status >> 2) & 3 == 0 and bool(status & 2)

def ens_compensation(temperature, humidity):
    if not math.isfinite(temperature) or not math.isfinite(humidity) or not -40 <= temperature <= 85 or not 0 <= humidity <= 100:
        return None
    t = int((temperature + 273.15) * 64 + 0.5)
    h = int(humidity * 512 + 0.5)
    return bytes((0x13, t & 255, t >> 8, h & 255, h >> 8))

def pulse_is_fresh(now, last_sample, now_sample, last_beat, maximum_age=5000):
    return last_sample >= 0 and 0 <= now - last_sample < maximum_age and last_beat >= 0 and 0 <= now_sample - last_beat < maximum_age
