"""Export the pinned MIT wav2sleep checkpoint; fail on any incompatible weights.

Run from repository root with build/model-tools/Scripts/python.exe.
Only PPG is exposed. All original model operations/weights are retained.
"""
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent / 'vendor'))
import numpy as np
import torch
import onnxruntime as ort
from wav2sleep.models.wav2sleep import Wav2Sleep, SignalEncoders, MultiModalAttentionEmbedder, SequenceCNN

torch.set_num_threads(2)
torch.backends.mha.set_fastpath_enabled(False)
torch.manual_seed(42)
weights = Path('build/model-weights/wav2sleep.pth')
assert hashlib.sha256(weights.read_bytes()).hexdigest() == 'ea6fb4410315cf6cce406fe1ffd44cba83e8dc69be6a69101aff62cc2cbee0bc'
model = Wav2Sleep(
    SignalEncoders(dict(ABD='ABD', ECG='ECG', PPG='PPG', THX='THX'), feature_dim=128,
                   activation='gelu', causal=False, chunk_causal=False, initial_channels=16,
                   max_channels=128, norm='instance', output_norm=False, use_residual=True),
    MultiModalAttentionEmbedder(feature_dim=128, activation='gelu', dim_ff=512,
                               dropout=0.1, layers=2, nhead=8),
    SequenceCNN(feature_dim=128, activation='gelu', causal=False, dropout=0.1,
                kernel_size=5, norm='layer', num_dilations=5, num_layers=2), 4)
model.load_state_dict(torch.load(weights, map_location='cpu', weights_only=True), strict=True)
model.eval()

class PpgOnly(torch.nn.Module):
    def __init__(self, inner):
        super().__init__()
        self.inner = inner
    def forward(self, ppg):
        return self.inner({'PPG': ppg})

wrapper = PpgOnly(model).eval()
dest = Path('app/src/main/assets/biosignal')
dest.mkdir(parents=True, exist_ok=True)
out = dest / 'wav2sleep_ppg.onnx'
with torch.no_grad():
    torch.onnx.export(wrapper, torch.randn(1, 16 * 1024), str(out), opset_version=17,
                      input_names=['ppg'], output_names=['logits'], dynamo=False,
                      dynamic_axes={'ppg': {1: 'samples'}, 'logits': {1: 'epochs'}})
session = ort.InferenceSession(str(out), providers=['CPUExecutionProvider'])
checks = []
for epochs in [16, 60, 240, 960]:
    x = torch.randn(1, epochs * 1024)
    with torch.no_grad():
        expected = wrapper(x).numpy()
    actual = session.run(None, {'ppg': x.numpy()})[0]
    np.testing.assert_allclose(actual, expected, rtol=0.002, atol=0.0002)
    checks.append({'epochs': epochs, 'max_absolute_error': float(np.max(np.abs(actual - expected)))})
    print(checks[-1], flush=True)
manifest = dict(name='wav2sleep PPG', sha256=hashlib.sha256(out.read_bytes()).hexdigest(),
                bytes=out.stat().st_size, labels=['Wake', 'Light', 'Deep', 'REM'],
                samples_per_epoch=1024, epoch_seconds=30, checks=checks,
                checkpoint_revision='8b6e46ab3ef6e3945282b81f631933c59db0b205',
                source_revision='278e30463c8149c4e6899b8784da492fec695bd9',
                torch_version=torch.__version__, onnxruntime_version=ort.__version__)
(dest / 'wav2sleep.json').write_text(json.dumps(manifest, indent=2))
(dest / 'wav2sleep-LICENSE.txt').write_bytes((Path(__file__).parent / 'vendor/wav2sleep/LICENSE').read_bytes())
print(json.dumps(manifest, indent=2))
