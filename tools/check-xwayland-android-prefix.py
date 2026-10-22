#!/usr/bin/env python3
"""Reject host-architecture shared libraries in an Android XWayland staging prefix.

This is a safety preflight, not a dependency-completeness check or a build test.
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

EXPECTED_MACHINES = {
    "armeabi-v7a": ("ARM", "ARM"),
    "arm64-v8a": ("AArch64", "AArch64"),
    "x86": ("Intel 80386", "Intel 80386"),
    "x86_64": ("Advanced Micro Devices X86-64", "X86-64"),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", required=True, help="Target-ABI staging prefix")
    parser.add_argument("--abi", required=True, choices=sorted(EXPECTED_MACHINES))
    parser.add_argument("--readelf", help="readelf/llvm-readelf executable (auto-detected if omitted)")
    args = parser.parse_args()

    prefix = Path(args.prefix).expanduser().resolve()
    libdir = prefix / "lib"
    if not prefix.is_dir():
        parser.error(f"staging prefix does not exist: {prefix}")
    if not libdir.is_dir():
        parser.error(f"staging prefix has no lib directory: {libdir}")

    readelf = args.readelf or shutil.which("llvm-readelf") or shutil.which("readelf")
    if not readelf:
        parser.error("readelf or llvm-readelf is required")
    candidates = sorted(
        path for path in libdir.rglob("*")
        if path.is_file() and (".so" == path.suffix or ".so." in path.name)
    )
    if not candidates:
        parser.error(f"no shared libraries (*.so or *.so.*) found under {libdir}")

    expected = EXPECTED_MACHINES[args.abi]
    failures: list[str] = []
    checked = 0
    for library in candidates:
        result = subprocess.run(
            [readelf, "-h", str(library)],
            text=True, capture_output=True, check=False,
        )
        if result.returncode != 0:
            # Ignore linker scripts or non-ELF files named .so; report only if
            # the file looks like an ELF binary but cannot be inspected.
            try:
                magic = library.open("rb").read(4)
            except OSError:
                magic = b""
            if magic == b"\\x7fELF":
                failures.append(f"{library}: readelf failed: {result.stderr.strip()}")
            continue
        checked += 1
        header = result.stdout
        machine_line = next((line.strip() for line in header.splitlines()
                             if line.strip().startswith("Machine:")), "Machine: <unknown>")
        machine = machine_line.split(":", 1)[-1].strip()
        if not any(token.lower() in machine.lower() for token in expected):
            failures.append(f"{library}: unexpected ELF machine '{machine}' for ABI {args.abi}")

    if checked == 0:
        print(f"ERROR: no ELF shared libraries could be validated under {libdir}", file=sys.stderr)
        return 1
    if failures:
        print(f"ERROR: {len(failures)} target-prefix architecture problem(s):", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    print(f"Validated {checked} ELF shared libraries for ABI={args.abi} under {libdir}.")
    print("NOTE: this checks ELF machine types only; it does not prove dependency completeness or linkability.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
