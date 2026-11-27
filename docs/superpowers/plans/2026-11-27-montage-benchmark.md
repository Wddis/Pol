# Montage Benchmark Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Measure whether the real montage pipeline can meet a ten-minute delivery budget.

**Architecture:** Separate isolated candidate trials from end-to-end runs. Use an append-only journal for the worker pool and preserve QA failures as failures.

**Tech stack:** Python, FFmpeg, existing montage CLI, isolated ASR and editing tools.

**Spec:** `pipeline/STYLE_SPEC.md`, `pipeline/MONTAGE.md`, `pipeline/PITFALLS.md`, `pipeline/MONTAGE_RETROSPECTIVE.txt`.

- [ ] Compare pinned speech engines on the same raw VOD and reference audio. Score against a manual transcript and censorship intervals, then measure forced alignment near confirmed words.
- [ ] Compare banner tracking on manually reviewed transition frames. Check missing and undersized masks and report speed separately from coverage.
- [ ] Compare exact smart cuts and full encoding against decoded frame boundaries. Probe hardware by actually encoding a sample. Measure downloader time separately.
- [ ] Dispatch main and Shorts tasks through the same journal. Verify unique task IDs, timeouts, stage records, failed QA and two-version parity. Test workloads of 15, 20 and 40 minutes from already downloaded footage.
- [ ] Promote only measured improvements that pass QA. Report incomplete measurements and distinguish throughput projections from actual wall times.
