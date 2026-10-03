# Current app state — 3 October 2026

**The app does not yet implement the full research vision or every example in the UI brief.**
The core app and much of the requested design structure exist. Software implementation,
physical sensor validation, and trained-model capabilities are different completion gates.
Drive setup is deferred by the user; the setup guide remains available.

## Research coverage

| Area | Current state | Remaining work |
|---|---|---|
| Raw PPG + motion | v2 BLE waveform pipeline, sample timing/gap handling, band-pass filters, NLMS cancellation, Kalman HR | Validate accuracy and timing on the final band |
| Pulse variability | RMSSD, SDNN, signal-quality gates; SD1/SD2 extraction exists | Persistent nonlinear training features, Sample Entropy and task validation |
| Recovery, Strain, Readiness | Personal baselines, missing-data states and BIPSS scoring | Device/population validation; these are not proprietary WHOOP algorithms |
| Autonomic Load | Restored, with still-signal coverage gates and personal comparison | Experimental; does not drive emergency decisions or diagnose stress |
| Band steps | MPU6050 firmware detector, BLE cumulative count and Android persistence | Flash/test physical band; multi-day offline timestamped step history is not implemented |
| Sleep | Sleep/wake summary plus opt-in wav2sleep Wake/Light/Deep/REM estimates, Unknown sections and nightly timeline | Physical-band validation against reference sleep stages; experimental stages do not alter scores |
| BioAge, VO₂ max | BIPSS BioAge and Pace of Aging from nine contributors (sleep, steps, heart-rate zones, resting HR, VO₂ max, optional strength and lean mass); VO₂ max from measured activity plus steady walks and runs | Lifestyle estimates, not medical biological age or measured VO₂; WHOOP's hazard curves are unpublished so ours are documented design choices (`docs/research/bioage-references.md`); needs weeks of band wear and the step firmware |
| Workouts, planner, report, journal | Workouts found from heart rate and movement for the user to name; sleep planner with bedtime reminder; weekly report (Monday notification, PDF); daily journal with morning-after recovery comparison | Detection and step cadence untested on a physical band; cycling cannot be told apart on a wrist |
| Export and widget | PDF health report, overnight oxygen record PDF, weekly report PDF and all-data CSV ZIP; home-screen water-log widget | Documents are English-only |
| Air quality | Outdoor feed/model fallback; ENS160 gas category kept separate | CPCB endpoint last checked refused connection; final ENS160/AHT21 bring-up unverified; no particulate sensor |
| Foundation models / other wearables | PaPaGei-S frozen encoder in local debug build, tested Android inference and versioned private features | Checkpoint distribution terms, task-specific heads and validation; no cross-vendor account imports |
| Local chat AI | Optional Download/Skip/install flow | No model has been made/published, as confirmed by the user |
| SOS / hospital lookup | Shared countdown clock, nearby lookup and preview, existing delivery flow | No live emergency SMS/hospital delivery test; lookup does not establish hospital acceptance |

See [research plan and primary references](research-implementation-plan.md) and
[earlier validation](research-implementation-validation.md).

The [3 October model and fitness implementation report](biosignal-models-and-fitness.md)
documents the model revisions, conversion parity checks, data lifecycle, and remaining
validation and public-release requirements. 252 JVM and four Android instrumented tests pass.

## UI brief coverage

| Requested design | Current state |
|---|---|
| Calm wearable dashboard, three main dimensions | Home greeting, swipeable Sleep/Recovery/Strain focus, compact summaries; hazards are secondary |
| Integrated date navigation | Date strip/calendar and recorded-day Snapshot |
| My Day / context windows | Logged events; caffeine, movement, screens, sunlight, hydration, recovery and wind-down windows |
| Sleep/activity/recovery sections | Snapshot, supporting values and recorded heart-rate chart |
| Long-term trends | 7/30/90-day Sleep/Recovery/Strain/step trends; BioAge and VO₂ max history and nightly stage history |
| Dedicated insight pages | Separate arrow next to expandable card; score, date selection, 7/30/90-day score/input charts, saved calculation inputs, baselines when available, contributors and interpretation |
| Workout detail | Timer/log, duration and recorded HR curve/summary |
| Food and optional modules | Manual food journal, hydration, body measurements, sleep routine and breathing timer; no photo nutrient estimation or medication/supplement module |
| Progressive disclosure | Card explanation stays inline; detailed history has its own page |
| Navigation | Home / Insights / Coach / More, with an Insights icon |
| Reference fidelity | Structure and visual direction implemented; not a pixel-for-pixel WHOOP/Fitbit clone. Full device/font-size/accessibility matrix remains unverified |

## Coach correction

Removed the question picker and starter-question chip list. The question bank is now
internal: ordinary typed phrases map to bounded offline intents. Examples include
“Is my bedtime regular?”, “Are my steps improving?” and “What does the band measure?”.
Answers use saved data, report missing coverage, and explain their period of comparison.
The matcher does not provide arbitrary general knowledge or the flexibility of a language model.
Log commands remain explicit and bounded; a question does not authorize a write.
Personal cycle notes require an explicit personal request and stay out of model context.

## Insight history semantics

- Each record stores the result and the inputs from that same calculation, including
  the usual baseline where available. Historical pages do not substitute current inputs.
- Up to 90 local calendar days are retained. A checkpoint is saved at most once per
  minute while the dashboard scoring flow runs; these are latest saved calculations,
  not continuous monitoring or daily averages.
- Previously saved Sleep/Recovery/Strain scores remain visible, with no invented past inputs.
  Other insight history starts with this update. Missing days break the chart line.
- Unavailable scores are not plotted as zero. Autonomic Load keeps its categorical
  labels; its actual numeric input trends can be viewed separately.
- A later missing reading does not erase the last usable saved result for a day.
  Today's detail page shows the current calculation; previous days show saved records.

## Validation of this update

- `:app:testDebugUnitTest :app:assembleDebug` passed: **242 tests**, zero failures/errors/skips.
- Tests cover all 28 history intent categories with conversational paraphrases, negated cycle requests,
  unrelated-word collisions, explicit write boundaries and symptom priority.
- Insight tests cover serialization/reopening, retained historical inputs, gaps, retention,
  unavailable vs true-zero scores, categorical Autonomic Load, Strain scale/locale and alert-check gating.
- Installed the final debug APK on `emulator-5554`. Checked Insights label/icon and separate arrows,
  Heat Stress score/input charts, 30-day range, yesterday's missing score/inputs, Readiness empty state,
  and a typed “Is my bedtime regular” question with a correct insufficient-history reply.
- Screenshots: `app/build/qa-continuation/insights-overview.png`, `heat-detail.png`,
  `heat-inputs.png`, `coach-natural-question.png`. UI checks used the isolated simulation profile, visibly labelled as test data.
- Crash log empty during checks; `git diff --check` passed. Physical-band tests remain outstanding.
- Final APK SHA-256: `FCF8949ACC6574A4462FAF6171EDFDEDB203410D7D35DAFDA5FDEBA864E2DE84`.

A successful weather fetch no longer implies that the alert feed was checked. Flood scoring and
Heat Stress's alert contribution now require the independent successful alert-check timestamp.

