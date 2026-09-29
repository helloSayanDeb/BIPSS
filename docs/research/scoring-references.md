# Scoring methods and sources

This file covers what each derived metric in BIPSS is computed from, the
published source where there is one, and every choice made where there is
none. The fidelity badge shown in the app is given for each metric.

## Signal pipeline: heart rate and PRV (`domain/signal/`)

- **Beat detection.** This follows Pan J, Tompkins WJ, *A real-time QRS
  detection algorithm*, IEEE Trans Biomed Eng 1985;32(3):230-236. The method
  was adapted for the systolic upstroke of a wrist PPG:
  - the signal is inverted;
  - a five-point derivative is applied, rectified to positive slopes;
  - a 150 ms moving-window integration follows;
  - the SPKI/NPKI adaptive thresholds are kept;
  - search-back runs at 166 % of the running interval.

  The following details are our own choices:
  - The refractory period is max(250 ms, 0.6 × median interval). A beat at 60 %
    of the interval would be a 67 % jump in rate within one beat, and what
    appears there in practice is the dicrotic wave.
  - The fiducial is the point of maximum slope, refined with a parabola.
- **Motion cancellation.** This is normalised LMS (Haykin, *Adaptive Filter
  Theory*), with the three accelerometer axes as references and 12 taps per
  axis at 50 Hz. The step size is μ = 0.08. Adaptation is frozen while the
  wrist is still.
- **Heart rate.** A two-state Kalman filter tracks [HR, dHR]. Measurement
  variance depends on signal quality: 16 bpm² when quality is Good and 49 bpm²
  when Degraded. The 16 bpm² value includes normal beat-to-beat variation, so
  the displayed rate follows the average rather than each breath. The filter
  coasts for up to 8 s. A coasted value is never stored.
- **PRV** is RMSSD over real successive interval pairs, in 60 s windows.
  - An interval that differs from the median of its five neighbours by more
    than 20 % is rejected.
  - SDNN and SD1/SD2 are computed on the gap-filled series.
  - If more than 30 % of the intervals were filled in, the result is marked
    low-confidence.
  - Reference: Task Force of the ESC and NASPE, *Standards of measurement,
    physiological interpretation and clinical use*, Circulation
    1996;93:1043-1065 (the time-domain definitions). PRV measured at the wrist
    approximates beat-to-beat variability measured from chest electrodes.
- **Validation.** Only simulated signals from the debug simulator have been
  used (`SignalPipelineTest`), and the targets below are met on them. Nothing
  has yet been validated on hardware against a chest strap.
  - Clean signal: beat F1 ≥ 0.95, HR within ±2 bpm for at least 95 % of
    seconds, and RMSSD within ±5 ms.
  - Walking: F1 ≥ 0.9 with motion cancellation, compared with about 0.55
    without it.

Fidelity: pipeline HR is **Production**. SpO₂ and PRV are **Estimate**.

## Baseline schedule (`domain/BaselineSchedule.kt`)

- A calibration day is a local day with at least 60 minutes of measured heart
  rate. Milestones fall at days 1, 3, 7, 14, 30, 45, 60, 80 and 90.
- Nothing is compared against the personal baseline before day 14.
- Days 14–29 are shown as provisional. From day 30 the baseline counts as
  established.
- Confidence rises from 0.5 at day 14 to 1.0 at day 90.

These thresholds are a design choice. A few days cannot separate a person's
normal from an unusual week.

## Strain (`domain/strain/Trimp.kt`)

- **TRIMP** is from Banister EW, *Modeling elite athletic performance*, in:
  Green HJ, McDougal JD, Wenger HA (eds), *Physiological Testing of Elite
  Athletes*, Human Kinetics 1991. The formula is
  `TRIMP = D · HRr · a · e^(b·HRr)`, where `HRr = (HR − HRrest)/(HRmax − HRrest)`.
- **Coefficients:**
  - Men: a = 0.64, b = 1.92.
  - Women: a = 0.86, b = 1.67.
  - Otherwise: the mean of the two, a = 0.75 and b = 1.795. This is our own
    choice.
- **Bouts.** TRIMP is applied to each contiguous bout of exertion, not to the
  whole day. The exponential makes TRIMP convex, so averaging a hard bout with
  an easy one would understate it.
- **Exertion gate.** A minute counts only if it has at least 3 000 mg·s of band
  activity, an average of 50 mg·s per second. This is our own threshold, set at
  the bottom of an easy walk. A raised heart rate without movement is not
  counted.
- **HRmax** comes from Gellish RL et al., *Longitudinal modeling of the
  relationship between age and maximal heart rate*, Med Sci Sports Exerc
  2007;39(5):822-829: `206.9 − 0.67 × age`.
- **HRrest** is the resting heart rate during sleep, averaged with an EWMA. On
  the first day, before any night is recorded, the lowest 5-minute mean while
  still is used instead, and the result is marked Estimate. Using HR reserve
  anchored to the sleeping resting rate agrees with %VO₂R to within about 2 %
  (PMC6827176).
- **Load words.** Below 10 is Light, 10 to 13.9 Moderate, 14 to 17.9 High and
  18 or more All out. These cutoffs are our own. Strain is shown in the
  activity colour at every size: it is load, not a health verdict.
- **0–21 scale.** No published scale exists; this is our own design:
  `21 · ln(1 + TRIMP) / ln(1 + 400)`. A TRIMP of 400, roughly two hours at
  90 % of HR reserve, maps to 21. The scale is logarithmic so that everyday
  activity does not sit pressed against zero.

Fidelity: **Production**, or **Estimate** while the first-day resting-rate
fallback is in use.

## Recovery (`domain/recovery/RecoveryEngine.kt`)

- **Inputs** are taken from each night's clean, still epochs:
  - median RMSSD;
  - mean heart rate;
  - total sleep time;
  - mean skin temperature.
- **Baselines.** Each input has an EWMA mean and variance with α = 0.1,
  following the incremental form in Finch T, *Incremental calculation of
  weighted mean and variance*, 2009. With α = 0.1 the baseline's effective
  memory is two to three weeks.
- **Standard scores.**
  - A z-score is reported once a baseline has at least 3 nights.
  - The SD is floored per input: 5 ms, 2 bpm, 30 min and 0.2 °C.
  - z is clamped to ±3.
  - Each score is oriented so that a positive value means better recovered.
- **Skin temperature** counts against recovery in either direction. It is
  centred on √(2/π), the expected |z| of an ordinary night.
- **Weights** are our own choice: PRV 0.45, resting HR 0.30, sleep duration
  0.15 and skin temperature 0.10. PRV and resting HR carry the most weight
  because they are the most studied markers of autonomic recovery.
- **Composite.** `z_c = Σ w·s / √(Σ w²)`, and the score is `100 · Φ(z_c)`, so
  50 is an ordinary night for this person.
- **Gating.** No score is shown before day 14. Days 14–29 are marked
  Provisional. A score also needs a baselined PRV or resting-HR input.

Fidelity: the composite is an **Estimate**. The detail screen shows each input
compared with the person's own baseline.

## Autonomic Load Indicator (`domain/ali/AutonomicLoad.kt`)

- The indicator compares the last 30 minutes of clean, still epochs with the
  same kind of epochs from the previous 14 days, leaving out the most recent
  2 hours. It uses robust z-scores: median / (1.4826 · MAD), with MAD floored
  at 3 ms and 2 bpm.
- **Load** is the mean of two z-scores: PRV suppression and still-HR
  elevation. Load of 1 or more is Elevated, and 2 or more is High.
- **Gates.** The indicator needs day 14 or later, at least 5 minutes of recent
  still signal and at least 2 hours of reference.
- It never drives the headline score, the health status or alert ranking. It
  is not a measure of stress, because the band has no electrodermal sensor,
  and it does not detect heart-rhythm problems.

Fidelity: **Experimental**.

## Heat stress, respiratory, flood

These are weighted budgets of environmental and body inputs, as documented in
`RiskScoreCalculator`.

- **From day 14**, the body inputs use personal deviation:
  - heart rate: 25 bpm above the sleeping resting rate earns full points;
  - skin temperature: 1 °C above the wearer's usual earns full points.
- **Before day 14**, population thresholds are used:
  - heart rate: from 70 bpm, full at 100;
  - skin temperature: from 36.5 °C, full at 38.

Fidelity: **Estimate**.

## Indoor/outdoor from GNSS (`domain/environmental/EnvContext.kt`)

The air-quality display needs to know whether the wearer is indoors, where
the band's ENS160 reads the room, or outdoors, where the CPCB station
applies. The context is judged from GNSS signal and movement only. The two
air readings are never an input: inferring "indoors" from their difference
and then trusting the band because the wearer is indoors was circular.

- **Physical basis.** A building's roof and walls attenuate satellite
  signals, typically by 10-30 dB depending on the material. Indoors, fewer
  satellites are tracked, they are weaker, fewer are used in the fix, and
  the fix is less accurate. Kjaergaard et al., "Indoor Positioning Using GPS
  Revisited", Pervasive 2010 (LNCS 6030), measured this across building
  types. Android reports each satellite's carrier-to-noise density
  (`GnssStatus.getCn0DbHz`); open-sky GPS L1 is commonly 40-50 dB-Hz.
- **Inputs**, from a window of about 12 s taken every 10 minutes by the band
  service while the band is connected. The window ends early once the sky
  is clearly open.
  - Satellites heard.
  - Satellites used in the fix.
  - Mean C/N0 of the four strongest satellites.
  - Best fix accuracy.
  - Median speed.
  - Distance from the previous check.
  - Band movement.
- **Decision.** All cut-offs below are design choices inside the published
  ranges, not measurements on this band or phone. The user's corrections
  (`context_feedback`) are what they are to be tuned against; that tuning is
  not implemented.
  - **Outdoors:** C/N0 >= 35 dB-Hz, with >= 6 satellites in the fix or
    accuracy <= 15 m. Confidence 0.6-0.9.
  - **Indoors:** C/N0 < 28 dB-Hz, or no satellites at all, together with
    <= 3 satellites in the fix and accuracy > 40 m or no fix. Confidence
    0.6-0.8, higher when the band shows the wearer still (< 600 mg·s/min).
  - A weak signal while travelling (> 2.5 m/s or > 150 m since the last
    check) could be a metro or a car. It is left as unknown.
  - Everything in between (near a window, under trees, between tall
    buildings) is unknown.
  - A check needs precise location. With approximate location there is no
    satellite detail, so no check is taken and the context stays unknown.
- **Reconciliation** (`AqiReconciler`). It runs on the shared baseline
  schedule; day N counts days with a confident check that had a band
  reading.
  - **Outdoors:** each band category (1-5) learns the station AQI it comes
    with, as a median and interquartile range, once it has >= 5 checks.
  - **Indoors:** the wearer's own usual band reading (median and 90th
    percentile) once there are >= 20 indoor checks.
  - **Display:** outdoors, the station leads and the band corroborates.
    Indoors, the band is compared against the wearer's own range, and the
    station is labelled "outdoors near you". Unclear, both are shown and
    labelled. The two readings are never averaged.

Fidelity: context **Estimate**; indoor-air comparison **Estimate**; station
AQI is the station's own figure.
