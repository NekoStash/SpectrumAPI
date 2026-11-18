#!/usr/bin/env python3
"""Small explicit CI/JitPack preparation and artifact allowlist."""
from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

from generate_aidl import resolve_sdk
from release_support import SIGNING_KEYS, expected_certificate, signing_properties

ROOT = Path(__file__).resolve().parents[1]
GROUP = "com.github.NekoStash.SpectrumAPI"
CMAKE_VERSION = "3.22.1"
REPORTS = ROOT / "build" / "reports" / "spectrum"


def write_report(name: str, report: dict[str, object]) -> None:
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / name).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


def command(args: list[str], cwd: Path = ROOT) -> None:
    # This helper never accepts credential-bearing argv.
    print("Running: " + " ".join(args), flush=True)
    subprocess.run(args, cwd=cwd, check=True)


def prepare_sdk(sdk_only: bool) -> None:
    sdk = resolve_sdk(None)
    manager = sdk / "cmdline-tools" / "latest" / "bin" / ("sdkmanager.bat" if os.name == "nt" else "sdkmanager")
    if not manager.is_file():
        located = shutil.which("sdkmanager")
        if not located:
            raise ValueError("Install official Android command-line tools; sdkmanager is unavailable")
        manager = Path(located)
    common = [str(manager), f"--sdk_root={sdk}"]
    subprocess.run([*common, "--licenses"], input="y\n" * 200, text=True, check=True)
    packages = ["platforms;android-37.0", "build-tools;37.0.0"]
    if not sdk_only:
        packages += ["ndk;28.2.13676358", f"cmake;{CMAKE_VERSION}"]
    command([*common, *packages])
    required = [sdk / "platforms/android-37.0/android.jar", sdk / "platforms/android-37.0/framework.aidl",
                sdk / "build-tools/37.0.0/lib/apksigner.jar", sdk / "build-tools/37.0.0/source.properties"]
    if not sdk_only:
        required += [sdk / "platforms/android-37.0/optional/libbinder_ndk_cpp/android/binder_interface_utils.h",
                     sdk / "ndk/28.2.13676358/source.properties", sdk / f"cmake/{CMAKE_VERSION}/source.properties"]
    for source in required:
        if not source.is_file() or source.is_symlink():
            raise ValueError(f"Exact SDK package is incomplete; no fallback is permitted: {source}")
    platform = signing_properties(sdk / "platforms/android-37.0/source.properties")
    tools = signing_properties(sdk / "build-tools/37.0.0/source.properties")
    # The android-37.0 package can report the full API level in source.properties.
    api_level = platform.get("AndroidVersion.ApiLevel")
    build_tools_revision = tools.get("Pkg.Revision")
    if api_level not in {"37", "37.0"} or build_tools_revision != "37.0.0":
        raise ValueError(
            f"Expected SDK API 37.0 and Build-Tools 37.0.0; "
            f"found API {api_level!r} and Build-Tools {build_tools_revision!r}"
        )
    if not sdk_only:
        ndk = signing_properties(sdk / "ndk/28.2.13676358/source.properties")
        if ndk.get("Pkg.Revision") != "28.2.13676358":
            raise ValueError("Installed NDK metadata does not match 28.2.13676358")
    write_report("sdk-preflight.json", {"packages": packages, "requiredFilesVerified": True})


def restore_key() -> None:
    # A reusable fork/PR job must never call this command or receive these envs.
    missing = [name for name in (*SIGNING_KEYS, "RELEASE_KEYSTORE_BASE64") if not os.environ.get(name, "").strip()]
    if missing:
        raise ValueError("Signed Release requires secret(s): " + ", ".join(missing))
    if "RELEASE_STORE_FILE" in os.environ or "SPECTRUM_SIGNING_TEST_MODE" in os.environ:
        raise ValueError("CI production signing does not accept test overrides")
    destination = ROOT / "release-key.jks"
    if destination.exists() or destination.is_symlink():
        raise ValueError("Refusing to overwrite an existing root release-key.jks")
    try:
        content = base64.b64decode(os.environ["RELEASE_KEYSTORE_BASE64"], validate=True)
    except (binascii.Error, ValueError):
        raise ValueError("RELEASE_KEYSTORE_BASE64 is invalid") from None
    if not content:
        raise ValueError("RELEASE_KEYSTORE_BASE64 decoded an empty file")
    marker = ROOT / "build" / ".ci-release-key-owned"
    marker.parent.mkdir(parents=True, exist_ok=True)
    # Marker precedes creation so the workflow's always-cleanup also handles a
    # validation failure after decoding. Existing user files are never owned.
    marker.write_text("created by tools/ci.py restore-key\n", encoding="utf-8")
    try:
        descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            output.write(content)
        fingerprint, role = expected_certificate(ROOT)
        write_report("release-certificate.json", {"certificateSha256": fingerprint, "signingIdentityRole": role})
    except Exception:
        cleanup_key()
        raise


def cleanup_key() -> None:
    marker = ROOT / "build" / ".ci-release-key-owned"
    if marker.is_file() and not marker.is_symlink():
        (ROOT / "release-key.jks").unlink(missing_ok=True)
        marker.unlink()


def publication_files(repository: Path, version: str) -> dict[str, str]:
    result = {}
    for module in ("protocol", "sdk"):
        directory = repository / GROUP.replace(".", "/") / module / version
        for suffix in (".aar", "-sources.jar", ".pom", ".module"):
            source = directory / f"{module}-{version}{suffix}"
            if not source.is_file() or source.is_symlink():
                raise ValueError(f"Missing Maven publication component: {module}{suffix}")
            result[f"{module}{suffix}"] = hashlib.sha256(source.read_bytes()).hexdigest()
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        pom = ET.parse(directory / f"{module}-{version}.pom").getroot()
        coords = tuple(pom.findtext(f"m:{name}", namespaces=ns) for name in ("groupId", "artifactId", "version"))
        if coords != (GROUP, module, version):
            raise ValueError("Published Maven coordinates do not match the intended GAV")
        dependencies = [(node.findtext("m:groupId", namespaces=ns), node.findtext("m:artifactId", namespaces=ns),
                         node.findtext("m:version", namespaces=ns), node.findtext("m:scope", namespaces=ns))
                        for node in pom.findall("m:dependencies/m:dependency", ns)]
        expected = [(GROUP, "protocol", version, "compile")] if module == "sdk" else []
        if dependencies != expected:
            raise ValueError("SDK POM must transitively expose only protocol; protocol must have no runtime dependencies")
        metadata = json.loads((directory / f"{module}-{version}.module").read_text(encoding="utf-8"))
        if metadata.get("component", {}).get("group") != GROUP or metadata.get("component", {}).get("module") != module:
            raise ValueError("Unexpected Gradle Module Metadata coordinates")
        dependency_variants = 0
        for variant in metadata.get("variants", []):
            attributes = variant.get("attributes", {})
            usage = attributes.get("org.gradle.usage")
            library = usage in ("java-api", "java-runtime") and attributes.get("org.gradle.category") != "documentation"
            actual = [(item.get("group"), item.get("module"), item.get("version", {}).get("requires")) for item in variant.get("dependencies", [])]
            expected_module = [(GROUP, "protocol", version)] if module == "sdk" and library else []
            if actual != expected_module:
                raise ValueError("Gradle Module Metadata has an incorrect transitive dependency")
            if library:
                dependency_variants += 1
        if dependency_variants != 2:
            raise ValueError("Publication must include Java API and runtime GMM variants")
        with zipfile.ZipFile(directory / f"{module}-{version}.aar") as archive:
            if not {"AndroidManifest.xml", "classes.jar"}.issubset(archive.namelist()) or archive.testzip():
                raise ValueError("Invalid published AAR")
        with zipfile.ZipFile(directory / f"{module}-{version}-sources.jar") as archive:
            if not any(name.endswith((".java", ".aidl")) for name in archive.namelist()) or archive.testzip():
                raise ValueError("Published sources JAR is empty or invalid")
    return result






def stage_dist(channel: str) -> None:
    dist = ROOT / "dist"
    source = dist / "build-manifest.json"
    if source.is_symlink():
        raise ValueError("Distribution manifest must be a regular file")
    manifest = json.loads(source.read_text(encoding="utf-8"))
    variants = manifest.get("managerVariants", {})
    expected_variants = {"debug", "release"} if channel == "both" else {channel}
    if set(variants) != expected_variants or manifest.get("unsignedManagerApks") != []:
        raise ValueError("Distribution manifest does not match the intended Debug/signed-Release channel")
    for variant, item in variants.items():
        if not item.get("signature", {}).get("verified"):
            raise ValueError("Unverified APK in distribution manifest")
        if variant == "release" and (item.get("signingIdentityRole") != "production" or
                                      item.get("signature", {}).get("certificateSha256") != manifest.get("expectedReleaseCertificateSha256") or
                                      item.get("r8", {}).get("minifyEnabled") is not True or item.get("r8", {}).get("shrinkResources") is not True):
            raise ValueError("Release distribution must use the verified production identity and R8")
    version = manifest.get("version", "")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", version):
        raise ValueError("Unsafe module version in manifest")
    allowed = {f"spectrumapi-{version}-arm64-v8a.zip"}
    for variant in expected_variants:
        allowed |= {f"spectrum-{module}-{variant}.aar" for module in ("sdk", "protocol")}
        allowed.add(f"spectrum-manager-{variant}.apk")
    mapping_names = {"spectrum-manager-release-mapping.txt", "spectrum-manager-release-r8-configuration.txt"} if "release" in expected_variants else set()
    allowed |= mapping_names
    artifacts = manifest.get("artifacts", [])
    names = [item.get("path", "") for item in artifacts]
    if len(names) != len(set(names)) or set(names) != allowed or set(manifest.get("distributionFiles", [])) != allowed | {"build-manifest.json"}:
        raise ValueError("Distribution files violate the explicit invocation allowlist")
    stage = ROOT / "build" / "ci-upload"
    if stage.is_symlink():
        raise ValueError("CI upload stage cannot be a symlink")
    if stage.exists():
        shutil.rmtree(stage)
    for kind in ("artifacts", "mapping"):
        (stage / kind).mkdir(parents=True)
    for item in artifacts:
        path = dist / item["path"]
        if path.is_symlink() or not path.is_file() or path.stat().st_size != item["bytes"] or hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            raise ValueError("Distribution artifact is missing, changed or symlinked")
        shutil.copyfile(path, stage / ("mapping" if path.name in mapping_names else "artifacts") / path.name)
    shutil.copyfile(source, stage / "artifacts" / source.name)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    sdk = sub.add_parser("prepare-sdk")
    sdk.add_argument("--sdk-only", action="store_true")
    sub.add_parser("restore-key")
    sub.add_parser("cleanup-key")
    stage = sub.add_parser("stage-dist")
    stage.add_argument("--channel", choices=("debug", "release", "both"), required=True)
    args = parser.parse_args()
    try:
        if args.action == "prepare-sdk": prepare_sdk(args.sdk_only)
        elif args.action == "restore-key": restore_key()
        elif args.action == "cleanup-key": cleanup_key()
        elif args.action == "stage-dist": stage_dist(args.channel)
        return 0
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"CI helper failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
