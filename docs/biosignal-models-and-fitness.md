# Fitness and biosignal implementation — 3 October 2026

## Delivered scope

- Insights → Cardio fitness: VO₂ max from the band's own data, with nothing to fill in: a
  passive estimate (Jackson non-exercise equation with its activity rating taken from measured
  heart-rate-zone minutes and steps, blended under 50 with the Uth heart-rate-ratio estimate)
  and an active estimate from steady walks and runs (ACSM equations scaled by heart-rate
  reserve; phone GPS speed when the workout was recorded with it). Shown with its typical error.
- Insights → BIPSS BioAge: lifestyle "effective age" from nine contributors, after WHOOP's
  Healthspan approach, with Pace of Aging. Replaces the earlier decade-band fitness age.
  Sources and design choices: `docs/research/bioage-references.md`.
- Insights → Sleep stages: opt-in pulse recording, on-device wav2sleep inference, nightly
  timeline, prior-night selection, stage duration totals and explicit Unknown intervals.
- Offline Coach answers questions about the saved estimates, dates and limitations.
- PaPaGei-S frozen foundation encoder runs in the **local debug build** and saves bounded,
  versioned feature vectors from recorded pulse windows. No untrained task head converts
  embeddings into health advice, biological age, diagnoses, recovery or emergency decisions.

## Models and provenance

### Sleep

[wav2sleep](https://github.com/joncarter1/wav2sleep), source revision
`278e30463c8149c4e6899b8784da492fec695bd9`, MIT license (notice included in assets).
[Checkpoint](https://huggingface.co/joncarter/wav2sleep) revision
`8b6e46ab3ef6e3945282b81f631933c59db0b205`; original SHA-256
`ea6fb4410315cf6cce406fe1ffd44cba83e8dc69be6a69101aff62cc2cbee0bc`.

The PPG-only ONNX export retains the published architecture and weights. 1024 samples per
30-second epoch, whole-recording sample-standard-deviation normalization, four logits in
Wake/Light/Deep/REM order. Whole-night execution preserves instance-normalization context.
Conversion script: `tools/models/export_sleep.py`; provenance/checksums: asset `wav2sleep.json`.

The band streams IR PPG at 50 Hz. Archive interpolation uses the fitted sample clock and
the 34.1333 Hz model clock. No interpolation crosses lost contact, saturation, reconnect
boundaries or gaps over 120 ms. An epoch needs at least 1004/1024 samples and non-flat signal;
at most 20 missing points in an otherwise usable epoch are filled by its mean.
Night analysis requires 1–10 hours and 90% usable epochs. Missing epochs receive the
recording mean before normalization, are never labelled, and their two neighbouring epochs
on either side also remain Unknown. Model maximum softmax below 0.55 remains Unknown;
that threshold is an engineering abstention rule, not calibrated accuracy.

The model is published for research. Its paper's sleep-study results do **not** establish
performance for MAX30102 wrist IR PPG, this firmware, our resampling, dropout rules or users.
No sleep score, readiness result or alert is changed by these experimental stage labels.

### Foundation encoder

[PaPaGei source](https://github.com/Nokia-Bell-Labs/papagei-foundation-model), revision
`0c537dad4d2850e15b724260de820dd68d77f0b0`; repository LICENSE is BSD 3-Clause,
while source headers say BSD-3-Clause-Clear. Both notices are retained as supplied.
[Published weights](https://zenodo.org/records/13983110), PaPaGei-S original SHA-256
`79d68671e51bdc951fee853e5a8241099e38148ff26ed888b843849332e0b988`.

**Distribution gate:** Zenodo's record metadata contains no weights license. The converted
checkpoint is therefore in `src/debug/assets` for this local evaluation; release builds do
not contain it. Confirm checkpoint redistribution terms with the publisher before a public
release. The runtime gracefully skips the optional encoder when its asset is absent.

`tools/models/export_papagei.py` strictly loads all checkpoint parameters and exports its first
512-dimensional output. ONNX graph optimizations must be disabled for this model: the
Pad+MaxPool fusion changes explicit zero padding to negative infinity on odd-length inputs
and failed parity (up to 0.335 embedding error). Disabling optimization restored agreement
within 0.000004 across tested inputs. Android has an independent original-PyTorch fixture.

Preparation follows the pyPPG 1.0.41 Chebyshev-II filter (order 4, 20 dB, 0.5–12 Hz), forward
and backward filtering with SciPy-compatible odd padding, Fourier resampling to 125 Hz,
central 10-second window, and population z-score normalization. At most 12 valid windows
are selected evenly across a night. Upsampling does not recover missing sensor bandwidth.
Input preparation matches a separate SciPy fixture. This adapted band preprocessing and
window selection still require downstream task validation; no task-specific training was done.

## Storage and operations

- Waveforms: private `noBackupFilesDir`, separate real/simulated folders, 72-hour rolling
  pruning during recording, analysis and app startup. If the app does not run, Android
  cannot execute its pruning; old data is removed at the next startup/access.
- Acquisition uses a bounded writer queue; dropped epochs remain gaps. Capture does not wait
  for disk writes. Roughly 36 MB maximum at uninterrupted capture over the retention window.
- Stages: latest 90 nights in existing isolated preferences. Foundation features: up to 90
  private files, each at most 12 × 512 floats with model/preprocessing/window provenance.
- Existing morning sleep worker analyses a completed night; manual retry is available.
- Turn off and clear removes waveform, stage and feature data. Settings → Delete local data
  also clears it and fitness history. Waveforms/features are not added to Drive or sharing.
- Biosignal models are bundled local files and require no network for inference. They are
  separate from the optional Coach language model, which the user has not created/published.

## Fitness references and limits (passive VO₂ max)

[NHANES Cardiovascular Fitness Procedures Manual](https://wwwn.cdc.gov/nchs/data/nhanes/public/2003/manuals/cv_99-04.pdf),
Appendices E/F: `56.363 + 1.921 × PA-R − 0.381 × age − 0.754 × BMI + 10.987 × maleReference`.
Original [Jackson et al. 1990](https://pubmed.ncbi.nlm.nih.gov/2287267/) reports typical model
error around 5.6 mL/kg/min. App guards (20–70 years, BMI 15–45, height 120–230 cm, weight
30–250 kg) are conservative implementation bounds, not a claim of validation across all
those combinations. The activity rating now comes from measured minutes, so no questionnaire is used; for gender other than male or female the sex term is the midpoint.

The earlier decade-band "fitness age" (HUNT3 reference means) was removed: it only matched
a number computed from typed answers to a population table. BioAge replaces it.
