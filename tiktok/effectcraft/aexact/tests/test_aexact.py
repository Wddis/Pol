"""Тесты aexact без сети: «хост» имитируется функциями numpy.  python3 -m unittest aexact.tests.test_aexact"""
import json
import os
import sys
import tempfile
import unittest

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
from aexact import charts, compile as C, engine  # noqa: E402


def grade(x):   # точечное преобразование с изломами, как у настоящего ЦК
    y = np.stack([x[..., 0] ** 0.8 * 0.92 + 0.04 * x[..., 2], np.clip(1.25 * x[..., 1] - 0.1, 0, 1),
                  0.55 * x[..., 2] + 0.45 * x[..., 0] * x[..., 1]], -1)
    return np.clip(y, 0, 1)


def sharpen(x):
    return np.clip(x + 0.8 * (x - engine.gauss(x, 2.0)), 0, 1)


def fake_capture(folder, effects, n):
    """папка съёмки в том же формате, что дают AEXACT_Capture.jsx и capture_ec.py"""
    rng = np.random.default_rng(3)
    yy, xx = np.mgrid[0:96, 0:128]
    for name, seed in (("fit", 1), ("test", 2)):
        base = np.stack([np.sin(xx / (7.0 + seed)) * 0.4 + 0.5, np.cos(yy / (5.0 + seed)) * 0.4 + 0.5, (xx + yy) / 224.0], -1)
        img = np.clip(base + rng.normal(0, 0.05, base.shape), 0, 1)
        Image.fromarray(np.round(img * 255).astype(np.uint8)).save(os.path.join(folder, name + ".png"))
    names = charts.write(os.path.join(folder, "charts"), os.path.join(folder, "fit.png"), os.path.join(folder, "test.png"), n)
    for ch in names:
        x = C.load(os.path.join(folder, "charts", ch + ".png"))
        full = x
        for i, f in enumerate(effects, 1):
            Image.fromarray(np.round(f(x) * 255).astype(np.uint8)).save(os.path.join(folder, f"{ch}__fx{i:02d}.png"))
            full = np.round(f(full) * 255) / 255.0          # хост округляет после каждого эффекта (8 бит)
        Image.fromarray(np.round(full * 255).astype(np.uint8)).save(os.path.join(folder, f"{ch}__full.png"))
    with open(os.path.join(folder, "manifest.json"), "w") as fh:
        json.dump({"look": "t", "host": "fake", "charts": names,
                   "effects": [{"index": i, "name": f.__name__} for i, f in enumerate(effects, 1)]}, fh)


class Exact(unittest.TestCase):
    def run_case(self, effects, n):
        d = tempfile.mkdtemp(prefix="aexact_t_")
        os.makedirs(os.path.join(d, "cap"))
        fake_capture(os.path.join(d, "cap"), effects, n)
        return C.compile_capture(os.path.join(d, "cap"), os.path.join(d, "pkg")), os.path.join(d, "pkg")

    def test_full_table_makes_colour_grade_bit_identical(self):
        meta, _ = self.run_case([grade], 256)
        self.assertEqual(meta["verdict"], "ИДЕНТИЧНО")
        self.assertEqual(meta["verify"]["max"], 0.0)
        self.assertEqual([o["type"] for o in meta["ops"]], ["lut"])

    def test_interpolated_table_is_close_but_not_called_identical_unless_measured(self):
        meta, _ = self.run_case([grade], 64)
        self.assertLessEqual(meta["verify"]["p99"], 2)
        self.assertEqual(meta["verdict"] == "ИДЕНТИЧНО", meta["verify"]["max"] <= 1)

    def test_spatial_effect_is_detected_and_fitted(self):
        meta, _ = self.run_case([sharpen], 64)
        self.assertEqual(meta["ops"][0]["type"], "linear")
        self.assertLessEqual(meta["verify"]["mean"], 1.0)
        self.assertLess(meta["verify"]["mean"], meta["verify"]["baseline"]["mean"] / 3)

    def test_mixed_stack_keeps_order_and_reports_error(self):
        meta, pkg = self.run_case([grade, sharpen], 64)
        self.assertEqual([o["type"] for o in meta["ops"]], ["lut", "linear"])
        self.assertIn(meta["verdict"], ("ИДЕНТИЧНО", "БЛИЗКО", "ПРИБЛИЖЕНИЕ"))
        img = np.asarray(Image.open(os.path.join(os.path.dirname(pkg), "cap", "charts", "test.png")))
        a, b = engine.Package(pkg).apply(img), engine.Package(pkg).apply(img)
        self.assertTrue(np.array_equal(a, b))                # один вход -> один выход

    def test_identity_table_changes_nothing(self):
        n = 64
        ident = charts.hald(n).reshape(n, n, n, 3) / 255.0
        img = np.random.default_rng(5).integers(0, 256, (40, 40, 3), dtype=np.uint8)
        self.assertTrue(np.array_equal(np.round(engine.apply_lut(img / 255.0, ident) * 255).astype(np.uint8), img))


if __name__ == "__main__":
    unittest.main()
