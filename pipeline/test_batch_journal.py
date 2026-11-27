import json
from pathlib import Path
import sys
import tempfile
import unittest

from batch_journal import run_batch


class JournalTests(unittest.TestCase):
    def test_stage_and_zero_exit_qa_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            tasks = [
                {"id": "main", "argv": [sys.executable, "-c", 'print(\'{"stage":"RENDER","status":"ok"}\')']},
                {"id": "short", "argv": [sys.executable, "-c", 'print("FAIL peak")']},
            ]
            self.assertFalse(run_batch(tasks, path, 2))
            rows = [json.loads(line) for line in (path / "journal.jsonl").read_text().splitlines()]
            self.assertEqual([row["event"] for row in rows[:2]], ["queued", "queued"])
            self.assertEqual({row["task"]: row["event"] for row in rows if row["event"] in ("finished", "failed")},
                             {"main": "finished", "short": "failed"})
            self.assertTrue((path / "journal.txt").exists())

    def test_timeout_kills_command(self):
        with tempfile.TemporaryDirectory() as folder:
            task = {"id": "long", "argv": [sys.executable, "-c", "import time; time.sleep(10)"],
                    "timeout_seconds": 0.1}
            self.assertFalse(run_batch([task], Path(folder), 1))
