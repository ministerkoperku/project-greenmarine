#!/usr/bin/env python3
"""Prepare an isolated development run for the actual-Minecraft LabPBR regression probe."""
import argparse
import json
import struct
import zlib
from pathlib import Path


def png(rgba, hole=False):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    rows = b''.join(b'\0' + b''.join(bytes((0,0,0,0) if hole and x==0 and y==0 else rgba) for x in range(16)) for y in range(16))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>2I5B', 16, 16, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run_directory', type=Path)
    parser.add_argument('--material-addon', action='store_true')
    args = parser.parse_args()
    run = args.run_directory
    marker = run / '.greenmarine-pbr-probe'
    if run.exists() and any(run.iterdir()) and not marker.is_file():
        parser.error('Refusing to change an existing run directory; choose a new directory')
    run.mkdir(parents=True, exist_ok=True)
    marker.write_text('GreenMarine generated LabPBR fixture\n')
    pack = run / 'resourcepacks/pbr-fixture'
    textures = pack / 'assets/minecraft/textures/block'
    textures.mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parents[1]
    (pack / 'pack.mcmeta').write_bytes((root / 'examples/solstice-rt/pack.mcmeta').read_bytes())
    (textures / 'stone_s.png').write_bytes(png((192, 231, 0, 255)))
    (textures / 'stone_n.png').write_bytes(png((128, 128, 255, 255)))
    if args.material_addon:
        (run / '.greenmarine-material-addon-probe').write_text('Generated addon fixture\n')
        material_dir = pack / 'assets/probe/greenmarine/materials'
        mask_dir = pack / 'assets/probe/greenmarine/masks'
        material_dir.mkdir(parents=True, exist_ok=True)
        mask_dir.mkdir(parents=True, exist_ok=True)
        (mask_dir / 'screen.png').write_bytes(png((18,52,86,255), hole=True))
        (material_dir / 'screen.json').write_text(json.dumps({'schema':1,'id':'probe:screen','textures':{
            'minecraft:textures/block/stone.png': {'mask':'probe:greenmarine/masks/screen.png','palette':{
                '123456': {'surface':'coated','roughness':.8,'reflection':.05,'emission':.12}}}}}))
    (run / 'options.txt').write_text('resourcePacks:' + json.dumps(['file/pbr-fixture'])
                                   + '\nmaxFps:30\nrenderDistance:2\n')
    print(f'Prepared isolated LabPBR fixture: {run.resolve()}')


if __name__ == '__main__':
    main()
