"""Тесты симуляции. Запуск: python -m unittest tv_simulation.test_simulation -v"""
import json
import unittest

from . import simulate as sim
from .devices import samsung_tv_non_smart, tricolor_receiver
from .facts import FACTS


class TestOutcomes(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.log, cls.outcomes = sim.run_all()

    def by(self, prefix, net):
        return next(o for o in self.outcomes if o.scenario.startswith(prefix) and o.network == net)

    def test_youtube_never_on_tv_or_receiver(self):
        for o in self.outcomes:
            if o.scenario[0] in "12345":
                self.assertFalse(o.youtube_installed, o)

    def test_flashing_tv_and_receiver_fails_with_brick_risk(self):
        for net in sim.NETWORKS:
            for p in ("1.", "3."):
                o = self.by(p, net)
                self.assertEqual(o.status, sim.FAIL)
                self.assertTrue(o.brick_risk)

    def test_apk_rejected_by_receiver_runtime(self):
        o = self.by("2.", "open")
        self.assertEqual(o.status, sim.FAIL)
        self.assertIn("Android", o.reason)

    def test_homebrew_only_theoretical_on_early_firmware(self):
        self.assertEqual(self.by("4. Своё QML-приложение YouTube на приёмник (текущая", "open").status, sim.FAIL)
        self.assertEqual(self.by("4. Своё QML-приложение YouTube на приёмник (ранняя", "open").status,
                         sim.THEORETICAL)

    def test_hdmi_devices_depend_on_network(self):
        expected = {"open": sim.SUCCESS, "throttled": sim.DEGRADED, "blocked_dns": sim.INSTALLED_NO_PLAYBACK}
        for net, status in expected.items():
            for p in ("6.", "7."):
                o = self.by(p, net)
                self.assertTrue(o.youtube_installed)
                self.assertEqual(o.status, status)

    def test_every_fact_referenced_exists_and_has_source(self):
        for r in self.log.records:
            for f in r["facts"]:
                self.assertTrue(FACTS[f].source.startswith("https://"))

    def test_deterministic(self):
        log2, out2 = sim.run_all()
        self.assertEqual(self.log.records, log2.records)
        self.assertEqual(self.outcomes, out2)

    def test_report_written(self):
        path = sim.write_report(self.log, self.outcomes)
        text = path.read_text(encoding="utf-8")
        self.assertIn("## Вывод", text)
        lines = (sim.LOG_DIR / "run.jsonl").read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(lines), len(self.log.records))
        json.loads(lines[0])


class TestModelIsNotRigged(unittest.TestCase):
    """Контроль: если поменять свойства устройства, модель должна дать другой ответ.
    Значит, отказ берётся из свойств устройств, а не зашит в коде."""

    def test_flash_succeeds_on_hypothetical_open_tv(self):
        tv = samsung_tv_non_smart()
        tv.accepts_unofficial_firmware = True
        tv.android_port_exists = True
        log = sim.Log()
        self.assertTrue(sim._flash(log, "x", "open", tv))

    def test_flash_still_fails_without_android_port(self):
        tv = samsung_tv_non_smart()
        tv.accepts_unofficial_firmware = True
        self.assertFalse(sim._flash(sim.Log(), "x", "open", tv))

    def test_receiver_with_android_runtime_would_install(self):
        rx = tricolor_receiver()
        rx.app_runtime = "android"
        status, installed, _ = sim._install_and_play(sim.Log(), "x", "open", rx)
        self.assertEqual(status, sim.SUCCESS)
        self.assertTrue(installed)


if __name__ == "__main__":
    unittest.main()
