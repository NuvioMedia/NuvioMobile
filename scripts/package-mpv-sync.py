#!/usr/bin/env python3
"""Replace only four libmpv.so entries in checksum-verified upstream AAR."""
import hashlib
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ORIGINAL_SHA256 = "bb1a007c545cc7ac3304293ae79866b5361a48449ee7648c4030d5355869effc"
URL = ("https://repo.maven.apache.org/maven2/io/github/abdallahmehiz/"
       "mpv-android-lib/0.1.12/mpv-android-lib-0.1.12.aar")
ARCHES = {"arm64-v8a": "arm64", "armeabi-v7a": "armv7l", "x86": "x86", "x86_64": "x86_64"}
ELF_ABIS = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86": (1, 3), "x86_64": (2, 62)}
build = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2]).resolve()
with tempfile.TemporaryDirectory() as directory:
    temporary = Path(directory)
    original = Path(sys.argv[3]).resolve() if len(sys.argv) > 3 and sys.argv[3] else temporary / "original.aar"
    if original == temporary / "original.aar":
        urllib.request.urlretrieve(URL, original)
    if hashlib.sha256(original.read_bytes()).hexdigest() != ORIGINAL_SHA256:
        raise RuntimeError("Upstream AAR checksum mismatch")
    replacements = {}
    toolchains = list((build / "sdk/android-ndk-r29/toolchains/llvm/prebuilt").glob("linux-*"))
    if len(toolchains) != 1:
        raise RuntimeError("Expected exactly one Linux NDK toolchain")
    strip = toolchains[0] / "bin/llvm-strip"
    for abi, arch in ARCHES.items():
        library = build / "prefix" / arch / "lib/libmpv.so"
        target = temporary / f"{abi}.so"
        subprocess.run([str(strip), "--strip-unneeded", str(library), "-o", str(target)], check=True)
        data = target.read_bytes()
        if (data[:4] != b"\x7fELF" or data[5] != 1
                or (data[4], struct.unpack_from("<H", data, 18)[0]) != ELF_ABIS[abi]
                or struct.unpack_from("<H", data, 16)[0] != 3):
            raise RuntimeError(f"Expected Android shared library for {abi}")
        replacements[f"jni/{abi}/libmpv.so"] = data
    staged = temporary / "patched.aar"
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(staged, "w") as after:
        missing = replacements.keys() - set(before.namelist())
        if missing:
            raise RuntimeError(f"Upstream AAR missing entries: {missing}")
        for entry in before.infolist():
            data = replacements[entry.filename] if entry.filename in replacements else before.read(entry)
            after.writestr(entry, data)
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(staged) as after:
        changed = {name for name in before.namelist() if before.read(name) != after.read(name)}
        if changed != replacements.keys() or before.namelist() != after.namelist():
            raise RuntimeError(f"Unexpected AAR changes: {changed}")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(staged.read_bytes())
print(f"AAR SHA256 {hashlib.sha256(output.read_bytes()).hexdigest()}")
print("Changed only: " + ", ".join(sorted(replacements)))
