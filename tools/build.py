#!/usr/bin/env python3
"""Build and package SpectrumAPI without installing or restarting anything.

Defaults: require the production Release signing identity, vendor pinned inputs,
generate AIDL, build arm64 API35 native binaries with static libc++, assemble
Debug/signed R8 Release artifacts and Manager lint, then package only verified
outputs and their manifest under dist/. --variant debug needs no production key.
Use --package-only to validate/package existing outputs without any build or
network access (Release still checks the expected production identity).
--build-only still performs native ELF checks; --skip-java allows native-only
validation without requiring old APKs. Link inputs default to audited stubs;
external link-only binder/utils inputs require both explicit platform-link flags.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import zipfile

from generate_aidl import resolve_sdk
from release_support import (expected_certificate, release_shrinker, select_apk,
                             validate_entry_definitions, verify_apk)


ROOT = Path(__file__).resolve().parents[1]
NDK_VERSION = "28.2.13676358"
ABI = "arm64-v8a"
NATIVE_API = 35
SUPPORTED_DEVICE_APIS = (35, 36, 37)
DEVICE_API = SUPPORTED_DEVICE_APIS[-1]
TEMPLATES = (
    "module.prop", "zn_modules.txt", "customize.sh",
    "sepolicy.rule", "NOTICE",
)
NATIVE_FILES = {
    "libspectrum_payload.so": "lib/libspectrum_payload.so",
}
# Deliberately fixed, not inferred from whatever a new build happens to import.
# Changing this private ABI contract requires a separately reviewed source change.
ANDROID_CPP_UNDEFINED = {
    "_ZNK7android7RefBase9decStrongEPKv": None,
    "_ZNK7android8String164sizeEv": None,
    "_ZN7android14sp_report_raceEv": None,
    "_ZN7android7BBinder11wasParceledEv": "LIBBINDER",
    "_ZN7android7BBinder12getExtensionEv": "LIBBINDER",
    "_ZN7android7BBinder12setExtensionERKNS_2spINS_7IBinderEEE": "LIBBINDER",
}
PRIVATE_BINDER_CONVERSION = "_Z25AIBinder_toPlatformBinderP8AIBinder"


def properties(path: Path) -> dict[str, str]:
    result = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def regular_file(path: Path, base: Path | None = None) -> Path:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"Required regular file is missing or is a symlink: {path}")
    resolved = path.resolve(strict=True)
    if base is not None and not resolved.is_relative_to(base.resolve(strict=True)):
        raise ValueError(f"Input escapes its declared directory: {path}")
    return resolved


def digest(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def run(command: list[str]) -> None:
    print("+ " + shlex.join(command), flush=True)
    subprocess.run(command, cwd=ROOT, check=True)


def find_executable(override: Path | None, name: str, sdk: Path) -> Path:
    suffix = ".exe" if os.name == "nt" else ""
    if override is not None:
        candidates = (override, override / "bin" / (name + suffix), override / (name + suffix))
    else:
        installations = list((sdk / "cmake").glob("*"))
        installations.sort(key=lambda path: tuple(int(n) for n in re.findall(r"\d+", path.name)), reverse=True)
        candidates = tuple(path / "bin" / (name + suffix) for path in installations)
        located = shutil.which(name)
        if located:
            candidates += (Path(located),)
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    raise FileNotFoundError(f"Cannot locate {name}; pass --{name} with an absolute executable or installation path")


def ndk_prebuilt(ndk: Path) -> Path:
    directory = ndk / "toolchains" / "llvm" / "prebuilt"
    host = "windows-x86_64" if os.name == "nt" else ("darwin-x86_64" if sys.platform == "darwin" else "linux-x86_64")
    result = directory / host
    if not result.is_dir():
        raise FileNotFoundError(f"NDK host toolchain not found: {result}")
    return result


def native_configuration(build_dir: Path, ndk: Path, link_mode: str, link_dir: Path | None) -> dict[str, str]:
    cache_file = regular_file(build_dir / "CMakeCache.txt", build_dir)
    cache = {}
    cache_types = {}
    for line in cache_file.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith(("#", "//")) and "=" in line:
            key, value = line.split("=", 1)
            name, _, cache_type = key.partition(":")
            cache[name] = value
            cache_types[name] = cache_type
    required = {"ANDROID_ABI": ABI, "ANDROID_PLATFORM": f"android-{NATIVE_API}", "ANDROID_STL": "c++_static"}
    for name, value in required.items():
        if cache.get(name) != value:
            raise ValueError(f"Native CMake cache requires {name}={value}; found {cache.get(name)!r}")
    toolchain = Path(cache.get("CMAKE_TOOLCHAIN_FILE", "")).resolve()
    if not toolchain.is_relative_to(ndk):
        raise ValueError("Native CMake cache was built with a different NDK directory")
    for name in ("CMAKE_C_FLAGS", "CMAKE_CXX_FLAGS"):
        if "-Werror" not in shlex.split(cache.get(name, "")):
            raise ValueError(f"Native build must enable strict warnings with {name}=-Werror")
    # An unused -D argument is still written to CMakeCache as UNINITIALIZED.
    # Require CMake to actually declare the STRING option before claiming stubs.
    if cache_types.get("SPECTRUM_PLATFORM_LINK_MODE") != "STRING" or cache.get("SPECTRUM_PLATFORM_LINK_MODE") != link_mode:
        raise ValueError("Native cache does not implement the requested SPECTRUM_PLATFORM_LINK_MODE; rebuild after the CMake link-input change")
    if link_mode == "external" and (link_dir is None or Path(cache.get("SPECTRUM_PLATFORM_LINK_DIR", "")).resolve() != link_dir):
        raise ValueError("Native cache external platform-link directory does not match --platform-link-dir")
    return {"platformLinkMode": link_mode, "abi": cache["ANDROID_ABI"], "platform": cache["ANDROID_PLATFORM"],
            "stl": cache["ANDROID_STL"], "buildType": cache.get("CMAKE_BUILD_TYPE", ""),
            "cFlags": cache["CMAKE_C_FLAGS"], "cxxFlags": cache["CMAKE_CXX_FLAGS"]}


def elf_metadata(path: Path) -> dict[str, object]:
    """Read bounded ELF64 headers/dynamic strings; never execute a target binary."""
    data = regular_file(path).read_bytes()
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"Expected little-endian ELF64: {path}")
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    if header[1] != 3 or header[2] != 183:
        raise ValueError(f"Expected AArch64 shared object/PIE: {path}")
    phoff, phsize, count = header[5], header[9], header[10]
    if phsize < 56 or count > 4096 or phoff + phsize * count > len(data):
        raise ValueError(f"Invalid ELF program headers: {path}")
    loads = []
    dynamic = None
    for index in range(count):
        entry = struct.unpack_from("<IIQQQQQQ", data, phoff + phsize * index)
        kind, offset, address, size = entry[0], entry[2], entry[3], entry[5]
        if offset + size > len(data):
            raise ValueError(f"Out-of-bounds ELF segment: {path}")
        if kind == 1:
            loads.append((address, offset, size))
        elif kind == 2:
            dynamic = (offset, size)
    if dynamic is None:
        raise ValueError(f"Dynamic segment is missing: {path}")
    tags: dict[int, list[int]] = {}
    for offset in range(dynamic[0], dynamic[0] + dynamic[1] - 15, 16):
        tag, value = struct.unpack_from("<qQ", data, offset)
        if tag == 0:
            break
        tags.setdefault(tag, []).append(value)
    if 5 not in tags or 10 not in tags:
        raise ValueError(f"ELF dynamic string table is missing: {path}")
    address, size = tags[5][0], tags[10][0]
    table = None
    for start, offset, length in loads:
        if start <= address and address + size <= start + length:
            table = data[offset + address - start:offset + address - start + size]
            break
    if table is None:
        raise ValueError(f"ELF dynamic string table escapes a load segment: {path}")

    def string(index: int) -> str:
        if index >= len(table):
            raise ValueError(f"Invalid ELF dynamic string offset: {path}")
        end = table.find(b"\0", index)
        if end < 0:
            raise ValueError(f"Unterminated ELF dynamic string: {path}")
        return table[index:end].decode("utf-8")

    needed = [string(value) for value in tags.get(1, [])]
    if any("/" in name or "\\" in name or ":" in name for name in needed):
        raise ValueError(f"ELF embeds a non-portable dependency path: {path}: {needed}")
    runpaths = [part for tag in (29, 15) for value in tags.get(tag, []) for part in string(value).split(":")]
    return {"elfClass": 64, "machine": "AArch64", "needed": needed, "runpath": runpaths}


def readelf_dynamic(readelf: Path, source: Path) -> tuple[list[dict[str, str]], dict[str, list[str]]]:
    """Let the pinned NDK decode ELF versions; parse its stable, non-demangled rows."""
    result = subprocess.run([str(readelf), "--dyn-syms", "--dynamic", "--wide", "--no-demangle", str(source)],
                            check=False, capture_output=True, text=True, encoding="utf-8", errors="strict",
                            env={**os.environ, "LC_ALL": "C", "LANG": "C"})
    if result.returncode:
        raise ValueError(f"NDK llvm-readelf could not audit {source.name}")
    output = result.stdout
    tables = re.findall(r"^Symbol table '\.dynsym' contains (\d+) entries:\s*$", output, re.MULTILINE)
    if len(tables) != 1:
        raise ValueError(f"Expected one complete readelf dynamic symbol table: {source.name}")
    symbols = []
    numbers = []
    pattern = re.compile(r"^\s*(\d+):\s+([0-9a-fA-F]+)\s+(\d+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)(?:\s+(.*?))?\s*$")
    for line in output.splitlines():
        row = pattern.fullmatch(line)
        if row is None:
            continue
        index, value, size, kind, binding, visibility, section, raw_name = row.groups()
        raw_name = raw_name or ""
        # Some readelf versions append a version-table index, e.g. "@LIBBINDER (2)".
        name = re.fullmatch(r"(\S+)(?:\s+\(\d+\))?", raw_name) if raw_name else None
        if raw_name and name is None:
            raise ValueError(f"Malformed readelf dynamic symbol name: {source.name}")
        symbols.append({"name": name[1] if name else "", "type": kind, "binding": binding,
                        "visibility": visibility, "section": section, "value": value, "size": size})
        numbers.append(int(index))
    if numbers != list(range(int(tables[0]))):
        raise ValueError(f"Incomplete or duplicate readelf dynamic symbol rows: {source.name}")
    tags: dict[str, list[str]] = {}
    for tag, value in re.findall(r"^\s*0x[0-9a-fA-F]+\s+\(([^)]+)\)\s+(.*?)\s*$", output, re.MULTILINE):
        tags.setdefault(tag, []).append(value)
    if "NULL" not in tags:
        raise ValueError(f"Missing readelf dynamic section: {source.name}")
    return symbols, tags


def audit_platform_linkage(build_dir: Path, prebuilt: Path, link_mode: str) -> dict[str, object]:
    """Reject private-ABI drift before build-only success or any distribution."""
    if link_mode not in ("stubs", "external"):
        raise ValueError("Unknown platform-link audit mode")
    candidate = prebuilt / "bin" / ("llvm-readelf.exe" if os.name == "nt" else "llvm-readelf")
    # The Linux NDK may use an in-toolchain llvm-readobj symlink. Validate its
    # canonical target, but invoke the readelf alias: LLVM selects GNU versus
    # LLVM output from argv[0], so executing a resolved readobj would break parsing.
    # Never fall back to a host/PATH readelf.
    regular_file(candidate.resolve(strict=True), prebuilt)
    readelf = candidate
    payload = regular_file(build_dir / "libspectrum_payload.so", build_dir)
    symbols, _ = readelf_dynamic(readelf, payload)
    required_exports = {"zn_module": ("OBJECT", 16)}
    exports = [symbol for symbol in symbols if symbol["section"] != "UND" and symbol["binding"] != "LOCAL"]
    if len(exports) != len(required_exports) or {symbol["name"] for symbol in exports} != set(required_exports):
        raise ValueError("FD-injected payload must export only zn_module")
    for symbol in exports:
        kind, size = required_exports[symbol["name"]]
        if ((symbol["type"], symbol["binding"], symbol["visibility"]) != (kind, "GLOBAL", "DEFAULT") or
                (size is not None and int(symbol["size"]) != size)):
            raise ValueError(f"Injected payload export has an incompatible type/size: {symbol['name']}")
    undefined = [symbol for symbol in symbols if symbol["section"] == "UND" and symbol["name"]]
    if any(symbol["name"].split("@", 1)[0] == PRIVATE_BINDER_CONVERSION for symbol in undefined):
        raise ValueError("Private AIBinder_toPlatformBinder conversion must not occur in dynamic UND")
    # Match the encoded android namespace anywhere in a C++ mangling, including
    # const/volatile members, RTTI/vtables/thunks and template arguments.
    android = [symbol for symbol in undefined if symbol["name"].startswith("_Z") and "7android" in symbol["name"].split("@", 1)[0]]
    actual = [symbol["name"].split("@", 1)[0] for symbol in android]
    missing = sorted(set(ANDROID_CPP_UNDEFINED) - set(actual))
    unknown = sorted(set(actual) - set(ANDROID_CPP_UNDEFINED))
    if missing or unknown or len(actual) != len(set(actual)):
        raise ValueError(f"Payload android C++ UND violates the fixed allowlist; missing={missing}, unknown={unknown}, duplicates={len(actual) != len(set(actual))}")
    for symbol in android:
        name = symbol["name"].split("@", 1)[0]
        version = ANDROID_CPP_UNDEFINED[name]
        expected = name + (f"@{version}" if version else "")
        if symbol["name"] != expected or (symbol["type"], symbol["binding"], symbol["visibility"]) != ("FUNC", "GLOBAL", "DEFAULT"):
            raise ValueError(f"Payload private UND has the wrong version/type/binding: {name}")
    stubs = {}
    if link_mode == "stubs":
        for soname, version in (("libbinder.so", "LIBBINDER"), ("libutils.so", None)):
            source = regular_file(build_dir / "link-only" / soname, build_dir)
            exports, tags = readelf_dynamic(readelf, source)
            declared_sonames = [match[1] for value in tags.get("SONAME", []) if (match := re.fullmatch(r"Library soname:\s*\[([^\]]+)\]", value))]
            if len(tags.get("SONAME", [])) != 1 or declared_sonames != [soname]:
                raise ValueError(f"Link-only stub has the wrong SONAME: {soname}")
            if tags.get("NEEDED"):
                raise ValueError(f"Link-only stub must have no DT_NEEDED: {soname}")
            if any(symbol["section"] == "UND" and symbol["name"] for symbol in exports):
                raise ValueError(f"Link-only stub must have no dynamic UND: {soname}")
            public = [symbol for symbol in exports if symbol["section"] != "UND" and symbol["binding"] != "LOCAL"]
            expected_exports = {name + (f"@@{version}" if version else "") for name, required_version in ANDROID_CPP_UNDEFINED.items() if required_version == version}
            if ({symbol["name"] for symbol in public} != expected_exports or len(public) != len(expected_exports) or
                    any((symbol["type"], symbol["binding"], symbol["visibility"]) != ("FUNC", "GLOBAL", "DEFAULT") for symbol in public)):
                raise ValueError(f"Link-only stub exports differ from its fixed three GLOBAL FUNC/version contract: {soname}")
            stubs[soname] = {"soname": soname, "needed": [], "exports": sorted(expected_exports),
                             "sha256": digest(source), "runtimePackaged": False}
    return {"verifier": "NDK llvm-readelf", "ndk": NDK_VERSION, "platformLinkMode": link_mode,
            "androidCppUndefined": sorted(name + (f"@{version}" if version else "") for name, version in ANDROID_CPP_UNDEFINED.items()),
            "privateBinderConversionUndefined": False, "linkOnlyStubs": stubs}


ALLOWED_PAYLOAD_NEEDED = {
    "libbinder.so", "libutils.so", "libbinder_ndk.so",
    "libandroid.so", "liblog.so", "libdl.so", "libm.so", "libc.so",
}


def validate_native(build_dir: Path) -> dict[str, dict[str, object]]:
    result = {}
    for name, destination in NATIVE_FILES.items():
        source = regular_file(build_dir / name, build_dir)
        info = elf_metadata(source)
        if "libc++_shared.so" in info["needed"]:
            raise ValueError(f"{name} must not depend on libc++_shared.so; rebuild with -DANDROID_STL=c++_static")
        if info["runpath"]:
            raise ValueError(f"{name} must not set DT_RUNPATH/DT_RPATH: {info['runpath']}")
        unexpected = sorted(set(info["needed"]) - ALLOWED_PAYLOAD_NEEDED)
        if unexpected:
            raise ValueError(f"{name} imports unexpected shared libraries: {unexpected}")
        result[destination] = {**info, "sha256": digest(source), "bytes": source.stat().st_size}
    return result


def validate_archive_name(name: str) -> None:
    parts = name.split("/")
    if (not name or "\\" in name or ":" in name or name.startswith("/")
            or any(part in ("", ".", "..") for part in parts)
            or PurePosixPath(name).is_absolute()):
        raise ValueError(f"Unsafe archive path: {name!r}")


def zip_timestamp() -> tuple[int, int, int, int, int, int]:
    value = int(os.environ.get("SOURCE_DATE_EPOCH", "315532800"))
    date = datetime.fromtimestamp(value, timezone.utc)
    if not 1980 <= date.year <= 2107:
        raise ValueError("SOURCE_DATE_EPOCH must be within the ZIP timestamp range (1980..2107)")
    return (date.year, date.month, date.day, date.hour, date.minute, date.second)


def create_module_zip(destination: Path, entries: dict[str, tuple[bytes, int]]) -> list[dict[str, object]]:
    inventory = []
    timestamp = zip_timestamp()
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(entries):
            validate_archive_name(name)
            content, mode = entries[name]
            info = zipfile.ZipInfo(name, date_time=timestamp)
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, content)
            inventory.append({"path": name, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest(), "mode": oct(mode)})
    with zipfile.ZipFile(destination) as archive:
        if archive.namelist() != sorted(entries) or len(archive.namelist()) != len(set(archive.namelist())):
            raise ValueError("Unexpected or duplicate module ZIP entries")
        for info in archive.infolist():
            validate_archive_name(info.filename)
            if stat.S_IFMT(info.external_attr >> 16) != stat.S_IFREG:
                raise ValueError(f"Non-regular archive entry: {info.filename}")
            if archive.read(info.filename) != entries[info.filename][0]:
                raise ValueError(f"Module ZIP integrity failure: {info.filename}")
    return inventory


def java_artifacts(variants: list[str], sdk: Path, expected_release: str | None,
                   identity_role: str) -> tuple[list[tuple[Path, str]], dict[str, object]]:
    result = []
    managers = {}
    for variant in variants:
        for module in ("protocol", "sdk"):
            source = regular_file(ROOT / module / "build" / "outputs" / "aar" / f"{module}-{variant}.aar")
            with zipfile.ZipFile(source) as archive:
                names = set(archive.namelist())
                if not {"AndroidManifest.xml", "classes.jar"}.issubset(names) or archive.testzip() is not None:
                    raise ValueError(f"Incomplete or corrupt Android library archive: {source}")
                manifest = archive.read("AndroidManifest.xml")
                if b"<queries" in manifest or b"SpectrumBroker" in manifest:
                    raise ValueError(f"Library still declares the removed Manager bridge: {source}")
                if any(name.startswith("jni/") or "xposed" in name.lower() for name in names):
                    raise ValueError(f"Pure-Java library contains native/Xposed artifacts: {source}")
                with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
                    for name in classes.namelist():
                        if name.startswith(("kotlin/", "androidx/compose/", "hk/uwu/roxyhook/")) or "SpectrumBroker" in name:
                            raise ValueError(f"Unexpected optional-app dependency in {source}: {name}")
                        if module == "sdk" and name.endswith(".class"):
                            data = classes.read(name)
                            if any(marker in data for marker in (b"android/content/ServiceConnection", b"hk/uwu/spectrumapi/manager/", b"ISpectrumBroker", b"bindService")):
                                raise ValueError(f"SDK still references the removed Manager bridge: {name}")
            result.append((source, f"spectrum-{module}-{variant}.aar"))
        directory = ROOT / "manager" / "build" / "outputs" / "apk" / variant
        apk = regular_file(select_apk(directory, variant), directory)
        signature = verify_apk(apk, sdk, expected_release if variant == "release" else None)
        shrinker: dict[str, object] = {"minifyEnabled": False, "shrinkResources": False}
        with zipfile.ZipFile(apk) as archive:
            names = set(archive.namelist())
            required = {"AndroidManifest.xml", "META-INF/xposed/java_init.list", "META-INF/xposed/module.prop", "META-INF/xposed/scope.list"}
            if not required.issubset(names) or archive.testzip() is not None:
                raise ValueError(f"Invalid modern Xposed APK: {apk}")
            if archive.read("META-INF/xposed/scope.list").decode("utf-8").split() != ["com.android.systemui"]:
                raise ValueError(f"Xposed scope must contain only SystemUI: {apk}")
            entries = archive.read("META-INF/xposed/java_init.list").decode("utf-8").split()
            if len(entries) != 1 or not re.fullmatch(r"hk\.uwu\.roxyhook\.generated\.RoxyEntry_[a-f0-9]{24}", entries[0]):
                raise ValueError(f"Expected exactly one generated modern Xposed entry: {apk}")
            validate_entry_definitions(archive, entries)
            if variant == "release":
                if expected_release is None:
                    raise ValueError("Release distribution requires an expected signing certificate")
                shrinker, reports = release_shrinker(ROOT, entries[0], identity_role)
                result.extend(reports)
            if "assets/xposed_init" in names or any(name.endswith("libspectrum_discovery.so") for name in names):
                raise ValueError(f"Legacy Xposed/Broker discovery payload in {apk}")
            props = archive.read("META-INF/xposed/module.prop").decode("utf-8").splitlines()
            if "minApiVersion=102" not in props or "targetApiVersion=102" not in props or "autoHotReload=false" not in props:
                raise ValueError(f"Unexpected modern Xposed API/hot-reload configuration: {apk}")
        destination = f"spectrum-manager-{variant}.apk"
        managers[variant] = {"artifact": destination, "signature": signature, "r8": shrinker,
                             "signingIdentityRole": identity_role if variant == "release" else "debug",
                             "xposedEntry": entries[0], "outputMetadataSha256": digest(directory / "output-metadata.json")}
        result.append((apk, destination))
    return result, managers


def package(args: argparse.Namespace, ndk: Path, prebuilt: Path, variants: list[str], steps: dict[str, bool]) -> None:
    template = ROOT / "module"
    module_properties = properties(regular_file(template / "module.prop", template))
    if module_properties.get("id") != "spectrumapi":
        raise ValueError("The module id must remain spectrumapi")
    version = module_properties.get("version", "")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", version):
        raise ValueError("Unsafe or missing module version")
    declaration = (template / "zn_modules.txt").read_text(encoding="utf-8").strip()
    if declaration != "path=/system/bin/audioserver lib/libspectrum_payload.so":
        raise ValueError("The module must declare only the exact audioserver injection path")
    configuration = native_configuration(args.native_build_dir, ndk, args.platform_link_mode, args.platform_link_dir)
    native = validate_native(args.native_build_dir)
    link_audit = audit_platform_linkage(args.native_build_dir, prebuilt, args.platform_link_mode)
    java, managers = java_artifacts(variants, args.sdk, args.expected_release_certificate, args.identity_role)
    metadata = {
        "schemaVersion": 2,
        "product": "SpectrumAPI", "version": version,
        # Keep the legacy single target; the list records the installer allowlist.
        "abi": ABI, "nativeMinimumApi": NATIVE_API, "targetDeviceApi": DEVICE_API,
        "supportedDeviceApis": SUPPORTED_DEVICE_APIS,
        "toolchain": {"ndk": NDK_VERSION, "androidStl": "c++_static", "compileSdk": 37,
                      "gradle": "9.9.1", "agp": "9.5.1", "javaSourceTarget": 17,
                      "optionalAppKotlinComposeCompiler": "2.4.20", "optionalAppMiuix": "0.9.4",
                      "optionalAppRoxyHook": "com.github.NekoStash.RoxyHook:*:24b1267db5"},
        "sdkRequiresManagerApk": False,
        "optionalManagerRole": "modern Xposed SystemUI media-island display and settings",
        "xposedScope": ["com.android.systemui"],
        "javaVariants": variants,
        "managerVariants": managers,
        "expectedReleaseCertificateSha256": args.expected_release_certificate,
        "stepsExecutedThisInvocation": steps,
        "managerLintExecutedThisInvocation": steps["java"],
        "deviceValidated": False,
        "artifactHashesAreRuntimeAuthorization": False,
        "nativeConfiguration": configuration,
        "nativeArtifacts": native,
        "nativeLinkAudit": link_audit,
    }
    entries = {}
    for name in TEMPLATES:
        path = regular_file(template / name, template)
        content = path.read_text(encoding="utf-8").replace("\r\n", "\n").encode("utf-8")
        entries[name] = (content, 0o644)
    for name, destination in NATIVE_FILES.items():
        entries[destination] = ((args.native_build_dir / name).read_bytes(), 0o644)
    for destination, info in native.items():
        if hashlib.sha256(entries[destination][0]).hexdigest() != info["sha256"]:
            raise ValueError(f"Native input changed while packaging: {destination}; retry after the build finishes")
    entries["build-info.json"] = ((json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode("utf-8"), 0o644)
    notices = {
        "notices/NDK_TOOLCHAIN_NOTICE": regular_file(prebuilt / "NOTICE", ndk),
        "notices/THIRD_PARTY_NOTICE": regular_file(ROOT / "third_party" / "NOTICE", ROOT / "third_party"),
        "notices/AOSP_NOTICE": regular_file(ROOT / "third_party" / "aosp" / "licenses" / "frameworks" / "native" / "NOTICE", ROOT / "third_party"),
    }
    for name, source in notices.items():
        entries[name] = (source.read_bytes(), 0o644)
    # This allowlist is the complete archive: never recursively copy native/, build/,
    # third_party/, ROM attachments, or restricted official Zygisk API headers.
    allowed = set(TEMPLATES) | set(NATIVE_FILES.values()) | {"build-info.json"} | set(notices)
    forbidden_runtime_libraries = {"libbinder.so", "libutils.so", "libbinder_ndk.so", "libc++_shared.so"}
    if (set(entries) != allowed or any(name.endswith((".h", ".hpp", ".md")) for name in entries) or
            any(PurePosixPath(name).name in forbidden_runtime_libraries or "link-only" in PurePosixPath(name).parts for name in entries)):
        raise ValueError("Module archive contents violate the explicit release allowlist")
    dist = args.dist_dir
    if dist.is_symlink():
        raise ValueError(f"Output directory must not be a symlink: {dist}")
    dist.mkdir(parents=True, exist_ok=True)
    dist = dist.resolve()
    with tempfile.TemporaryDirectory(prefix=".spectrum-package-", dir=dist) as temporary:
        stage = Path(temporary)
        zip_name = f"spectrumapi-{version}-{ABI}.zip"
        inventory = create_module_zip(stage / zip_name, entries)
        for source, name in java:
            validate_archive_name(name)
            shutil.copyfile(source, stage / name)
        artifacts = []
        for path in sorted(stage.iterdir()):
            artifacts.append({"path": path.name, "bytes": path.stat().st_size, "sha256": digest(path)})
        manifest = {**metadata, "artifacts": artifacts, "moduleEntries": inventory,
                    "distributionFiles": [item["path"] for item in artifacts] + ["build-manifest.json"],
                    "unsignedManagerApks": [],
                    "installationExecuted": False, "processRestartExecuted": False}
        (stage / "build-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        staged_paths = sorted(stage.iterdir(), key=lambda path: (path.name == "build-manifest.json", path.name))
        for path in staged_paths:
            output = dist / path.name
            if output.is_symlink() or (output.exists() and not output.is_file()):
                raise ValueError(f"Refusing to replace a non-regular output: {output}")
        # Publish the manifest last, after every artifact has been replaced.
        for path in staged_paths:
            output = dist / path.name
            os.replace(path, output)
            print(f"Packaged: {output}")
    print("Device installation/injection/capture/SELinux validation: NOT PERFORMED")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sdk", type=Path, help="Android SDK; otherwise environment or local.properties")
    parser.add_argument("--ndk", type=Path, help=f"NDK directory (default: SDK/ndk/{NDK_VERSION})")
    parser.add_argument("--cmake", type=Path, help="CMake executable or installation directory")
    parser.add_argument("--ninja", type=Path, help="Ninja executable; defaults to CMake's sibling ninja")
    parser.add_argument("--native-build-dir", "--native-dir", dest="native_build_dir", type=Path, default=ROOT / "build" / "native-arm64")
    parser.add_argument("--dist-dir", type=Path, default=ROOT / "dist")
    parser.add_argument("--build-type", choices=("Release", "Debug", "RelWithDebInfo"), default="Release")
    parser.add_argument("--variant", choices=("debug", "release", "both"), default="both", help="Java artifacts to build/package (default: both)")
    parser.add_argument("--jobs", type=int, default=max(1, os.cpu_count() or 1))
    parser.add_argument("--platform-link-mode", choices=("stubs", "external"), default="stubs")
    parser.add_argument("--platform-link-dir", type=Path, help="Explicit link-only binder/utils directory for external mode (never packaged)")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--package-only", action="store_true", help="Skip all downloads and build commands; validate/package existing artifacts")
    mode.add_argument("--build-only", action="store_true", help="Run requested build stages without publishing artifacts")
    for step in ("vendor", "aidl", "native", "java"):
        parser.add_argument(f"--skip-{step}", action="store_true", help=f"Use existing outputs instead of running the {step} step")
    args = parser.parse_args()
    try:
        if args.jobs < 1:
            raise ValueError("--jobs must be positive")
        variants = ["debug", "release"] if args.variant == "both" else [args.variant]
        args.expected_release_certificate, args.identity_role = expected_certificate(ROOT) if "release" in variants else (None, "debug")
        if args.platform_link_mode == "external":
            if args.platform_link_dir is None or not args.platform_link_dir.is_dir():
                raise ValueError("external platform linking requires --platform-link-dir")
            args.platform_link_dir = args.platform_link_dir.expanduser().resolve()
        elif args.platform_link_dir is not None:
            raise ValueError("--platform-link-dir is valid only with --platform-link-mode external")
        sdk = resolve_sdk(args.sdk)
        args.sdk = sdk
        ndk = (args.ndk or sdk / "ndk" / NDK_VERSION).expanduser().resolve()
        revision = properties(regular_file(ndk / "source.properties", ndk)).get("Pkg.Revision")
        if revision != NDK_VERSION:
            raise ValueError(f"NDK {NDK_VERSION} is required; found {revision!r} at {ndk}")
        prebuilt = ndk_prebuilt(ndk)
        if args.native_build_dir.is_symlink() or args.dist_dir.is_symlink():
            raise ValueError("Native build/output directories must not be symlinks")
        args.native_build_dir = args.native_build_dir.expanduser().resolve()
        args.dist_dir = args.dist_dir.expanduser().resolve()
        steps = {name: not (args.package_only or getattr(args, f"skip_{name}")) for name in ("vendor", "aidl", "native", "java")}
        if steps["vendor"]:
            run([sys.executable, str(ROOT / "tools" / "vendor_platform_headers.py"), "--ndk", str(ndk), "--zygisk"])
        if steps["aidl"]:
            run([sys.executable, str(ROOT / "tools" / "generate_aidl.py"), "--sdk", str(sdk)])
        if steps["native"]:
            cmake = find_executable(args.cmake, "cmake", sdk)
            ninja = args.ninja or cmake.parent / ("ninja.exe" if os.name == "nt" else "ninja")
            ninja = regular_file(ninja)
            cache_path = args.native_build_dir / "CMakeCache.txt"
            if cache_path.is_file() and "ANDROID_STL:STRING=c++_static" not in cache_path.read_text(encoding="utf-8", errors="ignore"):
                shutil.rmtree(args.native_build_dir)
            platform_flags = [f"-DSPECTRUM_PLATFORM_LINK_MODE={args.platform_link_mode}"]
            if args.platform_link_dir is not None:
                platform_flags.append(f"-DSPECTRUM_PLATFORM_LINK_DIR={args.platform_link_dir}")
            run([str(cmake), "-S", str(ROOT / "native"), "-B", str(args.native_build_dir), "-G", "Ninja",
                 f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build' / 'cmake' / 'android.toolchain.cmake'}",
                 f"-DANDROID_ABI={ABI}", f"-DANDROID_PLATFORM=android-{NATIVE_API}", "-DANDROID_STL=c++_static",
                 f"-DCMAKE_BUILD_TYPE={args.build_type}", "-DCMAKE_C_FLAGS=-Werror", "-DCMAKE_CXX_FLAGS=-Werror",
                 f"-DSPECTRUM_SDK_ROOT={sdk}",
                 f"-DCMAKE_MAKE_PROGRAM={ninja}", "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON", *platform_flags])
            run([str(cmake), "--build", str(args.native_build_dir), "--parallel", str(args.jobs),
                 "--target", "spectrum_payload"])
        # --build-only is not an escape hatch around native ABI checks.
        # Even reused native outputs must validate before any successful return.
        native_configuration(args.native_build_dir, ndk, args.platform_link_mode, args.platform_link_dir)
        validate_native(args.native_build_dir)
        audit_platform_linkage(args.native_build_dir, prebuilt, args.platform_link_mode)
        if steps["java"]:
            wrapper = regular_file(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
            tasks = [f":{module}:assemble{variant.title()}" for variant in variants for module in ("protocol", "sdk", "manager")]
            tasks += [f":manager:lint{variant.title()}" for variant in variants]
            run([*([str(wrapper)] if os.name == "nt" else ["bash", str(wrapper)]), *tasks,
                 "--console=plain", "--no-configuration-cache", "--no-daemon",
                 *(["--no-build-cache"] if "release" in variants else [])])
        if args.build_only and steps["java"]:
            # Validate APK signatures and R8/entry artifacts even when not packaging.
            # A deliberately native-only invocation does not require old Java outputs.
            java_artifacts(variants, sdk, args.expected_release_certificate, args.identity_role)
        if not args.build_only:
            package(args, ndk, prebuilt, variants, steps)
        return 0
    except (OSError, ValueError, KeyError, struct.error, UnicodeError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f"Build/package failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
