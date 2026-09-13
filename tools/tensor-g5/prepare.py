#!/usr/bin/env python3
"""Prepare an isolated Linux LiteRT 2.2.0 compiler environment; never installs system packages."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request
import venv

ROOT = Path(__file__).resolve().parent
VERSION = "2.2.0"
PUBLIC_SDK_URL = "https://redirector.gvt1.com/edgedl/tensor-ml-sdk/sdk/releases/6.1.0/litert_plugin_compiler.tar.gz"
COMPILER = "liblitert_plugin_compiler.so"


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def compiler_directory(path):
    path = Path(path).resolve()
    candidates = [path] if path.is_file() and path.name == COMPILER else list(path.rglob(COMPILER))
    if len(candidates) != 1:
        raise ValueError(f"Expected exactly one {COMPILER} in {path}; found {len(candidates)}")
    compiler = candidates[0]
    with compiler.open("rb") as stream:
        header = stream.read(20)
    if len(header) < 20 or header[:6] != b"\x7fELF\x02\x01" or header[18:20] != b"\x3e\x00":
        raise ValueError("Vendor compiler must be a Linux x86_64 ELF shared library")
    return compiler.parent


def extract_sdk(archive, destination):
    """Extract only regular files/directories; reject traversal and archive links."""
    destination = Path(destination).resolve()
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        for member in members:
            candidate = (destination / member.name).resolve()
            if not candidate.is_relative_to(destination) or not (member.isfile() or member.isdir()):
                raise ValueError(f"Unsafe or unsupported SDK archive member: {member.name}")
        for member in members:
            candidate = destination / member.name
            if member.isdir():
                candidate.mkdir(parents=True, exist_ok=True)
            else:
                candidate.parent.mkdir(parents=True, exist_ok=True)
                with tar.extractfile(member) as source, candidate.open("wb") as target:
                    shutil.copyfileobj(source, target)
                candidate.chmod(0o755 if member.mode & 0o111 else 0o644)


def download_sdk(destination):
    try:
        with urllib.request.urlopen(PUBLIC_SDK_URL, timeout=30) as source, destination.open("wb") as output:
            shutil.copyfileobj(source, output)
    except urllib.error.HTTPError as error:
        raise RuntimeError(
            f"Google's public compiler download returned HTTP {error.code}. "
            "Use --sdk with an SDK archive supplied by Google. The public 6.1.0 URL "
            "returned 404 when checked on 2026-09-07; LiteRT alone cannot compile for Tensor G5."
        ) from error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--sdk", type=Path, help="Google-provided .tar.gz, extracted SDK directory, or compiler .so")
    source.add_argument("--download-public", action="store_true", help="Try the URL in Google's current official installer")
    parser.add_argument("--sdk-version", help="Compiler version supplied by Google; never inferred from LiteRT's version")
    args = parser.parse_args()
    if sys.platform != "linux" or platform.machine() != "x86_64":
        raise RuntimeError("Google's AOT compiler requires Linux x86_64. Run this script inside WSL Ubuntu.")

    archive_hash = None
    if args.download_public:
        with tempfile.TemporaryDirectory(prefix="sdk-download-", dir=ROOT) as temporary:
            archive = Path(temporary) / "litert_plugin_compiler.tar.gz"
            download_sdk(archive)
            archive_hash = sha256(archive)
            sdk_root = ROOT / "sdk" / archive_hash
            extract_sdk(archive, sdk_root)
        sdk_dir = compiler_directory(sdk_root)
    elif args.sdk.is_file() and args.sdk.name.endswith((".tar.gz", ".tgz")):
        archive_hash = sha256(args.sdk)
        sdk_root = ROOT / "sdk" / archive_hash
        extract_sdk(args.sdk, sdk_root)
        sdk_dir = compiler_directory(sdk_root)
    else:
        sdk_dir = compiler_directory(args.sdk)

    executable = ROOT / ".venv/bin/python"
    if not executable.exists():
        venv.EnvBuilder(with_pip=True).create(ROOT / ".venv")
    subprocess.run([str(executable), "-m", "pip", "install", "--disable-pip-version-check",
                    f"ai-edge-litert=={VERSION}"], check=True)
    settings = {"litertVersion": VERSION, "sdkDir": str(sdk_dir),
                "compilerVersion": args.sdk_version, "sdkArchiveSha256": archive_hash,
                "compilerLibrarySha256": sha256(sdk_dir / COMPILER)}
    settings_path = ROOT / "environment.json"
    settings_path.write_text(json.dumps(settings, indent=2) + "\n")
    print(f"Prepared {settings_path}. Run .venv/bin/python compile_model.py from this directory.")
    print("Compilation and phone validation have not run.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print(f"Preparation failed: {error}", file=sys.stderr)
        sys.exit(1)
