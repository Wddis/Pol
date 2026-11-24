#!/usr/bin/env bash
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
src="$repo/app/src/main/java/com/example/vpnapp"
test="$repo/app/src/test/java/com/example/vpnapp"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/classes"
printf '%s\n' 'package androidx.annotation; public @interface Nullable {}' > "$tmp/Nullable.java"
printf '%s\n' 'package com.example.vpnapp; final class DiagLog { static void add(String text) {} }' \
    > "$tmp/DiagLog.java"
javac -encoding UTF-8 -d "$tmp/classes" \
    "$tmp/Nullable.java" "$tmp/DiagLog.java" "$src/NetProbe.java" \
    "$src/BypassSearch.java" "$src/BypassProfile.java" "$src/PsiphonHealth.java" "$src/PsiphonRoute.java" \
    "$test/PsiphonHealthTest.java"
java -cp "$tmp/classes" com.example.vpnapp.PsiphonHealthTest "$@"
