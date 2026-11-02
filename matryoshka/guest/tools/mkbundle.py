#!/usr/bin/env python3
"""
Writes manifest.json for a built guest image directory and optionally packs an importable
.zip bundle and/or a catalog entry for hosting the files on GitHub Releases.

  mkbundle.py <image-dir> --id ID --name NAME [--zip out.zip] [--base-url URL] [--catalog catalog.json]
"""
import argparse
import hashlib
import json
import pathlib
import zipfile

ENGINE_FILES = {
    "HYPERVISOR": ["kernel", "ramdisk.img", "boot.img", "system.img", "vendor.img"],
    "EMULATOR": ["kernel", "ramdisk.img", "boot.img", "system.img", "vendor.img"],
    "CONTAINER": ["rootfs.tar.gz"],
}
CMDLINE = "loglevel=4 androidboot.selinux=permissive"


def sha256(p: pathlib.Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dir")
    ap.add_argument("--id", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--zip")
    ap.add_argument("--base-url", help="URL prefix where the files will be hosted")
    ap.add_argument("--catalog", help="catalog.json to create/update")
    a = ap.parse_args()

    d = pathlib.Path(a.dir)
    present = {p.name for p in d.iterdir() if p.is_file()}
    engines = [e for e, files in ENGINE_FILES.items() if all(f in present for f in files)]
    names = sorted({f for e in engines for f in ENGINE_FILES[e]})
    files = []
    for n in names:
        p = d / n
        entry = {"name": n, "sha256": sha256(p), "size": p.stat().st_size}
        if a.base_url:
            entry["url"] = a.base_url.rstrip("/") + "/" + n
        files.append(entry)
    manifest = {
        "id": a.id, "name": a.name, "androidVersion": "11", "abi": "arm64-v8a",
        "engines": engines, "files": files, "kernelCmdline": CMDLINE,
        "description": "Compact AOSP 11 build for Matryoshka VM (microG-ready, Magisk-patchable).",
        "licenseNotice": "AOSP (Apache-2.0) and Linux kernel (GPL-2.0). Corresponding source: see guest/README.md.",
    }
    (d / "manifest.json").write_text(json.dumps(manifest, indent=2))
    print(f"manifest.json: engines={engines}, {len(files)} files")

    if a.zip:
        with zipfile.ZipFile(a.zip, "w", zipfile.ZIP_STORED, allowZip64=True) as z:
            z.write(d / "manifest.json", "manifest.json")
            for n in names:
                z.write(d / n, n)
        print(f"bundle: {a.zip}")

    if a.catalog:
        cp = pathlib.Path(a.catalog)
        cat = json.loads(cp.read_text()) if cp.exists() else {"version": 1, "images": []}
        cat["images"] = [i for i in cat["images"] if i["id"] != a.id] + [manifest]
        cp.write_text(json.dumps(cat, indent=2))
        print(f"catalog: {cp}")


if __name__ == "__main__":
    main()
