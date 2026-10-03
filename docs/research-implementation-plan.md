# Biosignal research audit and implementation plan

> Later update: see [current app state](current-app-state.md). Health is now Insights;
> Coach uses an internal intent bank, and the visible question picker has been removed.
> Phone steps were replaced by band-generated MPU6050 steps. Earlier sections below record their original delivery state.

Prepared 2 October 2026, before implementing this continuation. The supplied research is a direction, not evidence that identical sensors or borrowed equations reproduce a commercial algorithm's accuracy.

## Audit

| Research capability | Current state | Action |
| --- | --- | --- |
| Raw PPG + synchronized motion | Firmware v2 / Android waveform pipeline, packet-loss and clock handling | Preserve; extend telemetry with a band-generated step counter |
| Band-pass filtering, NLMS motion cancellation, Kalman HR, clean PRV | Implemented in domain/signal; missing observations are withheld | Preserve quality gates; do not interpolate missing data into measured readings |
| RMSSD / personal baseline / Strain / Recovery | Implemented with baseline maturity and missing-data states | Retain; these are BIPSS estimates, not WHOOP's proprietary scores |
| SDNN / Poincaré / Sample Entropy | SDNN and SD1/SD2 exist in PRV extraction; SDNN is retained in epochs; no SampEn implementation | These are statistical features, not independent health scores. Keep SampEn and persistent nonlinear features in the model-evaluation backlog until the analysis window and target task are defined |
| Autonomic Load | Engine retained; Health card removed during copy simplification | Restore a separate card with still-signal requirements, neutral wording and limitations |
| Steps | Phone sensor implementation | Replace with MPU6050 firmware detection, cumulative BLE telemetry, duplicate/reboot/day handling; remove phone tracking |
| Sleep/wake and sleep summaries | Implemented; no trained 4-stage model | Add shift-aware schedule consistency to Coach; preserve absence of REM/deep labels |
| Advanced sleep staging / PaPaGei / physiological ML | No trained, device-validated model | Document training/evaluation pipeline and release gates; no invented model performance |
| VO2 max | No fitted model or validated field protocol in app | Explain feasible estimation route; HR/SpO2 alone do not supply oxygen consumption; do not apply male-athlete HR-ratio equation to everyone |
| Biological age | No validated outcome model | Keep absent pending a defined target and validation; predicting chronological age is not validation of biological age |
| ENS160 air quality | Gas-derived UBA category, temperature/humidity compensation | Retain category; cannot recover PM2.5/PM10 concentration from this sensor |
| Hospital selection | Location-based nearest-three lookup already in SOS | Expose a read-only preview, location permission/errors and contact availability; retain automatic lookup during actual SOS |
| Google Drive | AuthorizationClient, consent resolution, appData backup/restore exist | Add authorization-only connection action and setup instructions; Cloud Android OAuth registration and account consent remain external |
| Coach ideas 22–25 | Not implemented | Explicit cycle summary, sleep consistency, chosen weekly habit, missing-data-aware multiweek comparisons |
| New Coach questions | Existing short starter list | Add 25 browsable deterministic questions backed by real context or bounded explanations |
| Open Wearables / HeartPy / external ecosystems | Custom BLE and Kotlin DSP already implemented; no cross-vendor account import | Not interchangeable requirements. Benchmark alternative DSP offline; third-party account imports need a separate data-access flow and chosen vendors |
| EDA stress sensing | No EDA electrodes/sensor in the listed hardware | Pulse-derived autonomic patterns remain possible, but cannot be labelled measured skin conductance or uniquely identify emotional stress |

## Implementation order and acceptance

1. MPU6050 detector runs on the band even while disconnected. Add a 20-byte extension without changing existing status/waveform layouts. Reject duplicates and implausible counter changes. Do not assign an un-timestamped overnight gap to a fabricated day. Firmware synthetic tests and parser/accounting tests; physical walk/arm-motion validation remains required.
2. Restore Autonomic Load in Health, keeping it outside emergency decisions. Explain what it observes, without claiming it diagnoses stress or illness.
3. Add local weekly habit state, explicit commands, cycle summaries only when asked, circular-clock sleep consistency and two-week comparisons with counts of recorded days. Add a browsable question bank and deterministic tests.
4. Add hospital preview and Drive authorization-only UI. No emergency message is sent by a preview. Connecting Drive does not upload or restore data. Do not extend the backup payload implicitly.
5. Build, run relevant tests and inspect the app in the emulator. Record unavailable hardware, live delivery and account checks explicitly.

## Model roadmap and release gates

- Collect consented MAX30102 IR/red and MPU6050 recordings with monotonic timestamps, signal quality, placement and firmware version; establish ECG/PSG or lab ground truth appropriate to the target.
- Motion HR: evaluate current DSP against ECG on PPG-DaLiA and device recordings and verify the already implemented NLMS adaptive filter against a no-cancellation comparator. PPG/motion cadence overlap can remove the real pulse if cancellation is applied blindly.
- Sleep stages: use a dataset with PPG/beat intervals AND accelerometry paired to PSG/EEG labels. CAP's EEG-oriented channels are not a drop-in substitute for this band's optical channels. Split by person, keep a held-out device cohort, report per-stage sensitivity, confusion matrix, kappa and failure/coverage rate.
- Foundation models: evaluate a licensed PaPaGei checkpoint as a feature extractor; train a task-specific head, export/quantize, then measure on-device memory/latency and accuracy loss. A chat model download and a biosignal classifier are distinct artifacts.
- VO2: collect speed/workload plus clean HR and lab VO2 references or implement a separately validated field-test protocol with its eligibility limits. Validate across sex, age, medications and fitness range. Do not ask people to reach maximum HR solely to populate a score.
- Age: define the outcome first (fitness-age comparison, chronological-age prediction and biological-aging risk are different). Validate against that outcome before surfacing a number.
- Do not promise 70–80% accuracy from a library or a foundation model. Dataset/channel/population mismatch and evaluation design determine performance.
- The report's blanket claims that higher entropy means healthier physiology, all proprietary Strain is Banister TRIMP, or PPG always underestimates ECG HRV under stress are not adopted as universal facts. BIPSS needs its own method-specific evidence.

## Primary references checked

- [MAX30102 datasheet](https://www.analog.com/media/en/technical-documentation/data-sheets/MAX30102.pdf)
- [ENS160 technical article](https://www.sciosense.com/wp-content/uploads/2023/12/ENS160-Technical-Article.pdf)
- [WHOOP VO2 estimate inputs](https://www.whoop.com/us/en/thelocker/how-accurate-is-whoop-vo2-max/)
- [Uth HR-ratio study, well-trained men](https://pubmed.ncbi.nlm.nih.gov/14624296/)
- [Oura multi-night PSG validation](https://pubmed.ncbi.nlm.nih.gov/38382312/)
- [CAP database channels and purpose](https://physionet.org/content/capslpdb/)
- [PaPaGei paper](https://arxiv.org/abs/2410.20542)
- [Google Android authorization](https://developer.android.com/identity/authorization)
