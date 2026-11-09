"""Тесты aefx. Запуск из tiktok/effectcraft:  python3 -m unittest aefx.tests.test_aefx -v

Сеть не нужна. Проверка на живом сайте — отдельным сценарием aefx/tests/site_check.py.
"""
import glob
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)

import make_ffx as M  # noqa: E402
from aefx import ir as IR  # noqa: E402
from aefx.convert import CAT, convert  # noqa: E402

PRESETS = sorted(glob.glob(os.path.join(HERE, "..", "..", "..", "knowledge", "cc_presets", "ffx", "*.ffx")))


def load_sample():
    with tempfile.NamedTemporaryFile(suffix=".ffx", delete=False) as f:
        f.write(M.sample())
    try:
        return IR.load(f.name)
    finally:
        os.unlink(f.name)


def entries(c, effect_index):
    return {x["key"]: x for x in c.ledger.effects[effect_index]["entries"]}


def steps_for(c, path):
    return [s for s in c.steps if s["p"].get("path") == path]


class ReadWithoutLoss(unittest.TestCase):
    """Слой 1: из файла читается всё."""

    def setUp(self):
        self.ir = load_sample()

    def test_effects_and_flags(self):
        e = self.ir["effects"]
        self.assertEqual([x["match"] for x in e], ["ADBE Tile", "ADBE Glo2", "ADBE Turbulent Displace", "S_Glow", "ACME Wobble"])
        self.assertFalse(e[2]["enabled"])
        self.assertEqual(e[1]["builtin"]["effect_opacity"], 60.0)

    def test_spatial_keys_keep_tangents_and_ease(self):
        p = self.ir["effects"][0]["params"][1]
        self.assertEqual(p["anim"]["layout"], "spatial")
        k = p["anim"]["keys"][1]
        self.assertEqual(k["value"], [0.5, 0.75])            # оба измерения, а не только первое
        self.assertEqual(k["tan_in"], [-0.05, 0.02])
        self.assertEqual(k["tan_out"], [0.06, -0.01])
        self.assertAlmostEqual(k["ease_in"][0]["speed"], 0.2)
        self.assertAlmostEqual(k["ease_out"][0]["influence"], 0.7)
        self.assertEqual((p["anim"]["keys"][2]["interp_in"], p["anim"]["keys"][2]["interp_out"]), (2, 3))

    def test_color_keys_have_four_channels(self):
        p = next(p for p in self.ir["effects"][1]["params"] if p["name"] == "Color A")
        self.assertEqual(p["anim"]["layout"], "color")
        self.assertEqual(p["anim"]["keys"][0]["value"], [255, 255, 0, 0])

    def test_definition_expression_arb(self):
        e = self.ir["effects"]
        phase = next(p for p in e[0]["params"] if p["name"] == "Phase")
        self.assertEqual(phase["expr"], "time * 90")
        op = next(p for p in e[1]["params"] if p["name"] == "Glow Operation")
        self.assertEqual(op["options"][4], "(-")
        self.assertEqual(op["default"], [2.0])
        curve = next(p for p in e[4]["params"] if p["name"] == "Curve")
        self.assertEqual(curve["arb"], "01020304")
        w = next(p for p in e[0]["params"] if p["name"] == "Tile Width")
        self.assertTrue(w["anim"]["keys"][2]["continuous"])

    def test_unknown_key_layout_is_flagged_not_dropped(self):
        meta = {"dims": 1, "is_color": False, "no_value": False, "spatial": False, "time_scale": 30720}
        node = IR.parse(M.keys("multi", 1, [{"t": 0, "v": [1.0]}]))[0]
        node["children"][0]["data"] = node["children"][0]["data"][:18] + b"\x00\x21" + node["children"][0]["data"][20:]
        node["children"][1]["data"] = bytes(33)
        r = IR.parse_keys(node, meta)
        self.assertEqual(r["layout"], "unknown")
        self.assertEqual(r["keys"][0]["unparsed"], "key")
        self.assertEqual(len(r["keys"][0]["raw"]), 66)        # сырые байты сохранены


class Transfer(unittest.TestCase):
    """Слой 2: перенос и учёт."""

    def setUp(self):
        self.ir = load_sample()
        self.c = convert(self.ir, t0=1.0, comp=(1280, 720))

    def test_every_parameter_is_accounted_exactly_once(self):
        for ir in [self.ir] + [IR.load(f) for f in PRESETS]:
            c = convert(ir)
            for e, led in zip(ir["effects"], c.ledger.effects):
                want = sorted(p["match"] for p in e["params"]
                              if p["kind"] not in IR.STRUCTURAL and not (p.get("index") == 0 and p["kind"] == "layer"))
                got = sorted(x["key"] for x in led["entries"] if x["key"] in set(want))
                self.assertEqual(want, got, f"{ir['source']} / {e['name']}")

    def test_all_dimensions_and_tangents_emitted(self):
        ks = [s for s in steps_for(self.c, "effects/#1/tileCenter") if s["c"] == "prop.addKey"]
        self.assertEqual([s["p"]["value"] for s in ks], [{"$frac": [0.25, 0.25]}, {"$frac": [0.5, 0.75]}, {"$frac": [0.75, 0.25]}])
        self.assertEqual([s["p"]["time"] for s in ks], [1.0, 1.5, 2.0])     # первый ключ встал на t0
        tan = [s for s in steps_for(self.c, "effects/#1/tileCenter") if s["c"] == "keys.setSpatialTangents"]
        self.assertEqual(tan[0]["p"]["in"], {"$fracVec": [-0.05, 0.02]})
        self.assertIn({"in": "bezier", "out": "hold"}, [s["p"] for s in self.c.steps if s["c"] == "keys.interpolation"])

    def test_fixed_layer_size_resolves_points_offline(self):
        c = convert(load_sample(), layer_size=(640, 360))
        ks = [s for s in steps_for(c, "effects/#1/tileCenter") if s["c"] == "prop.addKey"]
        self.assertEqual(ks[1]["p"]["value"], [320.0, 270.0])

    def test_popup_by_label_not_by_index(self):
        # AE: «Overlay» — пункт №6 (перед ним разделитель), в EffectCraft — индекс 4
        self.assertEqual(steps_for(self.c, "effects/#2/glowOperation")[0]["p"]["value"], CAT["Glow"]["params"][5]["options"].index("Overlay"))

    def test_color_argb_to_rgba_with_per_channel_ease(self):
        ks = [s for s in steps_for(self.c, "effects/#2/colorA") if s["c"] == "prop.addKey"]
        self.assertEqual(ks[0]["p"]["value"], [1.0, 0.0, 0.0, 1.0])
        self.assertEqual(ks[1]["p"]["value"], [0.0, 0.0, 1.0, 1.0])
        dims = sorted(s["p"]["dim"] for s in steps_for(self.c, "effects/#2/colorA") if s["c"] == "keys.setEase" and s["p"]["side"] == "in")
        self.assertEqual(dims, [0, 1, 2, 3])

    def test_out_of_range_and_effect_opacity_are_reported(self):
        e = entries(self.c, 1)
        self.assertEqual(e["ADBE Glo2-0003"]["status"], "CLAMPED")
        self.assertEqual(e["Compositing Options › Effect Opacity"]["status"], "LOSS")
        self.assertFalse(self.c.ledger.lossless())

    def test_calibration_keeps_keys_and_follows_size(self):
        e = entries(self.c, 2)
        self.assertEqual(e["ADBE Turbulent Displace-0002"]["status"], "CALIBRATED")
        ex = [s for s in steps_for(self.c, "effects/#3/amount") if s["c"] == "prop.setExpression"][0]["p"]["expression"]
        self.assertIn('effect("Turbulent Displace")("Size")', ex)
        self.assertIn({"layer": "$cur", "effect": 3, "value": False}, [s["p"] for s in self.c.steps if s["c"] == "effect.toggle"])

    def test_third_party_parameters_live_as_controls(self):
        e = entries(self.c, 3)
        self.assertEqual(e["S_Glow-0052"]["status"], "DERIVED")
        self.assertEqual(e["S_Glow-0061"]["status"], "STORED")       # сохранён, но эмуляция его не использует
        names = [s["p"]["name"] for s in self.c.steps if s["c"] == "prop.renameGroup"]
        self.assertIn("S_Glow › Threshold", names)
        i = names.index("S_Glow › Threshold")
        fx = [s for s in self.c.steps if s["c"] == "prop.renameGroup"][i]["p"]["prop"]
        n = int(fx.replace("$aefx", ""))
        keys = [s for s in steps_for(self.c, f"effects/#{n}/slider") if s["c"] == "prop.addKey"]
        self.assertEqual([k["p"]["value"] for k in keys], [0.2, 0.9])      # ключи 1:1, без запекания
        ex = [s["p"]["expression"] for s in self.c.steps if s["c"] == "prop.setExpression"]
        self.assertIn('clamp(effect("S_Glow › Threshold")("Slider") * 100, 0, 100)', ex)
        self.assertIn("wiggle(2, 10)", ex)                                 # выражение плагина не потеряно

    def test_unknown_plugin_is_never_silently_skipped(self):
        led = self.c.ledger.effects[4]
        self.assertEqual(led["fidelity"], "none")
        e = entries(self.c, 4)
        self.assertEqual(e["ACME Wobble-0001"]["status"], "STORED")
        self.assertEqual(e["ACME Wobble-0003"]["status"], "LOSS")
        self.assertEqual(e["ACME Wobble-0002"]["status"], "DEFAULT")

    def test_expectations_cover_every_written_property(self):
        written = {s["p"]["path"] for s in self.c.steps if s["c"] in ("prop.set", "prop.addKey", "prop.setExpression")}
        self.assertEqual(written, {x["path"] for x in self.c.expect if "name" not in x})


class Recipes(unittest.TestCase):
    def test_every_recipe_uses_all_parameters_it_was_given(self):
        with tempfile.NamedTemporaryFile(suffix=".ffx", delete=False) as f:
            f.write(M.recipes_sample())
        try:
            ir = IR.load(f.name)
        finally:
            os.unlink(f.name)
        c = convert(ir)
        self.assertEqual([e["fidelity"] for e in c.ledger.effects], ["recipe"] * len(ir["effects"]))
        self.assertTrue(c.ledger.lossless(), c.ledger.report())
        self.assertFalse(c.ledger.exact())          # эмуляция честно не называется переносом 1:1
        self.assertFalse(any("ждала параметр" in n for e in c.ledger.effects for n in e["notes"]))
        amp = [s for s in c.steps if s["c"] == "keys.setEase"]
        self.assertTrue(amp)                         # ключи амплитуды тряски перенесены с ease, без запекания


@unittest.skipUnless(PRESETS, "нет пресетов knowledge/cc_presets/ffx")
class RealPresets(unittest.TestCase):
    def test_hue_saturation_values_come_from_channel_range(self):
        ir = IR.load(next(f for f in PRESETS if "Dusky" in f))
        c = convert(ir)
        i = next(i for i, e in enumerate(ir["effects"]) if e["match"] == "ADBE HUE SATURATION")
        e = entries(c, i)
        self.assertEqual(e["ADBE HUE SATURATION-0003"]["status"], "DECODED")
        n = next(int(x["path"].split("#")[1]) for x in c.expect if x.get("name") == "Hue/Saturation")
        self.assertEqual(steps_for(c, f"effects/#{n}/saturation")[0]["p"]["value"], 10)   # в cdat ползунка — 0
        self.assertEqual(steps_for(c, f"effects/#{n}/redsRangeStart")[0]["p"]["value"], -15)

    def test_no_effect_is_dropped(self):
        for f in PRESETS:
            ir = IR.load(f)
            c = convert(ir)
            self.assertEqual(len(c.ledger.effects), len(ir["effects"]))
            for led in c.ledger.effects:
                self.assertTrue(led["entries"], f"{f}: {led['name']} без строк учёта")

    def test_later_instances_share_definitions(self):
        ir = IR.load(next(f for f in PRESETS if "Raspberry" in f))
        looks = [e for e in ir["effects"] if e["match"] == "MB LookSuite3"]
        self.assertGreater(len(looks), 1)
        for e in looks:
            self.assertTrue(any(p["name"] == "Strength" and p["kind"] == "float" for p in e["params"]))


class AepLayer(unittest.TestCase):
    def test_layer_effects_use_shared_definitions(self):
        head, sspc = M.effect("ADBE Tile", "Motion Tile", [
            M.P("ADBE Tile-0000", "layer", "", [0.0]),
            M.P("ADBE Tile-0004", "slider", "Output Width", [250.0], lo=0, hi=1000, default=100)])
        tree = IR.parse(sspc)
        part = next(c for c in tree[0]["children"] if c.get("type") == "parT")
        tree[0]["children"].remove(part)          # в .aep у экземпляра на слое определений нет…
        tdmn = IR.parse(M.tdmn("ADBE Tile"))[0]
        efdf = {"id": "LIST", "type": "EfDf", "children": [tdmn, {"id": "LIST", "type": "sspc", "children": [part]}]}
        shared = IR.shared_definitions([{"id": "LIST", "type": "EfdG", "children": [efdf]}])   # …они в LIST EfdG
        layr = {"id": "LIST", "type": "Layr", "children": [tdmn, tree[0]]}
        effs = IR.layer_effects(layr, shared)
        self.assertEqual(len(effs), 1)
        p = effs[0]["params"][1]
        self.assertEqual((p["name"], p["kind"], p["value"], p["default"]), ("Output Width", "slider", [250.0], [100.0]))
        c = convert({"source": "t.aep", "kind": "aep", "effects": [dict(effs[0], order=0)]})
        self.assertEqual(steps_for(c, "effects/#1/outputWidth")[0]["p"]["value"], 250.0)


if __name__ == "__main__":
    unittest.main()
