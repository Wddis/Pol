"""Run local montage commands in parallel, with a single append-only journal."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import threading
import time


def run_batch(tasks, folder, workers):
    if not tasks or workers < 1:
        raise ValueError("Provide at least one task and one worker.")
    folder.mkdir(parents=True, exist_ok=True)
    lock = threading.Lock()
    ids = [task["id"] for task in tasks]
    if len(set(ids)) != len(ids) or not all(re.fullmatch(r"[a-zA-Z0-9_-]+", name) for name in ids):
        raise ValueError("Task IDs must be unique ASCII names.")

    def append(name, event, **details):
        row = dict(utc=datetime.now(timezone.utc).isoformat(), task=name, event=event, **details)
        with lock, (folder / ".journal.lock").open("a") as journal_lock:
            fcntl.flock(journal_lock, fcntl.LOCK_EX)
            with (folder / "journal.jsonl").open("a") as out:
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
            with (folder / "journal.txt").open("a") as out:
                out.write(f"{row['utc']}  {name}  {event}  {json.dumps(details, ensure_ascii=False)}\n")

    def run(task):
        name = task["id"]
        start = time.monotonic()
        append(name, "started")
        terminal_hold = False
        try:
            with (folder / f"{name}.stderr.log").open("a") as err, (folder / f"{name}.stdout.log").open("a") as output:
                with subprocess.Popen(task["argv"], cwd=task.get("cwd"), stdout=subprocess.PIPE,
                                      stderr=err, text=True, start_new_session=True) as process:
                    def stop():
                        try:
                            os.killpg(process.pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass

                    timer = threading.Timer(task.get("timeout_seconds", 7200), stop)
                    timer.start()
                    try:
                        for line in process.stdout:
                            output.write(line)
                            output.flush()
                            if line.startswith("FAIL "):
                                append(name, "stage", record={"stage": "QA", "status": "failed", "reason": line.strip()})
                                terminal_hold = True
                            try:
                                row = json.loads(line)
                            except ValueError:
                                continue
                            if isinstance(row, dict) and "stage" in row:
                                append(name, "stage", record=row)
                                terminal_hold |= row.get("status") in ("failed", "held")
                        code = process.wait()
                    finally:
                        timer.cancel()
        except (OSError, ValueError) as exc:
            append(name, "failed", error=str(exc), wall_seconds=round(time.monotonic() - start, 3))
            return False
        ok = code == 0 and not terminal_hold
        append(name, "finished" if ok else "failed", exit_code=code,
               wall_seconds=round(time.monotonic() - start, 3))
        return ok

    for task in tasks:
        append(task["id"], "queued", kind=task.get("kind", "montage"))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        results = list(pool.map(run, tasks))
    return all(results)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("tasks", type=Path, help="JSON list of tasks, each with id and argv")
    parser.add_argument("folder", type=Path)
    parser.add_argument("--workers", type=int, default=2)
    args = parser.parse_args()
    raise SystemExit(0 if run_batch(json.loads(args.tasks.read_text()), args.folder, args.workers) else 1)
