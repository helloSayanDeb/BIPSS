# Product refresh — 2 October 2026

Implemented against `something.txt`, the subsequent user corrections, the supplied WHOOP/Fitbit screenshots, and the descriptive dashboard brief in `Pasted text.txt`.

## Delivered

- Home now starts with a named time-based greeting and a swipeable Sleep / Recovery / Strain overview, with all three summaries available below it. Local hazards are further down the page. SOS has its own header button and cannot overlap a content action.
- The seven-day date strip and calendar select actual stored days, limited to the last 90 days. Historical days show their recorded sleep, activities, drinks, steps and vital averages. They do not replay today's environmental data.
- Snapshot groups Sleep, Activity and Recovery, with a daily heart-rate graph and a timeline. Sleep includes timing, efficiency, awakenings and available oxygen readings. Trends supports 7, 30 and 90 days; missing days remain gaps.
- Daily rhythm cards cover caffeine, movement, screen breaks, daylight, hydration, recovery and sleep preparation. Details open on tap. Timing follows the user's editable sleep schedule; these are lifestyle suggestions, not measured circadian or vitamin-D values.
- More groups profile, everyday tracking, band, SOS, Coach download and settings. Health explanations expand on tap. Removed the experimental Autonomic Load section, model/rule-engine badges and unnecessary developer/provider copy from the main flows.
- Optional phone steps use Android's step counter with activity-recognition permission and a visible tracking notification. Added daily goal, seven-day history, pause/resume and unsupported-sensor messaging. Counting avoids importing steps from before opt-in or assigning a reboot/midnight gap to today.
- Activity logging, a persistent workout session timer, recorded-session heart-rate charts with sample-weighted average and true peak, and a one-minute breathing exercise.
- Adult BMI uses saved height and weight. Eligible adults receive an editable weight-based starting drinks goal. These measurements do not silently change the published physiological scoring formula.
- Optional food journal records names and user-entered calories/protein; unknown nutrients stay unknown. Its Home shortcut appears only after enabling it.
- Offline Coach answers daily questions, uses recorded values, offers starter chips, accepts explicit drink/activity log commands and retains existing symptom guidance. See [25 ideas and their implementation status](offline-coach-ideas.md).
- Optional AI offers Download now / Skip for now. No network availability check occurs on opening. A tap checks the configured manifest; a missing model is reported then. Downloads use partial files, HTTPS manifest checks, bounded size, checksum verification, and initialization before ready. Installed models load locally at launch. Initialization and inference are serialized.
- SOS digits, circular progress and expiry use one monotonic deadline. AQI comparisons and the sleep heart-rate gate now wait until the shared day-14 baseline. Nights without oxygen readings are excluded from oxygen-dip averages; replacing a sleep session and its events is transactional, and report rows match sessions by time rather than list position.

## Verification

- `:app:assembleDebug` succeeded with the existing offline dependencies.
- `:app:testDebugUnitTest`: **208 tests, 0 failures, 0 errors**. Added tests cover counter reset/day boundaries, BMI/goal bounds, shift-work windows, explicit Coach commands and unknown readings, manifest validation, countdown boundaries, and missing oxygen nights. Updated baseline tests enforce day 14.
- Installed on `emulator-5554`. All test writes used the separate simulation profile/database.
- Checked core navigation, historical date/Snapshot consistency, calendar future-date disabling, no-band states, unsupported step sensor, workout start/save, Coach drink logging, optional food entry with unknown nutrients, sleep routine save, and Download now → no model found → Skip.
- Processed an eight-hour simulated band stream through the real packet/pipeline/sleep analysis. Snapshot showed the resulting 7h 7m detected sleep, score 88, five interruptions, average oxygen, and the independently logged 250 mL drink after restart. These are test data, visibly labelled simulated.
- Opened and cancelled SOS; no alert was sent. An overlap discovered between the old floating SOS control and Snapshot was fixed by moving SOS into the header, then Snapshot navigation was rechecked.
- Final visual fixes also distinguish wellness labels from risk labels, so a good Sleep or Readiness score does not say “Low.” The final APK built and installed successfully. The 90-day trend view opened correctly, the crash buffer was empty, and `git diff --check` passed. The emulator is in normal data mode.
- Screenshots are in `app/build/qa-continuation/`. Unit test HTML: `app/build/reports/tests/testDebugUnitTest/index.html`.

## External dependencies and limits

- **No AI model exists yet**, confirmed by the user. The download/setup flow is implemented; a real model download and inference cannot be verified until a compatible model and manifest are published.
- **CPCB**: the supplied key replaced a different key in ignored `local.properties`; no key is recorded here. Repeated requests to `api.data.gov.in` failed at TCP connection with “target machine actively refused it,” before any HTTP/authentication response. Key validity is therefore unknown. Pagination and distinct authentication/rate-limit/network/empty-data outcomes are implemented; the app retains its clearly labelled modeled estimate when station data is unavailable.
- To investigate CPCB further, try the official API from another network (for example a phone hotspot). If it becomes reachable, check the key and resource access in the data.gov.in account. Share the HTTP status and a redacted error message, not another exposed key. An HTTP 401/403 needs account/key investigation; TCP refusal needs connectivity/service investigation. Official catalog: https://www.data.gov.in/catalog/real-time-air-quality-index.
- A real phone is needed to validate step-sensor accuracy, service longevity and OEM background behavior. The emulator has no step counter. Physical band firmware/sensors, live SMS/hospital delivery and Drive authorization were not exercised.
- The new activity/food/routine records stay local. Extending the existing Drive backup to include them was not applied because approval review required explicit authorization for that additional health-data payload. Existing backup behavior is retained.
- Daily score trends build from scores recorded by this version while the app observes them; historical sleep sessions remain browsable, but unsupported historic recovery/strain values are not invented. Existing overnight processing remains scheduled by the app.
- Sleep stages, VO2 max, biological age, calibrated indoor numeric AQI and photo-based meal estimates are not fabricated. They need appropriate sensors, validated methods or a model. Indoor air remains the band's supported category and personal comparison.

No Git remote was inspected and nothing was pushed.
