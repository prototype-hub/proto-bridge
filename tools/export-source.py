#!/usr/bin/env python3
"""Export a reviewed source allowlist without Git metadata or local runtime data."""

import argparse
import fnmatch
import hashlib
import json
import os
import posixpath
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import tempfile
from datetime import datetime, timezone
import uuid
from urllib.parse import unquote

DENIED_DIRECTORIES = {
    '.git', '.idea', '.vscode', 'node_modules', 'target', 'dist', '__pycache__',
}
# Runtime and audit directories are identified by their location. A business
# package or unchanged upstream legal path named data/logs/config is public source.
DENIED_PATH_PREFIXES = {
    ('config',), ('data',), ('logs',), ('coverage',), ('playwright-report',),
    ('test-results',), ('bootstrap', 'config'), ('bootstrap', 'data'),
    ('bootstrap', 'logs'), ('doc', 'evidence'), ('doc', 'verification'),
    ('doc', 'screenshots'), ('doc', 'baseline'),
}
DENIED_NAMES = {'.DS_Store', 'application-local.yml', 'application-local.yaml', 'variables.yml'}
HIGH_CONFIDENCE_PATTERNS = {
    'private-key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'),
    'cloud-access-key': re.compile(r'\bAKIA[A-Z0-9]{16}\b'),
    'github-token': re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})\b'),
    'gitlab-token': re.compile(r'\bglpat-[A-Za-z0-9_-]{20,}\b'),
    'slack-token': re.compile(r'\bxox[baprs]-[A-Za-z0-9-]{20,}\b'),
    'literal-sensitive-value': re.compile(r'(?im)^[ \t]*(?:[\"\']?[A-Za-z0-9_.-]*(?:password|secret|api[_-]?key|access[_-]?token)[\"\']?)[ \t]*[:=][ \t]*[\"\']?([A-Za-z0-9_+/=-]{24,})'),
    'jwt': re.compile(r'\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b'),
}


def safe_relative(value):
    path = PurePosixPath(value)
    if not value or path.is_absolute() or '..' in path.parts or '\\' in value:
        raise ValueError('Allowlist paths must be relative and cannot contain traversal')
    return path


def deny(path):
    if any(part in DENIED_DIRECTORIES for part in path.parts):
        return True
    if any(path.parts[:len(prefix)] == prefix for prefix in DENIED_PATH_PREFIXES):
        return True
    if path.name in DENIED_NAMES or path.suffix in {'.log', '.pyc', '.hprof'}:
        return True
    return path.name.startswith('.env') and path.name != '.env.example'


def within(path, root):
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def collect(root, rules):
    selected = set()
    for value in rules['files']:
        rel = safe_relative(value)
        source = root.joinpath(*rel.parts)
        if not source.is_file() or source.is_symlink():
            raise ValueError(f'Required public file missing or symlink: {rel}')
        selected.add(rel)
    for value in rules['directories']:
        rel = safe_relative(value)
        source = root.joinpath(*rel.parts)
        if not source.is_dir() or source.is_symlink():
            raise ValueError(f'Required public directory missing or symlink: {rel}')
        # Do not follow even internal links: an export must be self-contained.
        for current, directories, names in os.walk(source, followlinks=False):
            base = Path(current).relative_to(root)
            kept = []
            for name in directories:
                candidate = PurePosixPath((base / name).as_posix())
                if deny(candidate):
                    continue
                if (root / candidate).is_symlink():
                    raise ValueError(f'Symlink rejected: {candidate}')
                kept.append(name)
            directories[:] = kept
            for name in names:
                candidate = PurePosixPath((base / name).as_posix())
                if not deny(candidate):
                    selected.add(candidate)
    accepted = []
    allowed_suffixes = set(rules['sourceExtensions'])
    for rel in sorted(selected):
        if deny(rel) or any(fnmatch.fnmatchcase(str(rel), pattern) for pattern in rules.get('exclude', [])):
            continue
        if str(rel) not in rules['files'] and rel.suffix not in allowed_suffixes and rel.name not in {'LICENSE', 'NOTICE', 'COPYING'}:
            raise ValueError(f'Unreviewed file type in public source directory: {rel}')
        source = root / rel
        # Check ancestors as well as leaf paths; explicit files must not escape via links.
        current = root
        for part in rel.parts:
            current = current / part
            if current.is_symlink():
                raise ValueError(f'Symlink rejected: {rel}')
        if not within(source.resolve(), root) or not source.is_file():
            raise ValueError(f'Invalid source file: {rel}')
        accepted.append(rel)
    return accepted


def scan(root, paths):
    failures = []
    texts = 0
    for rel in paths:
        content = (root / rel).read_bytes()
        if b'\0' in content[:8192]:
            continue
        try:
            text = content.decode('utf-8')
        except UnicodeDecodeError:
            continue
        texts += 1
        for category, pattern in HIGH_CONFIDENCE_PATTERNS.items():
            for match in pattern.finditer(text):
                # The test suite uses clearly named fixture word placeholders. This
                # narrow exception never suppresses provider keys or later matches.
                if (category == 'literal-sensitive-value'
                        and rel.parts[0] == 'tests'
                        and re.fullmatch(r'fixture-(?:[a-z]+-)*[a-z]+', match.group(1))):
                    continue
                failures.append({'path': str(rel), 'line': text[:match.start()].count('\n') + 1, 'category': category})
    if failures:
        # Never echo the detected value; a CI or terminal log may be public.
        raise ValueError('Sensitive pattern detected: ' + json.dumps(failures, ensure_ascii=False))
    return texts


def validate_markdown_links(root, paths):
    selected = {str(path) for path in paths}
    for document in (path for path in paths if path.suffix == '.md'):
        text = (root / document).read_text(encoding='utf-8')
        for target in re.findall(r'\]\(([^)]+)\)', text):
            target = unquote(target.split('#', 1)[0].strip('<>'))
            if not target or re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:', target):
                continue
            resolved = posixpath.normpath(str(document.parent / target))
            if target.startswith('/') or resolved.startswith('../') or resolved not in selected:
                raise ValueError(f'Markdown link is not in public export: {document} -> {target}')


def export(root, output, rules):
    if within(output, root) or within(root, output):
        raise ValueError('Output must be outside the source tree and cannot be its ancestor')
    if output.exists():
        raise ValueError('Output already exists; choose a new directory (nothing will be overwritten)')
    output.parent.mkdir(parents=True, exist_ok=True)
    paths = collect(root, rules)
    texts = scan(root, paths)
    validate_markdown_links(root, paths)
    records = []
    staging = Path(tempfile.mkdtemp(prefix='.source-export-', dir=output.parent))
    try:
        for rel in paths:
            source, target = root / rel, staging / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            data = source.read_bytes()
            target.write_bytes(data)
            target.chmod(0o755 if source.stat().st_mode & 0o111 else 0o644)
            records.append({'path': str(rel), 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
        # Scan the copied bytes too, closing the gap between selection and copying.
        scan(staging, paths)
        validate_markdown_links(staging, paths)
        manifest = {'schemaVersion': 1, 'project': rules['project'], 'files': records,
                    'scan': {'utf8Files': texts, 'patterns': list(HIGH_CONFIDENCE_PATTERNS),
                             'limitations': 'Heuristic patterns do not prove rights, absence of all secrets, or clean Git history.'}}
        (staging / 'SOURCE_MANIFEST.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        if output.exists():
            raise ValueError('Output appeared during export; nothing will be overwritten')
        staging.rename(output)
    except BaseException:
        shutil.rmtree(staging)
        raise
    print(json.dumps({'output': str(output), 'files': len(records), 'utf8FilesScanned': texts,
                      'manifest': str(output / 'SOURCE_MANIFEST.json')}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--output', type=Path, help='New output directory outside the source tree; default: system temporary directory')
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    rules_path = root / 'open-source-export.json'
    if rules_path.is_symlink():
        raise ValueError('Allowlist must not be a symlink')
    rules = json.loads(rules_path.read_text(encoding='utf-8'))
    if rules.get('schemaVersion') != 1:
        raise ValueError('Unsupported source export schema')
    stamp = datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')
    output = (args.output or Path(tempfile.gettempdir()) / f"{rules['project']}-source-{stamp}-{uuid.uuid4().hex[:8]}").absolute()
    if output.is_symlink():
        raise ValueError('Output must not be a symlink')
    export(root, output.resolve(), rules)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f'Source export refused: {error}', file=sys.stderr)
        sys.exit(1)
