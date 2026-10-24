#!/usr/bin/env python3
"""Create, check and reproducibly package native GreenMarine raster shaderpacks."""
import argparse
import json
import re
import sys
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/greenmarine'
ID = re.compile(r'[a-z0-9_.-]+:[a-z0-9_./-]+\Z')


def resource(pack, name):
    if not isinstance(name, str) or not ID.fullmatch(name) or '..' in name or ':/' in name or name.endswith('/'):
        raise ValueError(f'Invalid resource id: {name}')
    namespace, path = name.split(':', 1)
    return pack / 'assets' / namespace / path


def init(pack, name):
    resource(pack, name)
    if pack.exists():
        raise ValueError(f'Destination already exists: {pack}')
    namespace, path = name.split(':', 1)
    manifest = json.loads((ASSETS / 'greenmarine/packs/raster.json').read_text())
    manifest['id'] = name
    manifest['name'] = path.rsplit('/', 1)[-1].replace('_', ' ').title()
    files = {'terrain.vsh': 'seabreeze_terrain.vsh', 'terrain.fsh': 'raster_terrain.fsh',
             'sky.fsh': 'seabreeze_sky.fsh', 'clouds.fsh': 'seabreeze_clouds.fsh'}
    for program in manifest['programs']:
        target = program['target']
        program['fragment'] = f'{namespace}:core/{target}'
        if 'vertex' in program:
            program['vertex'] = f'{namespace}:core/{target}'
    # Prepare inputs before creating the destination so missing SDK resources leave no partial pack.
    payloads = {f'assets/{namespace}/shaders/core/{target}': (ASSETS / 'shaders/core' / source).read_bytes()
                for target, source in files.items()}
    payloads[f'assets/{namespace}/greenmarine/packs/{path}.json'] = (json.dumps(manifest, indent=2) + '\n').encode()
    payloads['README.txt'] = (f'{name}\nEdit assets/{namespace}/shaders/core and the manifest.\n'
        'Engine and game includes are supplied by GreenMarine and Minecraft.\n'
        'Put this directory in shaderpacks/greenmarine, then reload resources (F3+T).\n'
        'Custom terrain shaders require separate renderer compatibility validation.\n').encode()
    pack.mkdir(parents=True, exist_ok=False)
    for relative, data in payloads.items():
        output = pack / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data)


def validate(pack):
    """Check local authoring mistakes; runtime catalog and GPU compiler remain authoritative."""
    if not pack.is_dir() or pack.is_symlink():
        raise ValueError(f'Expected a pack directory: {pack}')
    files = sorted(pack.rglob('*'))
    if len(files) > 2048:
        raise ValueError('Pack file limit exceeded')
    total = 0
    for file in files:
        if file.is_symlink():
            raise ValueError(f'Linked paths are unsupported: {file}')
        if file.is_file() and file.relative_to(pack).parts[0] == 'assets':
            limit = 4 * 1024 * 1024 if file.suffix == '.spv' else 262144
            if '/greenmarine/packs/' in file.as_posix() or file.suffix == '.lua':
                limit = 65536
            if file.stat().st_size > limit:
                raise ValueError(f'Resource exceeds {limit} bytes: {file}')
            total += file.stat().st_size
    if total > 8 * 1024 * 1024:
        raise ValueError('Pack snapshot exceeds 8 MiB')
    manifests = sorted(pack.glob('assets/*/greenmarine/packs/**/*.json'))
    if not manifests:
        raise ValueError('No assets/<namespace>/greenmarine/packs/*.json manifest')
    ids = set()
    for file in manifests:
        data = json.loads(file.read_text())
        if not isinstance(data, dict):
            raise ValueError(f'{file}: expected a manifest object')
        if type(data.get('schema')) is not int or data['schema'] != 1:
            raise ValueError(f'{file}: expected schema 1')
        if not isinstance(data.get('passes'), list):
            raise ValueError(f'{file}: passes must be an array')
        if not isinstance(data.get('programs', []), list) or not isinstance(data.get('parameters', {}), dict):
            raise ValueError(f'{file}: programs must be an array and parameters an object')
        if not all(isinstance(item, dict) for item in data['passes'] + data.get('programs', [])):
            raise ValueError(f'{file}: pass and program entries must be objects')
        resource(pack, data.get('id'))
        if data['id'] in ids:
            raise ValueError(f'Duplicate pack id: {data["id"]}')
        ids.add(data['id'])
        computes = [p for p in data['passes'] if p.get('stage') == 'compute']
        if len(data.get('parameters', {})) > 32 or len(data['passes']) - len(computes) > 8 or len(computes) > 16:
            raise ValueError(f'{file}: parameter/pass limit exceeded')
        required = []
        for program in data.get('programs', []):
            required.append(('shaders/', program['fragment'], '.fsh'))
            if program.get('vertex'):
                required.append(('shaders/', program['vertex'], '.vsh'))
            if program.get('surface'):
                required.append(('shaders/', program['surface'], '.fsh'))
        for effect in data.get('passes', []):
            if effect.get('stage') == 'compute':
                if 'compute' not in effect or 'groups' not in effect:
                    raise ValueError(f'{file}: compute pass {effect.get("name")} needs compute and groups')
                required.append(('shaders/', effect['compute'], '.csh'))
                continue
            required.append(('shaders/', effect['fragment'], '.fsh'))
            if effect.get('fallback'):
                required.append(('shaders/', effect['fallback'], '.fsh'))
            if effect.get('spirv'):
                required.append(('', effect['spirv'], ''))
        if data.get('script'):
            required.append(('', data['script'], ''))
        for prefix, name, suffix in required:
            resource(pack, name)
            namespace, path = name.split(':', 1)
            target = resource(pack, f'{namespace}:{prefix}{path}{suffix}')
            if not target.is_file():
                raise ValueError(f'Missing local resource: {target}')
    return files


def package(pack, output):
    files = validate(pack)
    # Output is separate from the source tree, avoiding accidental self-inclusion on subsequent builds.
    if output.resolve().is_relative_to(pack.resolve()):
        raise ValueError('ZIP destination must be outside the source directory')
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, 'x', compression=ZIP_DEFLATED) as archive:
        for file in files:
            if not file.is_file():
                continue
            entry = ZipInfo(file.relative_to(pack).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, file.read_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    create = commands.add_parser('init', help='Create an editable raster starter')
    create.add_argument('directory', type=Path)
    create.add_argument('--id', required=True)
    check = commands.add_parser('validate', help='Check local files; does not compile GPU programs')
    check.add_argument('directory', type=Path)
    build = commands.add_parser('package', help='Validate and produce a deterministic ZIP (new output only)')
    build.add_argument('directory', type=Path)
    build.add_argument('output', type=Path)
    args = parser.parse_args()
    try:
        if args.command == 'init':
            init(args.directory, args.id)
        elif args.command == 'validate':
            validate(args.directory)
        else:
            package(args.directory, args.output)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f'Pack error: {error}', file=sys.stderr)
        return 1
    print(f'{args.command}: {args.directory}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
