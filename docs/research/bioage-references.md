# BIPSS BioAge, VO2 max and the features around them: sources and design choices

BioAge follows the approach of WHOOP's Healthspan feature (Lee, Bailey, Beattie,
Capodilupo et al., *The WHOOP Healthspan Feature*, 2025 white paper). That paper
publishes the framework, the nine contributors, their neutral points and the
windows. It does not publish its hazard curves or the correction it applies for
overlapping contributors. Where it is silent, the choices below are ours and
are labelled as such. BioAge is a lifestyle estimate, not a medical biological
age, and the app says so on the BioAge screen.

## The model

- Mortality rises about 10 % per year of age (Gompertz), so a contributor whose
  hazard ratio against a healthy reference is HR is worth `ln(HR) / 0.1` years.
  (White paper, "Using All-Cause Mortality Risk to Calculate an Effective Age".)
- The reference is a person meeting health guidelines, not the average person,
  so BioAge equals age only for someone who meets them.
- Window: BioAge uses up to 180 days; Pace of Aging compares the last 30 days
  with the 180. Pace = `1 + (years30 - years180) / 0.5`, clamped to -1..3: half a
  year more than the calendar is a pace of 1. (White paper, "What is Pace of Aging?")
- **SHRINK = 0.5 (ours).** WHOOP corrects for the contributors overlapping
  (resting heart rate, VO2 max and activity move together) with a structural
  model fitted to its members' data, which is not published. We scale every
  contributor's years by 0.5 instead, and cap each at +/-4 years (VO2 max +/-5).
- Stages: fewer than 14 days of wear, or fewer than 3 usable contributors, shows
  progress, not a number; 14-29 days is provisional; 30 or more is established.
  A day counts for steps and zone minutes only with 10 hours of measured heart rate.

## Contributors

| Contributor | Neutral point | Slope used | Basis |
|---|---|---|---|
| Sleep duration | 7 h | below 7 h, +12 % hazard per hour (to 3 h); 7-9 h, hazard 0.97; above 9 h, nothing | White paper: short sleep +12 % all-cause mortality (meta-analysis of 5 million); 7-9 h "minor reduction"; over 9 h no effect |
| Sleep consistency | 70 % | hazard 0.8 at 100 %, 1.25 at 40 % | White paper: 70 % threshold; highest vs lowest regularity 20-48 % lower mortality |
| Resting heart rate | 60 bpm male, 64 female, 62 other | +9 % per 10 bpm | White paper: 10 bpm = 9 % mortality; neutral values as published |
| Daily steps | 8 000 (6 000 from age 60) | +14 % per 1 000 below (to 4 000 below); -5 % per 1 000 above (to 2 000) | White paper: plateau at 8-10 k, fewer needed over 60. The per-1 000 slope is ours: published meta-analyses range from about 12 % to 23 % |
| Zones 1-3 (40-80 % HRR) | 100 min/week (to 70 by age 100) | hazard 1.30 at 0, 0.72 at 600 | White paper: 100 min/week, benefit to 600; 20-50 % lower mortality at 5-7 h |
| Zones 4-5 (80 %+) | 10 min/week | `exp(0.01823 * (10 - min))`, flat from 25 | White paper: 10 min/week; 15-20 min vigorous = 18-24 % lower mortality |
| Strength | 40 min/week | hazard 1.25 at 0, 0.90 at 120, flat above | White paper: 40 min; U-shaped, optimum 30 min-2 h; 10-30 % lower mortality |
| VO2 max | WHOOP table by age and sex | +13 % hazard per MET (3.5 mL/kg/min) below the target | White paper, Figure 4 (F 40 at 20 ... 18 at 100; M 46 ... 21) and 13 % per MET |
| Lean body mass | 80 % male / 67 % female at 30, minus 0.15 points a year | +11 % per 10 points of body fat | White paper: 67 / 80 % at 30; 11 % per 10 % body fat. The age slope is ours |

Strength and lean body mass are optional. Strength counts only for someone who
has logged (or confirmed) strength-type sessions: never logging it is not the
same as never doing it. Lean body mass counts only when typed in from a scale.
Strength-type activities: Strength, Workout, Yoga.

## Zone minutes

Heart-rate reserve zones as in the white paper: Z1 40-60 %, Z2 60-70, Z3 70-80,
Z4-5 80+. HRrest is the sleep-derived baseline (or the lowest still five minutes
until there is one); HRmax is Gellish 206.9 - 0.67 x age. A minute counts only
with movement (the same gate Strain uses), so a raised heart rate at a desk is
not exercise.

## VO2 max

- **Passive.** Jackson et al. (1990) non-exercise equation (as in the NHANES
  cardiovascular fitness manual): `56.363 + 1.921*PA-R - 0.381*age - 0.754*BMI + 10.987*male`.
  Its activity rating is taken from measured weekly minutes instead of a
  questionnaire answer: vigorous (60 %+ HRR) 10 / 30 / 60 / 180 min a week
  gives ratings 4 / 5 / 6 / 7; moderate (40-60 %) 10 / 60 min gives 2 / 3;
  otherwise 1 with 3 000 steps a day, else 0. Typical error 5.6 mL/kg/min.
  Valid for ages 20-70, BMI 15-45.
- **Heart-rate ratio.** Uth et al. (2004), `15.3 x HRmax / HRrest`, built on
  well-trained men aged 21-51 (SEE 2.7 mL/kg/min there). Blended 0.3 with 0.7
  Jackson for ages 20-50 only; error then taken as 5.0.
- **Active.** ACSM metabolic equations on level ground: walking (50-100 m/min)
  `0.1 v + 3.5`, running (above 134 m/min) `0.2 v + 3.5`. The share of
  heart-rate reserve used stands for the share of VO2 reserve (Swain & Leutholtz,
  1997): `VO2max = 3.5 + (cost - 3.5) / %HRR`. Used only for 8+ steady minutes
  (cadence within 12 %, heart rate within 8 % and drifting under 2 bpm/min) at
  40-95 % of reserve (50 % for runs). Speed is GPS speed when the walk or run was
  recorded in the app with GPS, else step cadence x 0.414 x height (walking
  only; cadence cannot give running speed). Error taken as 12 % (GPS) or 22 %.
- **Combined** by inverse-variance weighting; the active estimates of the last 30
  days are kept per day and averaged (extremes trimmed).
- WHOOP's own VO2 max (passive, GPS-assisted and calibrated models) is
  proprietary; only its published accuracy (GPS model, about 3.3 mL/kg/min mean
  absolute error) was available to us.

## Sleep planner

Baseline need: 75th percentile of the last 14 nights, held between 7 and 9 h
(8 h until 7 nights exist). Plus up to 45 min after a hard day (strain above
10, full at 18) and a quarter of the last seven nights' debt (to 60 min).
Bedtime = wake time - need - 15 min to fall asleep. Design choices, not a
published formula.

## Journal

The next morning's recovery after "yes" days against "no" days, shown only with
5+ days of each and a gap of at least 1.3 standard errors (Welch). An
association in one person's own data, not a cause.

## Workout detection

Movement (Strain's gate) with heart rate at 35 % of reserve or more for 10+
minutes, bridging pauses of up to 2 minutes. Type guess from cadence (150+ run,
80-150 walk, otherwise workout); the user names it or dismisses it. Cycling
cannot be told apart on a wrist and is left to the user.
