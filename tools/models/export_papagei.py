"""Convert published PaPaGei-S embeddings for local research; no clinical task head.

Source architecture is BSD-3-Clause-Clear. Zenodo record 13983110 does not
declare a weights license in its API metadata; resolve distribution rights
before publishing a release containing these weights. This local conversion
does not assert rights beyond those supplied by the original publishers.
"""
import hashlib
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).parent / 'vendor'))
import numpy as np
import torch
import onnxruntime as ort
from papagei.resnet import ResNet1DMoE

torch.set_num_threads(2)
torch.manual_seed(42)
p = Path('build/model-weights/papagei_s.pt')
assert hashlib.md5(p.read_bytes()).hexdigest() == 'a4cdb32392e2a7b25999128af92813b5'
model = ResNet1DMoE(1, 32, 3, 2, 1, 18, 512, n_experts=3)
state = torch.load(p, map_location='cpu', weights_only=True)
state = {k.removeprefix('module.'): v for k, v in state.items()}
model.load_state_dict(state, strict=True)
model.eval()
class Encoder(torch.nn.Module):
    def __init__(self, inner):
        super().__init__()
        self.inner = inner
    def forward(self, ppg):
        return self.inner(ppg)[0]
wrapper = Encoder(model).eval()
# Local debug builds only until the weight distribution license is confirmed.
dest = Path('app/src/debug/assets/biosignal')
dest.mkdir(parents=True, exist_ok=True)
out = dest / 'papagei_s.onnx'
with torch.no_grad():
    torch.onnx.export(wrapper, torch.randn(1,1,1250), str(out), opset_version=17,
                      input_names=['ppg'], output_names=['embedding'], dynamo=False, do_constant_folding=False)
options = ort.SessionOptions()
# Pad+MaxPool fusion incorrectly replaces explicit zero padding with negative infinity.
options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
session = ort.InferenceSession(str(out), options, providers=['CPUExecutionProvider'])
checks = []
fixtures = Path('app/src/androidTest/assets/biosignal'); fixtures.mkdir(parents=True, exist_ok=True)
for case in range(3):
    x = torch.randn(1,1,1250) * (case+1)
    with torch.no_grad(): expected = wrapper(x).numpy()
    actual = session.run(None, {'ppg': x.numpy()})[0]
    np.testing.assert_allclose(actual, expected, rtol=0.002, atol=0.0002)
    checks.append(float(np.max(np.abs(actual-expected))))
    if case == 0:
        x.numpy().astype('>f4').tofile(fixtures/'papagei-input.bin')
        expected.astype('>f4').tofile(fixtures/'papagei-output.bin')
manifest = dict(name='PaPaGei-S encoder', checkpoint_sha256=hashlib.sha256(p.read_bytes()).hexdigest(),
                sha256=hashlib.sha256(out.read_bytes()).hexdigest(), bytes=out.stat().st_size,
                source_revision=Path('tools/models/vendor/papagei/SOURCE.txt').read_text(),
                max_absolute_errors=checks, output_dimensions=512, task_head=None)
(dest/'papagei_s.json').write_text(json.dumps(manifest, indent=2))
(dest/'papagei-LICENSE.txt').write_bytes(Path('tools/models/vendor/papagei/LICENSE').read_bytes())
print(json.dumps(manifest, indent=2))
