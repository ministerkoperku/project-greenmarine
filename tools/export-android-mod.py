#!/usr/bin/env python3
"""Verify the bundled Android native ELF before exporting a GreenMarine distribution."""
import argparse
import hashlib
import json
import os
import struct
import sys
import tempfile
from pathlib import Path
from zipfile import ZipFile, BadZipFile

NATIVE = 'native/android-aarch64/libgreenmarine_engine.so'


def verify(jar):
    with ZipFile(jar) as archive:
        if NATIVE not in archive.namelist():
            raise ValueError(f'Missing Android native library: {NATIVE}; rebuild with -PandroidNative=true')
        entry = archive.getinfo(NATIVE)
        if entry.file_size > 16 * 1024 * 1024:
            raise ValueError('Android library exceeds delivery limit')
        elf = archive.read(entry)
        if len(elf) < 64 or elf[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<HH', elf, 16) != (3, 183):
            raise ValueError('Android library must be an AArch64 ELF64 shared object')
        offset = struct.unpack_from('<Q', elf, 40)[0]
        stride, count = struct.unpack_from('<HH', elf, 58)
        if stride != 64 or count < 1 or offset + stride * count > len(elf):
            raise ValueError('Invalid Android ELF section table')
        sections = [struct.unpack_from('<IIQQQQIIQQ', elf, offset + i * stride) for i in range(count)]
        needed, paths = [], []
        for section in sections:
            if section[1] != 6:
                continue
            strings_section = sections[section[6]]
            strings = elf[strings_section[4]:strings_section[4] + strings_section[5]]
            for position in range(section[4], section[4] + section[5], 16):
                tag, value = struct.unpack_from('<qQ', elf, position)
                if tag in (1, 15, 29):
                    name = strings[value:].split(b'\0', 1)[0].decode('utf-8')
                    (needed if tag == 1 else paths).append(name)
        if 'libc.so' not in needed or any('.so.' in name for name in needed) or paths:
            raise ValueError(f'Expected Android/Bionic dependencies without RPATH/RUNPATH: {needed}, {paths}')
        metadata = json.loads(archive.read('fabric.mod.json'))
        if metadata.get('id') != 'greenmarine':
            raise ValueError('Expected a GreenMarine distribution')
        return metadata['version'], hashlib.sha256(elf).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    parser.add_argument('--destination', type=Path, default=Path('/storage/emulated/0/Documents/koper/recents'))
    args = parser.parse_args()
    try:
        version, native_hash = verify(args.jar)
        payload = args.jar.read_bytes()
        target = args.destination / args.jar.name
        if target.exists() and target.read_bytes() != payload:
            raise ValueError(f'Refusing to replace a different existing artifact: {target}')
        args.destination.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            with tempfile.NamedTemporaryFile(dir=args.destination, prefix='.greenmarine-', delete=False) as output:
                temporary = Path(output.name)
                try:
                    output.write(payload)
                    output.flush()
                    os.fsync(output.fileno())
                    output.close()
                    os.replace(temporary, target)
                finally:
                    temporary.unlink(missing_ok=True)
        if hashlib.sha256(target.read_bytes()).digest() != hashlib.sha256(payload).digest():
            raise ValueError('Export checksum mismatch')
        print(f'Exported GreenMarine {version}: {target}\nAndroid native SHA256: {native_hash}')
        return 0
    except (OSError, ValueError, KeyError, IndexError, struct.error, BadZipFile) as error:
        print(f'Android export rejected: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
