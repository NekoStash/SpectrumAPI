#!/usr/bin/env python3
"""Fetch the Android-only include closure from immutable upstream revisions.

This downloads headers, never Android runtime libraries or NDK/system headers.
Run with --ndk PATH to identify existing NDK headers before resolving AOSP ones.
The manifest records the exact URL and SHA-256 of every unmodified input.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import urllib.error
import urllib.request

REVISIONS = {
    "frameworks/native": "4f463a6b1de9198963dc6aff74154a504ba3f8f6",
    "system/core": "a3b721a32242006b59cb12bd62c9133632af3a2d",
    "system/libbase": "6d19b5c690fa4220c10e42c2326150bbd4d4b7bb",
    "system/logging": "bcac7c30d88a3773a7c0bc9f5617a23a886331fd",
}
INCLUDE_ROOTS = (
    ("frameworks/native", "libs/binder/include"),
    ("system/core", "libutils/binder/include"),
    ("system/core", "libutils/include"),
    ("system/libbase", "include"),
    ("system/logging", "liblog/include"),
    ("system/core", "libcutils/include"),
)
PREFIX_ROOTS = {
    "binder": (0,), "utils": (1, 2), "android-base": (3,),
    "log": (4,), "cutils": (5,),
}
SEEDS = ("binder/Binder.h", "binder/IServiceManager.h")
ZYGISK_REVISION = "db994f8caebd440eedd02beab38ca62f3d1f8a2a"
INCLUDES = re.compile(r'^\s*#\s*include\s*([<\"])([^>\"]+)[>\"]', re.MULTILINE)


def fetch(url: str) -> bytes | None:
    request = urllib.request.Request(url, headers={"User-Agent": "SpectrumAPI-header-vendor/1"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--refresh", action="store_true", help="Redownload pinned inputs")
    parser.add_argument("--zygisk", action="store_true", help="Download the exact official API header; upstream redistribution restrictions apply")
    args = parser.parse_args()
    workspace = Path(__file__).resolve().parents[1]
    destination = workspace / "third_party" / "aosp"
    destination.mkdir(parents=True, exist_ok=True)
    system_roots: list[Path] = []
    if args.ndk:
        prebuilts = args.ndk / "toolchains" / "llvm" / "prebuilt"
        for prebuilt in prebuilts.iterdir():
            root = prebuilt / "sysroot" / "usr" / "include"
            system_roots.extend((root, root / "c++" / "v1", root / "aarch64-linux-android"))
    manifest_path = destination / "manifest.json"
    previous_records = {}
    if manifest_path.is_file():
        previous_records = json.loads(manifest_path.read_text(encoding="utf-8")).get("files", {})
    records: dict[str, dict[str, str]] = {}
    visited: set[tuple[str, str]] = set()
    missing: set[tuple[str, str]] = set()

    def materialize(repo: str, path: str) -> bytes | None:
        key = (repo, path)
        if key in missing:
            return None
        target = destination / "licenses" / repo / path
        for root_repo, include_root in INCLUDE_ROOTS:
            if repo == root_repo and path.startswith(include_root + "/"):
                target = destination / "include" / path[len(include_root) + 1:]
                break
        url = f"https://android.googlesource.com/platform/{repo}/+/{REVISIONS[repo]}/{path}?format=TEXT"
        cached = target.read_bytes() if target.is_file() and not args.refresh else None
        recorded = previous_records.get(f"{repo}/{path}", {})
        if cached is not None and recorded.get("url") == url and recorded.get("sha256") == hashlib.sha256(cached).hexdigest():
            data = cached
        else:
            encoded = fetch(url)
            if encoded is None:
                missing.add(key)
                return None
            data = base64.b64decode(encoded, validate=True)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        records[f"{repo}/{path}"] = {"url": url, "sha256": hashlib.sha256(data).hexdigest()}
        return data

    def visit(repo: str, path: str) -> None:
        key = (repo, path)
        if key in visited:
            return
        visited.add(key)
        data = materialize(repo, path)
        if data is None:
            raise RuntimeError(f"Missing pinned input: {repo}/{path}")
        for delimiter, name in INCLUDES.findall(data.decode("utf-8")):
            # Do not shadow the NDK's public C/C++/Android headers.
            if any((root / name).is_file() for root in system_roots):
                continue
            if delimiter == '"':
                sibling = str(PurePosixPath(path).parent / name)
                if materialize(repo, sibling) is not None:
                    visit(repo, sibling)
                    continue
            candidates = PREFIX_ROOTS.get(name.split("/", 1)[0])
            if candidates is None:
                # Conditional host/Trusty headers and standard headers belong to
                # the toolchain, not this vendored platform include tree.
                continue
            for index in candidates:
                candidate_repo, root = INCLUDE_ROOTS[index]
                candidate_path = f"{root}/{name}"
                if materialize(candidate_repo, candidate_path) is not None:
                    visit(candidate_repo, candidate_path)
                    break
            else:
                raise RuntimeError(f"Unresolved platform include {name!r} in {repo}/{path}")

    for seed in SEEDS:
        visit(INCLUDE_ROOTS[0][0], f"{INCLUDE_ROOTS[0][1]}/{seed}")
    # Preserve the upstream Apache license verbatim in addition to each header.
    license_data = materialize("frameworks/native", "NOTICE")
    if license_data is None:
        raise RuntimeError("Upstream binder Apache license is missing")
    zygisk_manifest = workspace / "third_party" / "zygisk_next_api.json"
    if args.zygisk:
        url = f"https://raw.githubusercontent.com/LSPosed/ZygiskNext/{ZYGISK_REVISION}/zygisk_next_api.h"
        data = fetch(url)
        if data is None:
            raise RuntimeError("Pinned official Zygisk Next API is missing")
        api_root = workspace / "build" / "vendor" / "zygisk-next"
        api_root.mkdir(parents=True, exist_ok=True)
        (api_root / "zygisk_next_api.h").write_bytes(data)
        zygisk_manifest.write_text(json.dumps({
            "repository": "https://github.com/LSPosed/ZygiskNext",
            "revision": ZYGISK_REVISION,
            "url": url,
            "sha256": hashlib.sha256(data).hexdigest(),
            "license": "All rights reserved; no separate API-header license is provided upstream. See NOTICE.",
        }, indent=2) + "\n", encoding="utf-8")
    manifest = {
        "repositories": REVISIONS,
        "include_roots": ["include"],
        "seeds": list(SEEDS),
        "files": dict(sorted(records.items())),
    }
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Vendored {len(records)} unchanged AOSP files at fixed commits")
    print("include: third_party/aosp/include")
    if args.zygisk:
        print("include: build/vendor/zygisk-next (build-only; do not redistribute)")


if __name__ == "__main__":
    main()
