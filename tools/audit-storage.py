#!/usr/bin/env python3
"""Audit a quiescent storage snapshot against every persisted revision reference."""

import argparse
import json
from pathlib import Path
import re
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--delete-unreferenced', action='store_true')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--confirm')
    args = parser.parse_args()
    root = Path(args.root).absolute()
    if root.is_symlink():
        parser.error('Storage root must not be a symlink')
    root = root.resolve()
    manifest = json.loads(Path(args.manifest).read_text())
    complete = (isinstance(manifest, dict)
                and manifest.get('format') == 'prototype-hub-storage-references'
                and manifest.get('formatVersion') == 2
                and manifest.get('sourceSchema') == 'immutable-revisions-v1'
                and manifest.get('referencesComplete') is True
                and type(manifest.get('integrityErrors')) is int
                and manifest.get('integrityErrors') == 0)
    rows = manifest.get('entries') if isinstance(manifest, dict) else manifest
    if not isinstance(rows, list):
        parser.error('Storage reference entries must be an array')
    if args.delete_unreferenced:
        # Old manifests only listed current p_version paths. Accept them for inspection,
        # but never let that incomplete view turn retained revisions into deletion targets.
        if not complete:
            parser.error('Repair requires a complete revision reference export with valid associations in format version 2')
        if not args.offline or not args.confirm or Path(args.confirm).resolve() != root:
            parser.error('Repair requires --offline and --confirm equal to the absolute storage root')
    referenced = set()
    findings = []
    for row in rows:
        if not isinstance(row, dict):
            parser.error('Invalid storage reference entry')
        project, storage = row.get('projectId'), row.get('storagePath')
        if not isinstance(project, str) or not re.fullmatch('[A-Za-z0-9_-]{1,128}', project):
            parser.error('Invalid project in manifest')
        if not isinstance(storage, str) or not re.fullmatch(
                r'projects/' + re.escape(project) + r'/versions/[A-Za-z0-9_-]{1,128}/site', storage):
            parser.error('Invalid storage boundary in manifest')
        path = root / storage
        version = path.parent
        if (not path.is_relative_to(root)
                or version.parent != root / 'projects' / project / 'versions'):
            parser.error('Invalid storage boundary in manifest')
        if any(part.is_symlink() for part in
               (path, version, version.parent, version.parent.parent)):
            parser.error('Symlink in manifest storage boundary')
        referenced.add(version)
        if not path.is_dir():
            findings.append({'kind': 'missing-files', 'projectId': project,
                             'versionId': row.get('id')})
    unreferenced = []
    for version in sorted((root / 'projects').glob('*/versions/*')):
        if any(part.is_symlink() for part in
               (version, version.parent, version.parent.parent, version.parent.parent.parent)):
            findings.append({'kind': 'symlink', 'path': str(version.relative_to(root))})
            continue
        if version not in referenced and version.is_dir():
            unreferenced.append(version)
            findings.append({'kind': 'unreferenced-directory',
                             'path': str(version.relative_to(root))})
    print(json.dumps(findings, ensure_ascii=False, indent=2))
    if args.delete_unreferenced:
        for version in unreferenced:
            if any(part.is_symlink() for part in
                   (version, version.parent, version.parent.parent, version.parent.parent.parent)):
                parser.error('Refusing repair through symlink')
            shutil.rmtree(version)
        print('Removed ' + str(len(unreferenced)) + ' unreferenced directories from the offline snapshot')


if __name__ == '__main__':
    main()
