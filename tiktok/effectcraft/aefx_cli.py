#!/usr/bin/env python3
"""Обёртка: python3 tiktok/effectcraft/aefx_cli.py <inspect|convert|verify> …  (см. aefx/README.md)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aefx.__main__ import main  # noqa: E402

main()
