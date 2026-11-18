#!/usr/bin/env python3
"""Generate NDK Binder sources from the Java/NDK shared structured AIDL contract."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
BUILD_TOOLS_VERSION = "37.0.0"
PLATFORM = "android-37.0"
MIN_SDK_VERSION = 35


def local_sdk() -> Path | None:
    """Read only sdk.dir; do not require machine-specific paths in source control."""
    properties = ROOT / "local.properties"
    if not properties.is_file():
        return None
    for line in properties.read_text(encoding="utf-8-sig").splitlines():
        match = re.match(r"\s*sdk\.dir\s*[=:]\s*(.*?)\s*$", line)
        if match:
            value = re.sub(r"\\([\\:= ])", r"\1", match.group(1))
            return Path(value).expanduser()
    return None


def resolve_sdk(override: Path | None) -> Path:
    if override is not None:
        return override.expanduser().resolve()
    for name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(name)
        if value:
            return Path(value).expanduser().resolve()
    configured = local_sdk()
    if configured is not None:
        return configured.resolve()
    raise ValueError(
        "Android SDK not found: use --sdk, ANDROID_SDK_ROOT, ANDROID_HOME, "
        "or sdk.dir in local.properties"
    )


def generate(sdk: Path, output: Path) -> None:
    aidl_root = ROOT / "protocol" / "src" / "main" / "aidl"
    inputs = sorted(aidl_root.rglob("*.aidl"))
    if not inputs:
        raise FileNotFoundError(f"No AIDL sources under {aidl_root}")

    executable = "aidl.exe" if os.name == "nt" else "aidl"
    compiler = sdk / "build-tools" / BUILD_TOOLS_VERSION / executable
    platform = sdk / "platforms" / PLATFORM
    helpers = platform / "optional" / "libbinder_ndk_cpp"
    for required in (compiler, helpers):
        if not required.exists():
            raise FileNotFoundError(f"Required Android SDK component is missing: {required}")

    include = output / "include"
    source = output / "src"
    if include.exists():
        shutil.rmtree(include)
    if source.exists():
        shutil.rmtree(source)
    include.mkdir(parents=True, exist_ok=True)
    source.mkdir(parents=True, exist_ok=True)
    command = [
        str(compiler),
        "--lang=ndk",
        "--structured",
        f"--min_sdk_version={MIN_SDK_VERSION}",
        "--omit_invocation",
        f"--include={aidl_root}",
        # ParcelFileDescriptor and IBinder are compiler built-ins. The SDK's
        # Java-only framework.aidl must not be passed to the structured NDK backend.
        f"--header_out={include}",
        f"--out={source}",
        *(str(path) for path in inputs),
    ]
    subprocess.run(command, check=True, cwd=ROOT)
    print(f"Generated NDK bindings for {len(inputs)} AIDL files")
    print(f"Headers: {include}")
    print(f"Sources: {source}")
    print(f"Binder C++ helper headers: {helpers}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, help="Android SDK directory")
    parser.add_argument(
        "--out", "--output", dest="output", type=Path,
        default=ROOT / "build" / "generated" / "aidl",
        help="Output root containing include/ and src/ (default: build/generated/aidl)",
    )
    args = parser.parse_args()
    try:
        generate(resolve_sdk(args.sdk), args.output.expanduser().resolve())
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"AIDL generation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
