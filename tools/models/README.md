# Local model conversion

These scripts run on a workstation, not on a user's phone. They load tensors with
`weights_only=True`, strictly verify checkpoint parameter names, export opset 17 ONNX,
and stop if original-model/output parity fails. They do not train task heads or establish
sensor/clinical accuracy. See `docs/biosignal-models-and-fitness.md` for full provenance.

Tested isolated environment: Python 3.13, torch 2.14.1+cpu, onnx 1.23.1,
onnxruntime 1.30.0, NumPy 2.5.3, SciPy 1.18.1. Android uses ONNX Runtime 1.22.0,
also checked independently by instrumented parity tests. Vendored model architecture
files retain their original license notices; only unused imports were removed for PaPaGei.

Place original published checkpoints at:

- `build/model-weights/wav2sleep.pth`: [pinned Hugging Face checkpoint](https://huggingface.co/joncarter/wav2sleep/resolve/8b6e46ab3ef6e3945282b81f631933c59db0b205/state_dict.pth)
- `build/model-weights/papagei_s.pt`: [Zenodo checkpoint](https://zenodo.org/api/records/13983110/files/papagei_s.pt/content)

Run `python tools/models/export_sleep.py` and `python tools/models/export_papagei.py`
from the repository root using the isolated environment. Both verify publisher/checkpoint
digests before loading. Generated manifests record asset hashes and conversion errors.
Sleep assets go to main; PaPaGei assets go to debug only because checkpoint distribution
terms still need confirmation. Do not move those weights to release assets implicitly.

**PaPaGei runtime requirement:** keep graph optimization disabled. Pad+MaxPool fusion
changed zero-padding semantics and failed parity. The Android runtime enforces this.

Synthetic binary test fixtures are big-endian float32. Sleep and encoder expected outputs
come from the original PyTorch architecture. Foundation preprocessing expected output is
an independent SciPy `cheby2(4,20,[0.5,12],fs=1024/30,btype='bandpass')` + `filtfilt`
with default odd padding, `resample(...,3750)[1250:2500]`, population z-score. They contain
no participant records. Real clinical/model-performance validation still needs real labels.
