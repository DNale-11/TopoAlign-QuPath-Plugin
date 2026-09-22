"""Integration tests exercise the real TopoAlign feature/matching/warp pipeline."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
import tifffile
from skimage.draw import ellipse

ROOT = Path(__file__).resolve().parents[1]
SOURCE = os.environ.get("TOPOALIGN_SOURCE_ROOT")
if SOURCE:
    sys.path.insert(0, SOURCE)
spec = importlib.util.spec_from_file_location("bridge", ROOT / "src/main/resources/python/topoalign_bridge.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="topoalign test 空格 ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fixed = np.zeros((256, 256), dtype=np.uint16)
        rng = np.random.default_rng(71)
        # Varied cell shapes and irregular positions make identity unambiguous.
        for label, (y, x) in enumerate([(y, x) for y in (35, 78, 124, 173, 215) for x in (30, 75, 121, 169, 214)], 1):
            rr, cc = ellipse(y + rng.integers(-8, 8), x + rng.integers(-8, 8),
                             rng.integers(4, 10), rng.integers(4, 10), rotation=rng.uniform(-1, 1), shape=self.fixed.shape)
            self.fixed[rr, cc] = label
        self.moving = np.zeros_like(self.fixed)
        self.moving[7:, 11:] = self.fixed[:-7, :-11]
        tifffile.imwrite(self.root / "fixed.tif", self.fixed)
        tifffile.imwrite(self.root / "moving.tif", self.moving)
        self.request = dict(mode="mask", fixed=str(self.root / "fixed.tif"), moving=str(self.root / "moving.tif"),
                            output_dir=str(self.root / "results"), method="rigid", use_ransac=False)

    def test_real_matching_translation_and_outputs(self):
        result = bridge.run(self.request)
        self.assertEqual(result["status"], "completed")
        np.testing.assert_allclose(result["diagnostics"]["transform_matrix"], [[1, 0, -11], [0, 1, -7], [0, 0, 1]], atol=0.05)
        self.assertGreaterEqual(result["diagnostics"]["match_count"], 20)
        self.assertLess(result["diagnostics"]["residual_mean_px"], 0.05)
        registered = tifffile.imread(result["artifacts"]["registered_moving"])
        np.testing.assert_array_equal(registered, self.fixed)
        for path in result["artifacts"].values():
            self.assertTrue(Path(path).is_file(), path)
        with self.assertRaises(FileExistsError):
            bridge.run(self.request)

    def test_image_pipeline_and_full_resolution_coordinates(self):
        # Segmentation itself belongs to Cellpose; supply known labels to test image warping and adapter scaling.
        self.request.update(mode="image", fixed_downsample=4, moving_downsample=2)
        def segment(path, output, **kwargs):
            return (self.fixed if "fixed" in str(path) else self.moving), None
        with patch("cell_registration.service.segment_image", side_effect=segment):
            result = bridge.run(self.request)
        full = json.loads(Path(result["artifacts"]["full_resolution_transform"]).read_text())
        np.testing.assert_allclose(full["matrix"], [[2, 0, -44], [0, 2, -28], [0, 0, 1]], atol=0.2)
        self.assertEqual(tifffile.imread(result["artifacts"]["registered_moving"]).shape, (256, 256))

    def test_nonrigid_ransac_is_rejected(self):
        self.request.update(method="affine", use_ransac=True)
        with self.assertRaisesRegex(ValueError, "only for rigid"):
            bridge.run(self.request)

    def test_binary_mask_is_rejected(self):
        tifffile.imwrite(self.root / "fixed.tif", (self.fixed > 0).astype(np.uint8))
        with self.assertRaisesRegex(ValueError, "binary mask"):
            bridge.run(self.request)

    def test_failure_manifest_replaces_partial_success(self):
        with patch("cell_registration.service.register", side_effect=RuntimeError("test failure")):
            with self.assertRaisesRegex(RuntimeError, "test failure"):
                bridge.run(self.request)
        self.assertEqual(json.loads((self.root / "results/result.json").read_text())["status"], "failed")

    def test_subprocess_with_unicode_and_spaces(self):
        request_path = self.root / "request.json"
        request_path.write_text(json.dumps(self.request), encoding="utf-8")
        command = [sys.executable, str(ROOT / "src/main/resources/python/topoalign_bridge.py"), "--request", str(request_path)]
        if SOURCE:
            command.extend(["--source-root", SOURCE])
        result = subprocess.run(command, cwd=self.root, capture_output=True, env={**os.environ, "MPLBACKEND": "Agg", "PYTHONIOENCODING": "utf-8"}, timeout=120)
        self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", errors="replace"))
        self.assertEqual(json.loads((self.root / "results/result.json").read_text())["status"], "completed")


if __name__ == "__main__":
    unittest.main()
