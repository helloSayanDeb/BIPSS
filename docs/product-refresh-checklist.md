# Product refresh — 1 October 2026

> Later update: see [current app state](current-app-state.md). Health is now Insights;
> Coach uses an internal intent bank, and the visible question picker has been removed.
> Phone steps were replaced by band-generated MPU6050 steps. Earlier sections below record their original delivery state.

User request: implement remaining software requirements from `something.txt`, improve Home/Health/Coach/More and onboarding using supplied WHOOP/Fitbit references, synchronize SOS countdown, add feasible everyday tracking, investigate CPCB, and suggest 25 offline Coach features.

Keep existing hydration/Coach changes. Do not push or inspect Git remotes. Hardware flashing, live SMS, and cloud account actions require real devices/accounts; do not simulate success.

## Work

- [x] AQI comparisons honor shared calibration schedule.
- [x] Missing oxygen data excluded from sleep screening; transactional persistence and correct report matching.
- [x] SOS uses one monotonic deadline for numbers, arc, and dispatch; header placement avoids overlapping content.
- [x] Home: greeting, swipeable Sleep/Recovery/Strain, real date navigation, timeline, daily windows, hydration and Coach; hazards secondary.
- [x] Snapshot and 7/30/90-day trends show recorded days without inventing missing values.
- [x] More: organized user actions, no redundant system/risk dump.
- [x] Health/onboarding/settings copy: concise user language, consistent spacing, details on demand.
- [x] Optional model: Download now / Skip; fetch only after tap; verify/load before ready; restore installed model at launch. Actual model still needed.
- [x] Phone steps with contextual permission, daily goal/history and clear unsupported state. Physical sensor testing remains.
- [x] Body metrics, personalized editable hydration starting goal, activity log, workout timer/history, breathing exercise, sleep routine and optional food journal.
- [x] Expanded offline Coach prompts and useful replies; 25-idea document.
- [x] Diagnosed official CPCB endpoint with supplied key; improved pagination/error/fallback handling. Endpoint still refuses the connection.
- [x] Built, ran 208 passing unit tests, checked emulator navigation and installed APK.

See [validation and remaining external dependencies](product-refresh-validation.md). This checklist records implemented software, not completion of physical-device testing or availability of the future AI model.

## References

- WHOOP Home: https://www.whoop.com/us/en/thelocker/the-all-new-whoop-home-screen/
- Google Health/Fitbit layout: https://blog.google/products-and-platforms/products/google-health/google-health-app/
- Android step counter and foreground tracking: https://developer.android.com/health-and-fitness/fitness/basic-app/overview
- CDC BMI: https://www.cdc.gov/growth-chart-training/hcp/using-bmi/calculating-bmi.html
- Hydration context (food also contributes; starting goal is editable, not a prescription): https://www.rightdecisions.scot.nhs.uk/media/l5cbmhjn/hydration-poster.pdf
- Official air-quality feed: https://www.data.gov.in/catalog/real-time-air-quality-index
