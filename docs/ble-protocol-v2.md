# BIPSS band ↔ phone BLE protocol, version 2

Status: **contract frozen for app v1.1**. The phone-side decoder is
`app/src/main/java/com/bipss/app/ble/protocol/BandPackets.kt`. Byte-level tests
are in `BandPacketParserTest`. The reference firmware is
`firmware/bipss_band_v2/code.py`, which is *untested on hardware*.

## Why v2 exists

v1 sent one 24-byte frame at 1 Hz. That caused three problems:

1. **It did not fit.** At the default ATT MTU of 23, a notification carries 20
   bytes, so the phone received truncated frames and rejected every one. v2
   packets are exactly 20 bytes. MTU negotiation is still attempted, but the
   protocol no longer depends on it.
2. **It had no motion data.** The accelerometer only set a quality bit. Sleep
   detection and exertion-gated strain both need movement.
3. **It sent only the band's own beat-to-beat summary.** v2 also streams the
   PPG waveform and accelerometer, so the phone can run motion-artefact
   cancellation and beat detection itself.

## GATT layout

| UUID | Properties | Content |
|---|---|---|
| `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | service | BIPSS vendor service. **Must be in the advertisement.** |
| `6E400003-…` | NOTIFY | Status packet (below), 1 Hz |
| `6E400004-…` | NOTIFY | Waveform packet (below), 12.5 Hz |
| `6E400002-…` | WRITE | Control, reserved |
| `0x180F` / `0x2A19` | READ, NOTIFY | Standard battery service (optional) |

**Version detection.** The phone treats the band as v2 when characteristic
`6E400004` exists after service discovery. Otherwise the band is v1 and the
phone decodes the legacy 24-byte frame on `6E400003`.

If the phone sees a 20-byte `0x21` packet on `6E400003` but no `6E400004`, it
concludes that the band was reflashed and Android is using a stale cached GATT
table. It refreshes the cache and rediscovers once. If that fails, it asks the
user to unpair and re-pair the band.

## Encoding rules

- All multi-byte fields are **little-endian**.
- Every packet is **exactly 20 bytes**, and byte 0 is the header.
- Invalid readings are sent as the **sentinel** given for each field. They are
  never clamped or substituted. The phone also drops a value when its quality
  bit is clear, and drops any value outside its physiological range.

## Status packet — header `0x21`, 1 Hz, on `6E400003`

| Offset | Type | Field | Unit / scale | Invalid |
|---|---|---|---|---|
| 0 | u8 | header | `0x21` | — |
| 1–4 | u32 | band uptime | ms since boot, from `time.monotonic_ns()` | — |
| 5–6 | u16 | waveform sample index | index of the newest PPG sample at this uptime (wraps) | — |
| 7 | u8 | heart rate | bpm | `0` |
| 8 | u8 | PRV (RMSSD of pulse intervals) | ms, 1–254 | `0xFF` |
| 9 | u8 | SpO₂ | % | `0xFF` |
| 10–11 | i16 | skin temperature | °C × 100 | `0x8000` |
| 12–13 | i16 | ambient temperature | °C × 100 | `0x8000` |
| 14 | u8 | relative humidity | % | `0xFF` |
| 15 | u8 | ENS160 air-quality index | UBA 1 (excellent) … 5 (unhealthy) | `0` |
| 16–17 | u16 | activity count | mg·s over the last second (see below) | `0xFFFF` |
| 18 | u8 | battery | % | `0xFF` (unknown) |
| 19 | u8 | quality bits | see below | — |

Quality bits (byte 19):

| Bit | Meaning |
|---|---|
| 0 | pulse valid (gates HR and PRV) |
| 1 | SpO₂ valid (skin contact) |
| 2 | skin temperature valid |
| 3 | ambient temperature and humidity valid |
| 4 | ENS160 value valid (heater stable, fresh data) |
| 5 | accelerometer present (gates the activity count) |
| 6 | waveform streaming on `6E400004` |
| 7 | reserved, 0 |

The **uptime + sample-index pair** is what lets the phone fit the real PPG
sample period. The MAX30102 runs on its own oscillator, so its rate is not
exactly 50 Hz and not locked to the MCU clock. The phone never assumes 20 ms per
sample. See `BandClock`.

## Waveform packet — header `0x11`, 12.5 Hz, on `6E400004`

Each packet covers 80 ms: 4 PPG samples at 50 Hz and 2 accelerometer samples at
25 Hz.

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u8 | header | `0x11` |
| 1–2 | u16 | index of the first PPG sample | the same wrapping counter as the status packet |
| 3 | u8 | flags | bit 0 skin contact · bit 1 ADC saturated · bit 2 accel valid · bit 3 FIFO overflowed before this packet |
| 4–11 | 4 × u16 | PPG IR samples | 18-bit ADC value `>> 2` |
| 12–17 | 2 × 3 × i8 | accel x, y, z for sample 0, then sample 1 | 1 LSB = 1/64 g (±2 g) |
| 18 | u8 | band instantaneous HR | bpm, `0` = invalid; informational only |
| 19 | u8 | packet counter | wraps at 256; used to detect loss |

- **PPG rate.** The MAX30102 samples at 100 Hz with hardware averaging
  `SMP_AVE = 2`, which gives 50 Hz. That rate is fast enough to resolve beat
  timing to about 20 ms before parabolic refinement.
- **Accel alignment.** Accel sample *j* is held against PPG sample `2j` of the
  same packet. The firmware reads the MPU6050 in its main loop, so the pairing
  can be off by up to one loop period (sample-and-hold). The MPU6050 digital
  low-pass filter is set to `DLPF_CFG = 5` (10 Hz), so 25 Hz sampling does not
  alias.
- **Loss handling.** A packet whose first index is not the previous index + 4,
  a counter gap, or the FIFO-overflow flag each tell the phone that samples are
  missing. No beat-to-beat interval is measured across such a gap.

## Activity count

The band computes the activity count at the accelerometer rate (25 Hz). For
each sample it does the following:

1. **Remove gravity.** Apply a first-order high-pass filter to each axis
   (cut-off ≈ 0.25 Hz).
2. **Take the vector magnitude** of the three filtered axes, in milli-g.
3. **Apply a dead-band.** Subtract 10 mg and floor the result at 0. This
   suppresses sensor noise and tremor-level jitter at rest.
4. **Integrate** over the second: sum `magnitude × 0.04 s`. The unit is mg·s.

At rest, the result is near 0. Walking gives roughly 100–400 mg·s, and running
gives more. The phone stores it once a second as the `ACTIVITY` metric. It is
the input intended for these features:

- 60-second sleep epochs;
- the exertion gate for strain.

Any thresholds the phone applies will be in these units, so a firmware change
to this definition needs the same change on the phone. (The signal pipeline's
own motion gate uses the raw waveform accelerometer, not this count.)

## Legacy v1 frame (24 bytes, `6E400003`)

`<IIhhhhhhhBB>`, in this order:

1. seq
2. uptime ms
3. hr
4. rmssd of pulse intervals, i.e. PRV (v1 used an older name for this field)
5. spo2
6. skin ×100
7. ambient ×100
8. RH ×100
9. ENS160 AQI (UBA)
10. battery
11. quality

`-1` means invalid. The phone still decodes this frame. There are three caveats:

- A v1 battery value of exactly 100 is treated as unknown, because the v1
  firmware returns 100 when it cannot read the battery pin.
- v1 cannot provide motion or waveform data. The app's Settings screen says so
  when a v1 band is connected, and names the features that need firmware v2.
- A v1 frame truncated to 20 bytes by the default MTU is rejected.
