"""Small stdlib checks for the release verifier; no APK is built or executed."""
import contextlib
import io
import json
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace
import unittest

import verify_beta2_release as verifier


def elf64(alignment=16384, virtual_address=0, machine=183):
    data = bytearray(256)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HH", data, 16, 3, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, virtual_address, 0, 256, 256, alignment)
    return bytes(data)


class ReleaseVerifierTest(unittest.TestCase):
    def test_elf_16k_and_4k_segments_are_distinguished(self):
        good = verifier.elf_report(io.BytesIO(elf64()), 256, "arm64-v8a")
        self.assertTrue(good["aligned16KiB"])
        old = verifier.elf_report(io.BytesIO(elf64(alignment=4096)), 256, "arm64-v8a")
        self.assertFalse(old["aligned16KiB"])

    def test_invalid_elf_machine_bounds_and_congruence_are_rejected(self):
        for data, size in ((elf64(machine=40), 256), (elf64(), 100), (elf64(virtual_address=4096), 256)):
            with self.assertRaises(ValueError):
                verifier.elf_report(io.BytesIO(data), size, "arm64-v8a")

    def test_elf32_is_reported_without_claiming_16k_alignment(self):
        data = bytearray(128)
        data[:7] = b"\x7fELF\x01\x01\x01"
        struct.pack_into("<HH", data, 16, 3, 40)
        struct.pack_into("<I", data, 28, 52)
        struct.pack_into("<HH", data, 42, 32, 1)
        struct.pack_into("<IIIIIIII", data, 52, 1, 0, 0, 0, 128, 128, 5, 4096)
        report = verifier.elf_report(io.BytesIO(data), 128, "armeabi-v7a")
        self.assertEqual(32, report["class"])
        self.assertFalse(report["aligned16KiB"])

    def test_zip_alignment_uses_local_extra_field_length(self):
        data = bytearray(40)
        data[10:14] = b"PK\x03\x04"
        struct.pack_into("<HH", data, 36, 15, 16329)
        self.assertEqual(16384, verifier.zip_data_offset(io.BytesIO(data), SimpleNamespace(header_offset=10)))

    def test_nested_junit_reports_do_not_double_count(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            path = Path(directory) / "TEST-sample.xml"
            path.write_text('<testsuites tests="3"><testsuite tests="3" failures="1" skipped="1">'
                            '<testcase name="ok"/><testcase name="bad"><failure message="broken"/></testcase>'
                            '<testcase name="skip"><skipped/></testcase></testsuite></testsuites>', encoding="utf-8")
            errors = []
            report = verifier.junit_report([path], errors)
            self.assertEqual((3, 1, 1, 1), (report["tests"], report["passed"], report["failures"], report["skipped"]))
            self.assertIn("failures=1", errors[0])

    def test_junit_runner_error_without_testcase_is_not_lost(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            path = Path(directory) / "TEST-error.xml"
            path.write_text('<testsuite tests="0" errors="1"/>', encoding="utf-8")
            errors = []
            report = verifier.junit_report([path], errors)
            self.assertEqual(1, report["errors"])
            self.assertTrue(errors)

    def test_missing_inputs_and_invalid_expected_hash_produce_failed_json(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            root = Path(directory)
            output = root / "report.json"
            with contextlib.redirect_stdout(io.StringIO()):
                code = verifier.main(["--apk", str(root / "missing.apk"), "--tests", str(root / "missing.xml"),
                                      "--metadata", str(root / "missing.json"), "--expected-stem-sha256", "bad",
                                      "--output", str(output)])
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(1, code)
            self.assertFalse(report["ok"])
            self.assertFalse(report["signatureVerified"])
            self.assertTrue(any("SHA-256" in error for error in report["errors"]))
            self.assertTrue(any("No .apk" in error for error in report["errors"]))

    def test_metadata_version_mismatch_is_reported(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as directory:
            root = Path(directory)
            path = root / "output-metadata.json"
            path.write_text(json.dumps({"applicationId": "com.theveloper.pixelplay", "elements": [
                {"outputFile": "missing.apk", "versionName": "old", "versionCode": 1}]}), encoding="utf-8")
            errors = []
            verifier.metadata_report([path], [], "new", 2, "com.theveloper.pixelplay", errors)
            self.assertTrue(any("versionName" in error for error in errors))
            self.assertTrue(any("versionCode" in error for error in errors))
            self.assertTrue(any("does not exist" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
