#!/usr/bin/env python3
"""Audit a staged Android XWayland dependency prefix before cross-compilation.

This catches missing pkg-config modules and metadata that still points at a
host/Termux installation instead of the selected staging prefix. It does not
build dependencies or prove that their ABI/link interfaces are compatible.
"""
from __future__ import annotations

import argparse
import os
import shlex
import shutil
import subprocess
import sys
from pathlib import Path

REQUIRED_MODULES = (
    "libdrm",
    "epoxy",
    "pciaccess",
    "pixman-1",
    "wayland-client",
    "wayland-protocols",
    "x11",
    "xau",
    "xcvt",
    "xfont2",
    "xinerama",
    "xkbfile",
    "xshmfence",
    "libcrypto",
    "libssl",
)


def under(path: Path, roots: tuple[Path, ...]) -> bool:
    for root in roots:
        try:
            path.resolve().relative_to(root.resolve())
            return True
        except (OSError, ValueError):
            continue
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", required=True, help="Target-ABI staging prefix")
    parser.add_argument("--abi", required=True,
                        choices=("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
    parser.add_argument("--sysroot", help="Optional Android NDK sysroot allowed in compiler flags")
    parser.add_argument("--pkg-config", help="pkg-config executable (auto-detected by default)")
    args = parser.parse_args()

    prefix = Path(args.prefix).expanduser().resolve()
    libdir = prefix / "lib"
    if not prefix.is_dir() or not libdir.is_dir():
        parser.error(f"expected staging prefix with lib/ directory: {prefix}")

    pkg_config = args.pkg_config or shutil.which("pkg-config")
    if not pkg_config:
        parser.error("pkg-config is required")
    pc_dirs = [p for p in (libdir / "pkgconfig", prefix / "share" / "pkgconfig") if p.is_dir()]
    if not pc_dirs:
        parser.error(f"no .pc metadata directories found under {prefix}")

    allowed_roots = (prefix,)
    if args.sysroot:
        sysroot = Path(args.sysroot).expanduser().resolve()
        if not sysroot.is_dir():
            parser.error(f"NDK sysroot does not exist: {sysroot}")
        allowed_roots += (sysroot,)

    env = dict(os.environ)
    env.pop("PKG_CONFIG_PATH", None)
    env.pop("PKG_CONFIG_SYSROOT_DIR", None)
    env["PKG_CONFIG_LIBDIR"] = os.pathsep.join(map(str, pc_dirs))

    failures: list[str] = []
    checked = 0
    for module in REQUIRED_MODULES:
        exists = subprocess.run(
            [pkg_config, "--exists", module], env=env,
            text=True, capture_output=True, check=False,
        )
        if exists.returncode != 0:
            failures.append(f"required pkg-config module missing: {module}")
            continue

        checked += 1
        reported_prefix = subprocess.run(
            [pkg_config, "--variable=prefix", module], env=env,
            text=True, capture_output=True, check=False,
        ).stdout.strip()
        if reported_prefix:
            module_prefix = Path(reported_prefix)
            if module_prefix.is_absolute() and not under(module_prefix, allowed_roots):
                failures.append(
                    f"{module}: pkg-config prefix escapes staging/NDK roots: {reported_prefix}"
                )

        flags = subprocess.run(
            [pkg_config, "--cflags", "--libs", module], env=env,
            text=True, capture_output=True, check=False,
        )
        if flags.returncode != 0:
            failures.append(f"{module}: pkg-config could not resolve compiler/linker flags")
            continue
        try:
            tokens = shlex.split(flags.stdout)
        except ValueError as exc:
            failures.append(f"{module}: malformed pkg-config flags: {exc}")
            continue
        for token in tokens:
            path_value = None
            if token.startswith(("-I", "-L")) and len(token) > 2:
                path_value = token[2:]
            elif token.startswith("-isystem") and len(token) > 8:
                path_value = token[8:]
            if path_value and Path(path_value).is_absolute():
                if not under(Path(path_value), allowed_roots):
                    failures.append(f"{module}: compiler/linker flag escapes target roots: {token}")

    if failures:
        print(f"ERROR: {len(failures)} staging-prefix audit problem(s):", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    print(f"Validated {checked} required pkg-config modules for ABI={args.abi}.")
    print(f"Staging prefix: {prefix}")
    print("NOTE: metadata path checks are not proof of dependency ABI or linkability.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
