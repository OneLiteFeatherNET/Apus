#!/usr/bin/env python3
"""Mirrors the lowres tile levels (tiles/1, tiles/2, tiles/3) of a bucket out of the
spike's MinIO via a throwaway `minio/mc` container, then diffs them byte-for-byte and
pixel-for-pixel against an already-mirrored `reference` bucket.

Usage:
    python3 compare_tiles.py <bucket> <label>   # mirrors <bucket> into mirrors/<label>/
                                                 # and diffs it against mirrors/reference/
    python3 compare_tiles.py --summary <label> [<label> ...]
                                                 # prints the cross-run table used in the
                                                 # report, given labels already mirrored
                                                 # by prior --mirror-only/plain calls.

Requires: docker (for the mc mirror step) and Pillow (`pip install pillow`).
Run `./run-spike.sh` first so the buckets exist.
"""
from __future__ import annotations

import hashlib
import os
import subprocess
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MIRRORS_DIR = os.path.join(SCRIPT_DIR, "mirrors")
NET = "apus-spike-net"
MC_IMAGE = "minio/mc:RELEASE.2025-08-13T08-35-41Z"
S3_USER = "apusspike"
S3_PASS = "apusspikesecret"
LEVELS = ["tiles-1", "tiles-2", "tiles-3"]  # LOD1, LOD2, LOD3 -- the lowres pyramid


def mirror_bucket(bucket: str, label: str) -> None:
    out_dir = os.path.join(MIRRORS_DIR, label)
    os.makedirs(out_dir, exist_ok=True)
    cmds = " && ".join(
        [
            f"mc alias set m http://minio:9000 {S3_USER} {S3_PASS} >/dev/null",
            *[
                f"mc mirror --overwrite --quiet m/{bucket}/demo/overworld/tiles/{lvl} /out/{label}/tiles-{lvl}"
                for lvl in (1, 2, 3)
            ],
            "echo MIRRORED",
        ]
    )
    subprocess.run(
        [
            "docker",
            "run",
            "--rm",
            "--network",
            NET,
            "-v",
            f"{MIRRORS_DIR}:/out",
            "--entrypoint",
            "/bin/sh",
            MC_IMAGE,
            "-c",
            cmds,
        ],
        check=True,
        stdout=subprocess.DEVNULL,
    )


def md5(path: str) -> str:
    return hashlib.md5(open(path, "rb").read()).hexdigest()


def all_reference_files() -> list[str]:
    ref_dir = os.path.join(MIRRORS_DIR, "reference")
    files = []
    for lvl in LEVELS:
        d = os.path.join(ref_dir, lvl)
        if not os.path.isdir(d):
            continue
        for root, _, names in os.walk(d):
            for n in names:
                files.append(os.path.relpath(os.path.join(root, n), ref_dir))
    return sorted(files)


def pixel_diff_pct(ref_path: str, other_path: str) -> tuple[float, int]:
    from PIL import Image
    import numpy as np

    a = np.array(Image.open(ref_path).convert("RGBA"), dtype=np.int16)
    b = np.array(Image.open(other_path).convert("RGBA"), dtype=np.int16)
    if a.shape != b.shape:
        return 100.0, int(a[..., 0].size)
    diff = np.abs(a - b)
    npix = int((diff.max(axis=-1) > 0).sum())
    return 100.0 * npix / diff[..., 0].size, npix


def diff_against_reference(label: str) -> list[str]:
    ref_dir = os.path.join(MIRRORS_DIR, "reference")
    other_dir = os.path.join(MIRRORS_DIR, label)
    differing = []
    for rel in all_reference_files():
        ref_path = os.path.join(ref_dir, rel)
        other_path = os.path.join(other_dir, rel)
        if not os.path.exists(other_path):
            print(f"  MISSING: {rel}")
            differing.append(rel)
            continue
        if md5(ref_path) == md5(other_path):
            continue
        pct, npix = pixel_diff_pct(ref_path, other_path)
        print(f"  DIFFERS: {rel}  ({npix} px, {pct:.2f}%)")
        differing.append(rel)
    return differing


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    if sys.argv[1] == "--summary":
        labels = sys.argv[2:]
        files = set()
        for lbl in labels:
            files.update(diff_against_reference(lbl))
        # already printed per-label above; nothing more to do
        return

    bucket, label = sys.argv[1], sys.argv[2]
    print(f"mirroring {bucket} -> mirrors/{label}/ ...")
    mirror_bucket(bucket, label)
    print(f"diffing mirrors/{label}/ against mirrors/reference/ (lowres only):")
    differing = diff_against_reference(label)
    total = len(all_reference_files())
    print(f"{len(differing)}/{total} lowres tiles differ from the reference render")


if __name__ == "__main__":
    main()
