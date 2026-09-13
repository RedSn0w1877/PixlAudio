#!/usr/bin/env python3
"""Read-only release checks; APK signature/manifest verification still requires Android tools.

Example (run after Gradle finishes):
  python tools/verify_beta2_release.py --apk app/build/outputs/apk/release \
    --tests app/build/test-results/testDebugUnitTest \
    --metadata app/build/outputs/apk/release/output-metadata.json \
    --expected-version-name 0.7.6-beta2 --expected-version-code 13 \
    --output build/beta2-release-verification.json

Only the requested JSON report is written. No APK, device, build, or signing command is run.
"""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile


PAGE_SIZE = 16384
STEM_ASSET = "assets/tais/stem_separation.tflite"
STEM_SHA256 = "5ef47e3b3bafa14357532c0a3f6c5f18444d94b6efe3fd62b3d13f80051f1e58"
STEM_BYTES = 66848828
REQUIRED_ASSETS = [STEM_ASSET, "assets/tais/wav2vec2_base_960h_fp32.onnx", "assets/tais/wav2vec2_vocab.json"]
ABI_MACHINES = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62), "x86": (1, 3)}


def collect_paths(inputs, suffix, recursive, errors):
    found = set()
    for value in inputs:
        path = Path(value).resolve()
        if path.is_file() and path.suffix.lower() == suffix:
            found.add(path)
        elif path.is_dir():
            found.update(p.resolve() for p in (path.rglob("*" + suffix) if recursive else path.glob("*" + suffix)) if p.is_file())
        else:
            errors.append(f"Missing or invalid {suffix} input: {path}")
    if not found:
        errors.append(f"No {suffix} files found")
    return sorted(found)


def junit_report(paths, errors):
    totals = Counter(tests=0, failures=0, errors=0, skipped=0)
    reports = []
    for path in paths:
        try:
            root = ET.parse(path).getroot()
            if root.tag not in ("testsuite", "testsuites"):
                raise ValueError(f"Unexpected JUnit root {root.tag}")
            cases = list(root.iter("testcase"))
            counts = Counter(tests=len(cases), failures=0, errors=0, skipped=0)
            failed = []
            for case in cases:
                for tag, key in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
                    detail = case.find(tag)
                    if detail is not None:
                        counts[key] += 1
                        if tag != "skipped":
                            failed.append({"class": case.get("classname"), "name": case.get("name"),
                                           "kind": tag, "message": detail.get("message", "")[:2000]})
            # Leaf suite declarations catch runner failures with no testcase and truncated reports.
            for suite in root.iter("testsuite"):
                if suite.find("testsuite") is not None:
                    continue
                local_cases = suite.findall("testcase")
                if suite.get("tests") is not None and int(suite.get("tests")) != len(local_cases):
                    raise ValueError("Declared test count differs from recorded testcase count")
                for key, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                    declared = int(suite.get(key, "0"))
                    observed = sum(case.find(tag) is not None for case in local_cases)
                    if declared > observed:
                        counts[key] += declared - observed
            totals.update(counts)
            reports.append({"path": str(path), **counts, "failedCases": failed})
        except (OSError, ValueError, ET.ParseError) as exc:
            errors.append(f"Invalid JUnit report {path}: {exc}")
    if totals["failures"] or totals["errors"]:
        errors.append(f"JUnit failures={totals['failures']}, errors={totals['errors']}")
    return {**totals, "passed": max(0, totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]),
            "reportCount": len(reports), "reports": reports}


def elf_report(stream, file_size, abi):
    header = stream.read(64)
    if len(header) < 52 or header[:4] != b"\x7fELF" or header[4] not in (1, 2) or header[5] not in (1, 2):
        raise ValueError("Invalid ELF header")
    elf_class = header[4]
    endian = "<" if header[5] == 1 else ">"
    machine = struct.unpack_from(endian + "H", header, 18)[0]
    if abi in ABI_MACHINES and (elf_class, machine) != ABI_MACHINES[abi]:
        raise ValueError(f"ELF class/machine does not match ABI {abi}")
    if elf_class == 2:
        if len(header) < 64:
            raise ValueError("Truncated ELF64 header")
        ph_offset = struct.unpack_from(endian + "Q", header, 32)[0]
        ph_size, ph_count = struct.unpack_from(endian + "HH", header, 54)
        minimum = 56
    else:
        ph_offset = struct.unpack_from(endian + "I", header, 28)[0]
        ph_size, ph_count = struct.unpack_from(endian + "HH", header, 42)
        minimum = 32
    end = ph_offset + ph_size * ph_count
    if ph_size < minimum or ph_count == 0 or end > file_size or end > 16 * 1024 * 1024:
        raise ValueError("Invalid or unsupported ELF program-header table")
    stream.seek(ph_offset)
    table = stream.read(ph_size * ph_count)
    if len(table) != ph_size * ph_count:
        raise ValueError("Truncated ELF program-header table")
    segments = []
    for index in range(ph_count):
        values = struct.unpack_from(endian + ("IIQQQQQQ" if elf_class == 2 else "IIIIIIII"), table, index * ph_size)
        if values[0] != 1:  # PT_LOAD
            continue
        if elf_class == 2:
            _, _, offset, address, _, size, memory_size, alignment = values
        else:
            _, offset, address, _, size, memory_size, _, alignment = values
        if offset + size > file_size or size > memory_size:
            raise ValueError("ELF load segment exceeds file or memory bounds")
        if alignment > 1 and (alignment & (alignment - 1) or offset % alignment != address % alignment):
            raise ValueError("Invalid ELF load-segment alignment")
        segments.append({"offset": offset, "virtualAddress": address, "alignment": alignment,
                         "aligned16KiB": alignment >= PAGE_SIZE and offset % PAGE_SIZE == address % PAGE_SIZE})
    if not segments:
        raise ValueError("ELF contains no PT_LOAD segments")
    return {"class": 64 if elf_class == 2 else 32, "machine": machine, "loadSegments": segments,
            "aligned16KiB": all(segment["aligned16KiB"] for segment in segments)}


def zip_data_offset(apk_stream, entry):
    apk_stream.seek(entry.header_offset)
    header = apk_stream.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise ValueError("Invalid ZIP local file header")
    filename_size, extra_size = struct.unpack_from("<HH", header, 26)
    return entry.header_offset + 30 + filename_size + extra_size


def apk_report(path, required_assets, expected_hash, required_abis, errors):
    report = {"path": str(path), "bytes": path.stat().st_size, "nativeLibraries": [], "assets": []}
    try:
        with zipfile.ZipFile(path) as archive, path.open("rb") as apk_stream:
            names = archive.namelist()
            if len(names) != len(set(names)):
                raise ValueError("Duplicate APK ZIP entries")
            inventory = {entry.filename: entry for entry in archive.infolist()}
            for name in required_assets:
                if name not in inventory or inventory[name].file_size <= 0:
                    errors.append(f"{path.name}: required asset missing or empty: {name}")
            report["assets"] = [{"path": name, "bytes": item.file_size, "compression": item.compress_type}
                                for name, item in sorted(inventory.items()) if name.startswith("assets/") and not item.is_dir()]
            for name in (STEM_ASSET, "assets/tais/wav2vec2_base_960h_fp32.onnx"):
                if name in inventory and inventory[name].compress_type != zipfile.ZIP_STORED:
                    errors.append(f"{path.name}: mmap model asset is compressed: {name}")
            if STEM_ASSET in inventory:
                with archive.open(STEM_ASSET) as model:
                    digest = hashlib.file_digest(model, "sha256").hexdigest()
                report["stemModel"] = {"sha256": digest, "bytes": inventory[STEM_ASSET].file_size,
                                       "matchesExpected": digest == expected_hash and inventory[STEM_ASSET].file_size == STEM_BYTES}
                if not report["stemModel"]["matchesExpected"]:
                    errors.append(f"{path.name}: stem model identity changed (SHA-256 {digest}, bytes {inventory[STEM_ASSET].file_size})")
            for name, entry in sorted(inventory.items()):
                match = re.fullmatch(r"lib/([^/]+)/[^/]+\.so", name)
                if not match:
                    continue
                abi = match.group(1)
                library = {"path": name, "abi": abi, "bytes": entry.file_size,
                           "compression": entry.compress_type, "requires16KiB": abi in required_abis}
                try:
                    offset = zip_data_offset(apk_stream, entry)
                    library.update(zipDataOffset=offset, zipDataAligned16KiB=offset % PAGE_SIZE == 0,
                                   zipAlignmentRequired=entry.compress_type == zipfile.ZIP_STORED and abi in required_abis)
                    if library["zipAlignmentRequired"] and not library["zipDataAligned16KiB"]:
                        errors.append(f"{path.name}: uncompressed native ZIP entry is not 16 KiB aligned: {name}")
                    with archive.open(entry) as stream:
                        library["elf"] = elf_report(stream, entry.file_size, abi)
                    if abi in required_abis and not library["elf"]["aligned16KiB"]:
                        errors.append(f"{path.name}: ELF PT_LOAD is not 16 KiB aligned: {name}")
                except (OSError, ValueError, struct.error, zipfile.BadZipFile) as exc:
                    library["error"] = str(exc)
                    errors.append(f"{path.name}: invalid native library {name}: {exc}")
                report["nativeLibraries"].append(library)
            if not report["nativeLibraries"]:
                errors.append(f"{path.name}: no native libraries found")
            report["abis"] = sorted({library["abi"] for library in report["nativeLibraries"]})
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as exc:
        errors.append(f"Cannot verify APK {path}: {exc}")
    return report


def metadata_report(paths, apks, expected_name, expected_code, expected_id, errors):
    reports = []
    covered = set()
    for value in paths:
        path = Path(value).resolve()
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(data, dict) or not isinstance(data.get("elements"), list) or not data["elements"]:
                raise ValueError("Missing APK metadata elements")
            if expected_id is not None and data.get("applicationId") != expected_id:
                errors.append(f"{path}: unexpected applicationId {data.get('applicationId')!r}")
            elements = []
            for element in data["elements"]:
                name, code = element.get("versionName"), element.get("versionCode")
                if not isinstance(name, str) or not name or type(code) is not int or code <= 0:
                    raise ValueError("Missing or invalid versionName/versionCode")
                if expected_name is not None and name != expected_name:
                    errors.append(f"{path}: expected versionName {expected_name!r}, got {name!r}")
                if expected_code is not None and code != expected_code:
                    errors.append(f"{path}: expected versionCode {expected_code}, got {code}")
                filename = element.get("outputFile")
                if not isinstance(filename, str) or not filename:
                    raise ValueError("Missing APK outputFile")
                output = (path.parent / filename).resolve()
                if not output.is_file():
                    errors.append(f"{path}: metadata APK does not exist: {output}")
                covered.add(output)
                elements.append({"outputFile": str(output), "versionName": name, "versionCode": code,
                                 "filters": element.get("filters", [])})
            reports.append({"path": str(path), "applicationId": data.get("applicationId"), "elements": elements})
        except (OSError, ValueError, TypeError, AttributeError) as exc:
            errors.append(f"Invalid output metadata {path}: {exc}")
    for path in apks:
        if path not in covered:
            errors.append(f"No output-metadata entry for APK {path}")
    return reports


def write_report(path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n", dir=path.parent,
                                         prefix=path.name + ".", suffix=".tmp", delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(report, stream, indent=2, ensure_ascii=False)
            stream.write("\n")
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk", action="append", required=True, help="APK file or directory; repeatable")
    parser.add_argument("--tests", action="append", required=True, help="JUnit XML file or recursive report directory; repeatable")
    parser.add_argument("--metadata", action="append", required=True, help="AGP output-metadata.json; repeatable")
    parser.add_argument("--output", type=Path, required=True, help="JSON report destination")
    parser.add_argument("--expected-version-name")
    parser.add_argument("--expected-version-code", type=int)
    parser.add_argument("--expected-application-id", default="com.theveloper.pixelplay")
    parser.add_argument("--expected-stem-sha256", default=STEM_SHA256)
    parser.add_argument("--required-asset", action="append", default=[], help="Additional exact APK entry name")
    parser.add_argument("--require-16k-abi", action="append", help="Override ABIs requiring 16 KiB; default arm64-v8a,x86_64")
    parser.add_argument("--min-tests", type=int, default=1)
    args = parser.parse_args(argv)
    errors = []
    expected_hash = args.expected_stem_sha256.lower()
    if re.fullmatch(r"[0-9a-f]{64}", expected_hash) is None:
        errors.append("Expected stem SHA-256 must contain exactly 64 hexadecimal characters")
    if args.min_tests < 1:
        errors.append("--min-tests must be positive")
    if args.expected_version_code is not None and args.expected_version_code < 1:
        errors.append("--expected-version-code must be positive")
    apks = collect_paths(args.apk, ".apk", False, errors)
    test_paths = collect_paths(args.tests, ".xml", True, errors)
    tests = junit_report(test_paths, errors)
    protected = set(apks + test_paths + [Path(path).resolve() for path in args.metadata])
    if args.output.resolve() in protected:
        print(json.dumps({"ok": False, "errors": ["JSON output must not overwrite an APK, test report, or metadata input"]}))
        return 2
    if tests["tests"] < args.min_tests:
        errors.append(f"Only {tests['tests']} tests recorded; expected at least {args.min_tests}")
    abis = set(args.require_16k_abi or ["arm64-v8a", "x86_64"])
    assets = list(dict.fromkeys(REQUIRED_ASSETS + args.required_asset))
    report = {"generatedAtUtc": datetime.now(timezone.utc).isoformat(), "signatureVerified": False,
              "scope": "JUnit XML, ZIP/ELF alignment, model identity, required assets, AGP output metadata; no device execution",
              "expected": {"stemSha256": expected_hash, "stemBytes": STEM_BYTES, "requiredAssets": assets,
                           "require16KiBAbis": sorted(abis), "versionName": args.expected_version_name,
                           "versionCode": args.expected_version_code, "applicationId": args.expected_application_id},
              "tests": tests,
              "apks": [apk_report(path, assets, expected_hash, abis, errors) for path in apks],
              "metadata": metadata_report(args.metadata, apks, args.expected_version_name,
                                           args.expected_version_code, args.expected_application_id, errors)}
    report.update(ok=not errors, errors=errors)
    try:
        write_report(args.output.resolve(), report)
    except OSError as exc:
        print(f"Cannot write verification report: {exc}", file=sys.stderr)
        return 2
    print(json.dumps({"ok": report["ok"], "tests": tests["tests"], "failures": tests["failures"],
                      "errors": errors, "apkCount": len(apks), "report": str(args.output.resolve())}))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())

