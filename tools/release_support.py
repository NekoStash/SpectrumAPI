"""Fail-closed signing and current-output inspection; never prints credentials."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile

SIGNING_KEYS = ("RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
BUILD_TOOLS = "37.0.0"


def signing_properties(path: Path) -> dict[str, str]:
    """Read Java Properties escapes/continuations, matching the Gradle UTF-8 reader."""
    if not path.exists():
        return {}
    result: dict[str, str] = {}
    logical = ""
    for physical in path.read_text(encoding="utf-8").splitlines():
        line = logical + (physical.lstrip() if logical else physical)
        slashes = len(line) - len(line.rstrip("\\"))
        if slashes % 2:
            logical = line[:-1]
            continue
        logical = ""
        line = line.lstrip()
        if not line or line.startswith(("#", "!")):
            continue
        match = re.match(r"((?:\\.|[^\s=:])*)(?:\s*[=:]\s*|\s+)?(.*)$", line)
        if not match:
            raise ValueError("Malformed signing.properties")
        def unescape(value: str) -> str:
            def replace(item: re.Match[str]) -> str:
                escaped = item.group(1)
                if escaped.startswith("u"):
                    return chr(int(escaped[1:], 16))
                return {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}.get(escaped, escaped)
            return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", replace, value)
        result[unescape(match[1])] = unescape(match[2])
    if logical:
        raise ValueError("Unterminated continuation in signing.properties")
    return result


def signing_inputs(root: Path, environment: dict[str, str] | None = None) -> tuple[Path, dict[str, str], str]:
    source = dict(os.environ if environment is None else environment)
    local = signing_properties(root / "signing.properties")
    values = {key: source[key] if key in source else local.get(key, "") for key in SIGNING_KEYS}
    missing = [key for key, value in values.items() if not value.strip()]
    if missing:
        raise ValueError("Release signing requires: " + ", ".join(missing))
    store = root / "release-key.jks"
    role = "production"
    if "RELEASE_STORE_FILE" in source or "SPECTRUM_SIGNING_TEST_MODE" in source:
        raise ValueError("Signing overrides are unsupported; use the root production release-key.jks")
    if store.is_symlink() or not store.is_file():
        raise ValueError("Release signing requires the regular root release-key.jks; no unsigned/Debug fallback")
    return store, values, role


def expected_certificate(root: Path) -> tuple[str, str]:
    store, values, role = signing_inputs(root)
    executable = "keytool.exe" if os.name == "nt" else "keytool"
    java_home = os.environ.get("JAVA_HOME")
    keytool = str(Path(java_home) / "bin" / executable) if java_home else executable
    # Passwords are passed only through this child's environment, never argv/logs.
    command = [keytool, "-exportcert", "-keystore", str(store), "-storetype", "JKS",
               "-alias", values["RELEASE_KEY_ALIAS"], "-storepass:env", "RELEASE_STORE_PASSWORD"]
    child_env = {**os.environ, **values}
    try:
        result = subprocess.run(command, env=child_env, check=True, capture_output=True)
        # A certificate alone does not prove the private-key password is usable.
        # certreq reads/signs with that key but writes only to captured stdout.
        subprocess.run([keytool, "-certreq", "-keystore", str(store), "-storetype", "JKS",
                        "-alias", values["RELEASE_KEY_ALIAS"], "-storepass:env", "RELEASE_STORE_PASSWORD",
                        "-keypass:env", "RELEASE_KEY_PASSWORD"], env=child_env, check=True, capture_output=True)
    except (OSError, subprocess.CalledProcessError):
        raise ValueError("Cannot validate the expected Release identity; check JKS and all three signing credentials") from None
    fingerprint = hashlib.sha256(result.stdout).hexdigest()
    pinned = os.environ.get("RELEASE_CERT_SHA256", "").replace(":", "").lower()
    if pinned and (not re.fullmatch(r"[a-f0-9]{64}", pinned) or pinned != fingerprint):
        raise ValueError("Release certificate does not match RELEASE_CERT_SHA256")
    return fingerprint, role


def select_apk(directory: Path, variant: str, application_id: str = "hk.uwu.spectrumapi") -> Path:
    metadata = directory / "output-metadata.json"
    if metadata.is_symlink():
        raise ValueError("APK metadata must not be a symlink")
    value = json.loads(metadata.read_text(encoding="utf-8"))
    if value.get("variantName") != variant or value.get("applicationId") != application_id:
        raise ValueError("APK output-metadata variant/applicationId mismatch")
    elements = value.get("elements", [])
    if len(elements) != 1 or elements[0].get("type") != "SINGLE" or elements[0].get("filters"):
        raise ValueError("Expected one unfiltered arm64-only Manager APK output")
    name = elements[0].get("outputFile", "")
    if not name or Path(name).name != name or any(item in name for item in ("/", "\\", ":")) or not name.endswith(".apk"):
        raise ValueError("Unsafe APK output-metadata filename")
    apk = directory / name
    if apk.is_symlink() or not apk.is_file() or not apk.resolve().is_relative_to(directory.resolve()):
        raise ValueError("APK output is missing, symlinked or outside its output directory")
    if "unsigned" in name.lower():
        raise ValueError("Unsigned APKs are never distribution candidates")
    return apk


def verify_apk(apk: Path, sdk: Path, expected: str | None = None) -> dict[str, object]:
    jar = sdk / "build-tools" / BUILD_TOOLS / "lib" / "apksigner.jar"
    if not jar.is_file():
        raise ValueError(f"Exact build-tools {BUILD_TOOLS} apksigner.jar is required")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else "java"
    result = subprocess.run([java, "-jar", str(jar), "verify", "--verbose", "--print-certs", "-Werr", str(apk)],
                            capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise ValueError(f"apksigner verification failed for {apk.name}")
    # Build-tools 37 labels certificates by scheme ("V2 Signer:",
    # "V3.1 Signer:") rather than only "Signer #1". One identity can appear
    # under several verified schemes; that is not a multi-signer APK.
    counts = re.findall(r"^Number of signers:[ \t]*(\d+)[ \t]*$", result.stdout, re.MULTILINE)
    certificates = re.findall(
        r"^(?:Signer #\d+|V[1234](?:\.\d+)? Signer(?: #\d+)?):?[ \t]+certificate SHA-256 digest:[ \t]*([0-9a-fA-F:]+)[ \t]*$",
        result.stdout, re.MULTILINE,
    )
    fingerprints = {value.replace(":", "").lower() for value in certificates}
    numbered = re.findall(r"^(?:V[1234](?:\.\d+)? )?Signer #(\d+):?[ \t]+certificate SHA-256 digest:",
                          result.stdout, re.MULTILINE)
    if (counts != ["1"] or len(fingerprints) != 1 or any(number != "1" for number in numbered)
            or any(not re.fullmatch(r"[a-f0-9]{64}", value) for value in fingerprints)):
        raise ValueError("Expected exactly one APK signing certificate")
    fingerprint = next(iter(fingerprints))
    if expected is not None and fingerprint != expected:
        raise ValueError("APK signer does not match the configured production Release certificate")
    schemes = {number: bool(re.search(rf"^Verified using v{number}(?:\.\d+)? scheme.*:[ \t]*true[ \t]*$",
                                     result.stdout, re.MULTILINE)) for number in (1, 2, 3)}
    if not schemes[2] and not schemes[3]:
        raise ValueError("Manager APK must have a verified v2 or v3 signature")
    return {"verified": True, "certificateSha256": fingerprint, "schemes": schemes, "verifier": f"apksigner {BUILD_TOOLS}"}


def dex_definitions(data: bytes) -> set[str]:
    if len(data) < 112 or not data.startswith(b"dex\n"):
        raise ValueError("Invalid APK DEX header")
    def uint(offset: int) -> int:
        if offset < 0 or offset + 4 > len(data):
            raise ValueError("Out-of-bounds DEX offset")
        return struct.unpack_from("<I", data, offset)[0]
    string_count, string_offset = uint(56), uint(60)
    type_count, type_offset = uint(64), uint(68)
    class_count, class_offset = uint(96), uint(100)
    if string_offset + string_count * 4 > len(data) or type_offset + type_count * 4 > len(data) or class_offset + class_count * 32 > len(data):
        raise ValueError("Out-of-bounds DEX index")
    result = set()
    for index in range(class_count):
        class_index = uint(class_offset + index * 32)
        if class_index >= type_count:
            raise ValueError("Invalid DEX class index")
        string_index = uint(type_offset + class_index * 4)
        if string_index >= string_count:
            raise ValueError("Invalid DEX type index")
        offset = uint(string_offset + string_index * 4)
        for _ in range(5):
            if offset >= len(data):
                raise ValueError("Invalid DEX string offset")
            byte = data[offset]
            offset += 1
            if not byte & 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = data.find(b"\0", offset)
        if end < 0:
            raise ValueError("Unterminated DEX class descriptor")
        result.add(data[offset:end].decode("utf-8"))
    return result


def validate_entry_definitions(archive: zipfile.ZipFile, entries: list[str]) -> None:
    missing = {"L" + name.replace(".", "/") + ";" for name in entries}
    dex = [name for name in archive.namelist() if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name)]
    if "classes.dex" not in dex:
        raise ValueError("Manager APK has no classes.dex")
    for name in dex:
        if archive.getinfo(name).file_size > 128 * 1024 * 1024:
            raise ValueError("Manager DEX exceeds the inspection limit")
        missing -= dex_definitions(archive.read(name))
    if missing:
        raise ValueError("Xposed entry was removed/renamed by R8: " + ", ".join(sorted(missing)))


def release_shrinker(root: Path, entry: str, expected_role: str) -> tuple[dict[str, object], list[tuple[Path, str]]]:
    outputs = root / "manager" / "build" / "outputs"
    contract = json.loads((outputs / "spectrum" / "release-build-contract.json").read_text(encoding="utf-8"))
    if contract != {"variant": "release", "minifyEnabled": True, "shrinkResources": True, "signingIdentityRole": expected_role}:
        raise ValueError("Release shrinker/signing build contract mismatch")
    mapping = outputs / "mapping" / "release" / "mapping.txt"
    configuration = outputs / "mapping" / "release" / "configuration.txt"
    for source in (mapping, configuration):
        if source.is_symlink() or not source.is_file() or not source.stat().st_size:
            raise ValueError("Release R8 mapping/configuration is missing or invalid")
    # Entry identity is literal in Xposed metadata; class definition is separately
    # checked in every DEX, not merely searched in string tables.
    expected_mapping = f"{entry} -> {entry}:"
    if expected_mapping not in mapping.read_text(encoding="utf-8").splitlines():
        raise ValueError("R8 mapping does not preserve the generated Xposed entry identity")
    report = {**contract, "mappingSha256": hashlib.sha256(mapping.read_bytes()).hexdigest(),
              "configurationSha256": hashlib.sha256(configuration.read_bytes()).hexdigest()}
    return report, [(mapping, "spectrum-manager-release-mapping.txt"),
                    (configuration, "spectrum-manager-release-r8-configuration.txt")]
