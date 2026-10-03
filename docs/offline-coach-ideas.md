# 25 useful Coach features without an AI model

These are bounded conversations based on saved measurements, goals and explicit user actions. They do not diagnose illnesses or invent missing readings.

| # | Idea | Current status |
|---|---|---|
| 1 | A short daily summary of steps, drinks and sleep | Added |
| 2 | Explain progress toward the user's step goal | Added |
| 3 | Explain the remaining daily drinks target | Added |
| 4 | Compare today's logged water with yesterday | Added |
| 5 | Log a drink from “Log 250 mL water” | Added |
| 6 | Log an activity from “Log a 20 minute walk” | Added |
| 7 | Review last night's recorded sleep | Added |
| 8 | Explain the user's planned bedtime and wake time | Added |
| 9 | Caffeine guidance relative to the chosen bedtime | Added |
| 10 | Screen-break and wind-down suggestions | Added |
| 11 | Outdoor activity guidance using current air quality | Added |
| 12 | UV protection guidance when a current UV reading exists | Added |
| 13 | A short breathing exercise and timer shortcut | Added |
| 14 | Explain adult BMI from saved height and weight | Added |
| 15 | Summarize logged activity minutes over seven days | Added |
| 16 | Help users start a manual food journal | Added |
| 17 | Recognize a bounded set of symptom descriptions, including common misspellings | Existing |
| 18 | Symptom follow-ups and urgent-help signposting | Existing |
| 19 | Avoid using disconnected or expired sensor readings | Existing |
| 20 | Recognize natural typed questions through an internal intent bank | Added; visible question lists removed at user request |
| 21 | Answer short follow-ups such as “tell me more” about the last supported topic | Added (reuses that topic's current context) |
| 22 | Summarize cycle notes only when the user asks | Added: explicit cycle request only, never in general model context |
| 23 | Compare bedtime consistency across recorded nights | Added: circular-clock comparison, minimum 3 recorded sessions, daytime sleep supported |
| 24 | Help choose a small weekly habit, then review its completion | Added: user-selected habit, daily completion, undo and clear |
| 25 | Explain multi-week trends with uncertainty and missing-day handling | Added: two completed weeks, coverage counts and minimum 3 recorded days per week |

Commands only write when the user explicitly asks to log something. Recorded food nutrients come from user input, not guessed meal composition. Keep future suggestions similarly bounded and testable.

Guidance references: [NHS sleep advice](https://www.nhs.uk/every-mind-matters/mental-health-issues/sleep/), [WHO UV protection](https://www.who.int/news-room/fact-sheets/detail/ultraviolet-radiation), [CDC BMI](https://www.cdc.gov/growth-chart-training/hcp/using-bmi/calculating-bmi.html). Existing symptom references are in `coach-guidance.md`.

## 25 additional questions

Internal example phrases for the offline intent bank, not a user-facing question menu. The matcher also accepts paraphrases; these answers use saved records or bounded sensor guidance. No model download is required.

1. Compare my steps over two weeks
2. How often did I reach my step goal?
3. Which day had the most steps?
4. What is my typical step count?
5. What was my longest activity?
6. What activities did I log this week?
7. Compare my activity minutes over two weeks
8. How many workouts did I log?
9. What meals did I log today?
10. Which meals have missing nutrition?
11. How many calories did I log today?
12. How much protein did I log today?
13. How consistent is my sleep schedule?
14. How consistent is my wake time?
15. Compare my sleep over two weeks
16. How many sleep records do I have?
17. Were there interruptions in my latest sleep?
18. Does my planned sleep window match my recorded sleep?
19. Compare my recovery over two weeks
20. How many days have strain records?
21. Compare my strain over two weeks
22. When did my band last send steps?
23. What can my band sensors measure?
24. What does Autonomic Load mean?
25. How can I improve my band readings?
