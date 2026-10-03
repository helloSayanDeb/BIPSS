# Research continuation — delivered and validated

> Later update: see [current app state](current-app-state.md). Health is now Insights;
> Coach uses an internal intent bank, and the visible question picker has been removed.
> Phone steps were replaced by band-generated MPU6050 steps. Earlier sections below record their original delivery state.

2 October 2026. The implementation plan was written before code changes in
[research-implementation-plan.md](research-implementation-plan.md).

## Delivered

- **Band steps:** MPU6050 detector in CircuitPython, cumulative 20-byte BLE extension,
  persistent Android accounting, duplicate/reboot/wrap handling and separate band history.
  Removed the phone step service, sensor permission and phone-tracking controls.
  Copy both firmware files as described in [the firmware guide](../firmware/bipss_band_v2/README.md).
- **Autonomic Load:** restored the Health card and explanatory expansion. The engine
  now requires enough paired, finite heart-rate and PRV epochs, and deduplicates
  timestamps before assessing coverage. It remains outside emergency decisions.
- **Coach 22–25:** explicit cycle-note summary, sleep/wake timing consistency across
  midnight and daytime schedules, a user-chosen weekly habit with mark/undo/clear,
  and two-week comparisons with recorded-day counts and minimum coverage.
- **25 additional questions:** implemented under Coach → Explore questions. The
  complete list is in [offline-coach-ideas.md](offline-coach-ideas.md). A selected
  question starts a new topic instead of being mistaken for a prior symptom follow-up.
- **Hospitals:** existing SOS auto-selection retained; added Settings → Emergency
  support → Find nearby hospitals. Preview never sends messages. Current-location
  requests disallow cached fixes; SOS's hospital-search fallback is limited to 15 minutes.
- **Drive:** added a separate Connect Google Drive action using the existing
  AuthorizationClient/app-data scope. Connection does not build/upload a health payload
  or restore data. Consent cancellation is visible. Backup contents were not expanded.
  [The setup guide](drive-backup-setup.md) includes direct Cloud links and the verified
  debug SHA-1. The user confirmed Cloud registration is not configured yet.

## Verification

- Android: `:app:testDebugUnitTest :app:assembleDebug` successful; **231 tests, zero
  failures/errors/skips**. Includes new protocol/accounting, persistence, Coach,
  circular-time and Autonomic Load quality tests.
- Firmware: **6 synthetic tests pass**, plus Python syntax compilation of `code.py`
  and `step_counter.py`. Covers stationary noise, walking-like input, short gestures,
  pure rotation, sensor gaps and invalid samples. This is not physical step accuracy.
- Installed the APK on `emulator-5554`. All test habit writes used the separate
  simulation preferences/database. Confirmed question selection, missing-week answers,
  cycle empty state, habit creation/completion and persistence across restart/update.
- Confirmed Health displays Autonomic Load's calibration state and explanation;
  Activity shows band steps with no phone step permission/setup.
- Hospital preview at **synthetic Kolkata coordinates (22.5726, 88.3639)** returned
  three nearby listed contacts (0.5, 0.9, 1.3 km), all landlines. UI correctly showed
  them as contacts to call with no mobile SMS number. This verifies lookup and display,
  not emergency availability, number ownership, hospital acceptance or SMS delivery.
- Drive Connect opened Google's account setup. Cancelled before account sign-in;
  BIPSS displayed that access was not granted. No backup upload/restore was performed.
- Runtime crash buffer empty during checks. `git diff --check` completed without errors.

Screenshots are in `app/build/qa-continuation/`: `research-question-bank-final.png`,
`research-autonomic-load.png`, `research-band-steps.png`, `research-hospitals.png`,
and `research-drive-entry.png`. Build outputs are ignored by Git.

## Honest remaining limits

1. The band is not connected to this environment. Firmware is prepared, not flashed.
   Walking/arm-motion calibration, physical sensor operation, power use and BLE timing
   still need testing on the final device. Existing AHT21/ENS160 bring-up remains unproven
   on that hardware; their presence does not establish working readings.
2. Step telemetry has a cumulative counter, not per-day offline storage. Same-day
   reconnects recover increments; midnight-spanning gaps cannot be allocated accurately
   and are excluded. Unreceived steps can be lost on a band reboot. This is documented
   rather than presented as complete offline multi-day recovery.
3. Sleep stages, VO2 max and biological age are **not implemented as validated scores**.
   The hardware can support inputs for useful estimates, but BIPSS has no validated task
   model, matching reference dataset or device study. SpO2 is saturation, not oxygen
   consumption. The report's accuracy claims cannot be transferred to this band.
4. ENS160 provides a gas-based air category. It does not measure particulate matter,
   so an independently measured PM-based indoor AQI cannot be reconstructed from it.
5. Sample Entropy, persistent nonlinear training features, external wearable imports
   and physiological foundation-model fine-tuning remain explicitly identified in
   the research plan; they have not been claimed as delivered product features.
6. Drive's real account grant/upload/restore needs the user's Google Cloud registration
   and account consent. No API key or client secret is needed for this Android flow.
   No live SOS SMS was sent in testing.

The research has therefore been audited and the specified supported product changes
implemented; the app does not yet reproduce every capability described in the report.
