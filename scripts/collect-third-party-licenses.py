#!/usr/bin/env python3
"""Collect unchanged upstream notices and reviewed standard license texts for runtime artifacts.

Maven owns artifact resolution. This build tool never downloads data or guesses a license:
unknown declarations, absent artifact/POM files and changed reviewed texts fail the release.
POM multi-license declarations are retained, not silently interpreted as AND or OR.
"""
import argparse
import hashlib
import json
import re
import shutil
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
LEGAL_FILE = re.compile(r"^(?:license|licence|notice|copying|copyright)(?:$|[._-])", re.I)
MAX_NOTICE_BYTES = 4 * 1024 * 1024
OUTPUT_MARKER = '.license-collection-output'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def pom_licenses(repository, group, artifact, version, visited=()):
    coordinate = (group, artifact, version)
    if coordinate in visited:
        raise ValueError("POM parent cycle: " + ":".join(coordinate))
    relative = Path(*group.split('.')) / artifact / version / f'{artifact}-{version}.pom'
    path = repository / relative
    root = ET.parse(path).getroot()
    prefix = '{http://maven.apache.org/POM/4.0.0}' if root.tag.startswith('{') else ''
    tag = lambda name: prefix + name
    declarations = [
        {'name': node.findtext(tag('name'), ''), 'url': node.findtext(tag('url'), '')}
        for node in root.findall(tag('licenses') + '/' + tag('license'))
    ]
    if declarations:
        return declarations, relative.as_posix()
    parent = root.find(tag('parent'))
    if parent is not None:
        values = [parent.findtext(tag(name), '') for name in ('groupId', 'artifactId', 'version')]
        if all(values) and not any('${' in value for value in values):
            return pom_licenses(repository, *values, visited + (coordinate,))
    raise ValueError('No license declaration in POM/parents: ' + ':'.join(coordinate))


def resolved_artifacts(path):
    """Parse the pinned maven-dependency-plugin list output, including native classifiers."""
    artifacts = []
    for line in path.read_text(encoding='utf-8').splitlines():
        text = line.strip()
        if not re.match(r'^[\w.-]+:[\w.-]+:', text):
            continue
        # The plugin may append Java module names; split only after the resolved path.
        match = re.match(r'^(.*?):(compile|runtime):(.+?)(?: -- module .*|$)', text)
        if not match:
            raise ValueError('Unexpected runtime artifact record: ' + text.split(':')[0])
        parts = match.group(1).split(':')
        if len(parts) not in (4, 5):
            raise ValueError('Unexpected coordinate: ' + match.group(1))
        group, artifact, kind = parts[:3]
        classifier, version = ('', parts[3]) if len(parts) == 4 else parts[3:5]
        artifacts.append({'group': group, 'artifact': artifact, 'type': kind,
                          'classifier': classifier, 'version': version,
                          'scope': match.group(2), 'path': Path(match.group(3))})
    return artifacts


def collect_embedded_notices(artifact, destination):
    """Copy bytes verbatim, bounded and path checked; never execute or rewrite upstream content."""
    notices = []
    with zipfile.ZipFile(artifact) as archive:
        for item in sorted(archive.infolist(), key=lambda item: item.filename):
            path = PurePosixPath(item.filename)
            if item.is_dir() or not LEGAL_FILE.match(path.name):
                continue
            if path.suffix.lower() in ('.class', '.jar', '.png', '.svg'):
                continue
            if path.is_absolute() or '..' in path.parts or '\\' in item.filename:
                raise ValueError('Unsafe upstream legal-file path')
            if item.file_size > MAX_NOTICE_BYTES:
                raise ValueError('Upstream legal file exceeds collection limit')
            data = archive.read(item)
            target = destination.joinpath(*path.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            notices.append({'path': item.filename, 'sha256': digest(data)})
    return notices


def collect(dependencies, repository, output, module, sources_path=None):
    sources_path = sources_path or ROOT / 'scripts/license-sources.json'
    sources = json.loads(sources_path.read_text(encoding='utf-8'))
    text_dir = sources_path.parent / 'license-texts'
    # Only marked generated output may be replaced. Refuse repository/source paths
    # even when an accidental CLI argument points at a directory that already exists.
    output = output.resolve()
    protected = [ROOT.resolve(), repository.resolve(), sources_path.resolve(), text_dir.resolve()]
    if any(output == path or output in path.parents for path in protected):
        raise ValueError('Unsafe collection output: ancestor of source/repository')
    if repository.resolve() in output.parents or text_dir.resolve() in output.parents:
        raise ValueError('Unsafe collection output: inside repository/license sources')
    if ROOT.resolve() in output.parents and 'target' not in output.relative_to(ROOT).parts:
        raise ValueError('Collection output inside source tree must be under target')
    if output.exists():
        if not (output / OUTPUT_MARKER).is_file():
            raise ValueError('Refusing to replace unmarked collection output')
        shutil.rmtree(output)
    output.mkdir(parents=True)
    (output / OUTPUT_MARKER).write_text('Generated license material; safe to replace.\n')
    records, missing, used_texts = [], [], set()
    artifacts = resolved_artifacts(dependencies)
    # All current runtime modules have external dependencies. Fail closed if Maven's
    # output format changes instead of silently producing an empty release inventory.
    if not artifacts:
        missing.append({'coordinate': module, 'reason': 'Empty runtime artifact list; check Maven output'})
    for artifact in artifacts:
        coordinate = ':'.join([artifact['group'], artifact['artifact'], artifact['version']]
                              + ([artifact['classifier']] if artifact['classifier'] else []))
        record = {'coordinate': coordinate, 'scope': artifact['scope'],
                  'artifactSha256': None, 'declaredLicenses': [], 'standardTexts': [],
                  'upstreamNoticeFiles': [], 'reviewNotes': []}
        try:
            data = artifact['path'].read_bytes()
            record['artifactSha256'] = digest(data)
            with zipfile.ZipFile(artifact['path']) as archive:
                if 'META-INF/MANIFEST.MF' in archive.namelist():
                    manifest = archive.read('META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n ', '')
                    hint = re.search(r'^Spring-Boot-Jar-Type: (.+)$', manifest, re.M)
                    if hint:
                        record['springBootJarType'] = hint.group(1).strip()
            declarations, provenance = pom_licenses(repository, artifact['group'],
                                                    artifact['artifact'], artifact['version'])
            record['licenseMetadataPom'] = provenance
            record['declaredLicenses'] = declarations
            for declaration in declarations:
                names = sources['aliases'].get(declaration['name'])
                if not names:
                    raise ValueError('Unreviewed license declaration: ' + declaration['name'])
                for name in names:
                    spec = sources['texts'][name]
                    text = (text_dir / spec['file']).read_bytes()
                    if digest(text) != spec['sha256']:
                        raise ValueError('Reviewed license checksum mismatch: ' + name)
                    target = output / 'licenses' / spec['file']
                    target.parent.mkdir(exist_ok=True)
                    target.write_bytes(text)
                    record['standardTexts'].append(name)
                    used_texts.add(name)
            folder = re.sub(r'[^A-Za-z0-9._-]', '_', coordinate)
            record['noticeDirectory'] = 'artifacts/' + folder
            record['upstreamNoticeFiles'] = collect_embedded_notices(
                artifact['path'], output / record['noticeDirectory'])
            base_coordinate = ':'.join(coordinate.split(':')[:3])
            supplement = sources.get('artifactSupplements', {}).get(base_coordinate)
            if supplement:
                record['upstreamSourceReview'] = {k: v for k, v in supplement.items() if k != 'files'}
                for notice in supplement.get('files', []):
                    original = (sources_path.parent / 'license-notices' / notice['file']).read_bytes()
                    if digest(original) != notice['sha256']:
                        raise ValueError('Reviewed upstream notice checksum mismatch')
                    relative = 'source-archive/' + notice['file']
                    target = output / record['noticeDirectory'] / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(original)
                    record['upstreamNoticeFiles'].append({**notice, 'path': relative,
                        'source': notice.get('source', supplement['source'])})
            if not record['upstreamNoticeFiles']:
                record['reviewNotes'].append('No named license/notice files in this binary. '
                                             'Do not invent copyrights; verify upstream source notices.')
            if len(declarations) > 1:
                record['reviewNotes'].append('Multiple declarations preserved; review per-file '
                                             'license alternatives and obligations before distribution.')
            if any('LGPL' in declaration['name'].upper() or 'LESSER' in declaration['name'].upper()
                   for declaration in declarations):
                record['reviewNotes'].append('Review LGPL source/relinking obligations or verified '
                                             'alternative license; collection is not a compliance approval.')
        except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile) as error:
            # Retain every unresolved component and exit nonzero only after writing the report.
            missing.append({'coordinate': coordinate, 'reason': str(error)})
        record['standardTexts'] = sorted(set(record['standardTexts']))
        records.append(record)
    inventory = {
        'schemaVersion': 1, 'module': module, 'scope': 'Maven compile/runtime artifacts',
        'collectionComplete': not missing, 'complianceApproved': False,
        'limitations': ['Standard texts supplement, never replace, actual upstream copyright/NOTICE.',
                        'POM declarations may be alternatives or apply to different files. No automatic relicensing.',
                        'Source, notice and relinking obligations require component-specific review; '
                        'this inventory is not a legal or contribution-rights approval.',
                        'JDK/base-image/server software licenses are outside this Maven closure.'],
        'standardTextSources': {name: sources['texts'][name] for name in sorted(used_texts)},
        'artifacts': records, 'missing': missing}
    (output / 'inventory.json').write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + '\n')
    summary = [f'Third-party distribution material for {module}',
               'Full standard texts: licenses/; original artifact notices: artifacts/.',
               'Collection complete: ' + str(not missing).lower(),
               'This is not a legal approval. Read inventory.json limitations and per-component reviewNotes.', '']
    for record in records:
        summary.append(record['coordinate'])
        summary.extend('  Declared: ' + license['name'] + ' ' + license['url']
                       for license in record['declaredLicenses'])
        summary.append('  Standard texts: ' + ', '.join(record['standardTexts']))
        summary.append('  Original notice files: ' + str(len(record['upstreamNoticeFiles'])))
        summary.extend('  Review: ' + note for note in record['reviewNotes'])
    for failure in missing:
        summary.append('UNRESOLVED ' + failure['coordinate'] + ': ' + failure['reason'])
    (output / 'THIRD-PARTY.txt').write_text('\n'.join(summary) + '\n')
    return inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dependencies', required=True, type=Path)
    parser.add_argument('--repository', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--module', required=True)
    parser.add_argument('--packaging', default='jar')
    args = parser.parse_args()
    if args.packaging == 'pom':
        return 0
    inventory = collect(args.dependencies, args.repository, args.output, args.module)
    print(f'{args.module}: collected license material for {len(inventory["artifacts"])} runtime artifacts; '
          f'{len(inventory["missing"])} unresolved')
    if inventory['missing']:
        print('Release blocked: inspect ' + str(args.output / 'inventory.json'), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
