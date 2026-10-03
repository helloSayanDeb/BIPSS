# Hydration and Coach continuation

Completed and verified on 1 October 2026, continuing chat
`01a0f32f-cec9-7251-8a0d-a79779b96007`.

## Delivered

- Home hydration control: minus / 250 mL / plus; subtract corrects today's
  newest drinks transactionally and cannot reduce the total below zero.
- Hydration detail: optional personal goal and progress, today's removable
  drink entries, and seven local calendar days of history. Goal persists in
  preferences and is included in the existing opt-in Drive backup payload.
- Offline symptom responses before model inference or metric reporting,
  including dizziness spelling variants, basic follow-ups, local negation,
  and urgent-help signposting. See [guidance sources](coach-guidance.md).
- Coach readings expire independently and clear on disconnect or a withheld
  pipeline pulse. The water answer uses today's actual logged total.
- Chat scroll accounts for briefing cards so the newest response is visible.
- Home scores recalculate when a water log is corrected, including without a band.

## Verification

- Debug APK built with the existing offline Gradle dependencies.
- 198 unit tests, zero failures/errors. Includes local-day history boundaries,
  corrections across entries, zero clamp, unchanged neighbouring days,
  symptom precedence/negation/follow-ups, sensor freshness and backup compatibility.
- Installed on the existing `emulator-5554`.
- Emulator checks used the separate simulation database for water mutations:
  additions, subtraction to zero, individual deletion, goal saving and retention
  across process restart; Coach dizziness, follow-up and water-total responses;
  live heart-rate answer followed by no-current-reading after stopping the stream.
- Final screenshots inspected for hydration and Home with no band. Crash buffer
  was empty; `git diff --check` passed. Emulator is back in real-data mode.

Screenshots and the UI test helper are under `app/build/qa-continuation/`.
APK: `app/build/outputs/apk/debug/app-debug.apk`.

This verification does not exercise physical band hardware or live Google Drive
authorization. The offline symptom matcher supports a limited set of English
phrases; it is not a clinical triage system or a general language model.
