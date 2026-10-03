# Band step tracking update

Target: the existing CircuitPython reference firmware for Seeed XIAO nRF52840 Sense
with the external MPU6050. No phone step counter is used.

Copy **both `code.py` and `step_counter.py`** to the band's CIRCUITPY root, preserving
the installed Adafruit BLE libraries in `lib/`. Keep a copy of the previous firmware
before replacing it. The update has not been flashed or tested on physical hardware
in this session; the connected Android device is an emulator.

The detector uses acceleration magnitude at 25 Hz, gravity removal, smoothing,
a refractory interval and a four-step cadence confirmation. It continues counting
without Bluetooth or a usable PPG reading. A new random boot identifier separates
sessions. The counter resets when the band restarts; the app retains received totals.

Every five seconds while connected, a cumulative step packet follows the normal
status packet on the same BLE characteristic. Existing status and waveform formats
are unchanged. Counts represent an estimate: rhythmic arm movement can look like
walking, and short bouts under four steps are deliberately suppressed.

The phone recovers same-day disconnected increments after it has an anchor.
The counter has no wall clock or per-day storage: an interval spanning midnight
cannot be split accurately and is excluded rather than assigned to the wrong day.
Steps not received before a band reboot may be lost. This is not yet an offline
multi-day step-history implementation.

## Validation

`python -m unittest discover -s firmware/bipss_band_v2 -p 'test_*.py' -v`

Synthetic tests cover stationary noise, walking-like cadence, isolated gestures,
rotation without acceleration-magnitude change, missing samples and invalid input.
Before claiming step accuracy, compare manual counts with the final worn device:
slow/normal/fast walks, runs, short bouts, stairs, typing, brushing teeth, vehicle
travel, removal from wrist, reconnect, reboot and midnight. Tune thresholds against
held-out recordings, reporting both missed steps and false counts. Synthetic tests
do not establish physical accuracy or battery life.
