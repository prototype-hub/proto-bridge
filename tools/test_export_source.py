"""Behavioral checks for the standalone source export boundary."""

import contextlib
import io
import json
from pathlib import Path
import runpy
import tempfile
import unittest

exporter = runpy.run_path(str(Path(__file__).with_name('export-source.py')))


class SourceExportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='source-export-test-')
        self.base = Path(self.temp.name).resolve()
        self.root = self.base / 'source'
        (self.root / 'src').mkdir(parents=True)
        (self.root / 'src/a.py').write_text('print("public")\n')
        (self.root / 'README.md').write_text('[source](src/a.py)\n')
        self.rules = {'schemaVersion': 1, 'project': 'fixture', 'files': ['README.md'],
                      'directories': ['src'], 'sourceExtensions': ['.py'], 'exclude': []}

    def tearDown(self):
        self.temp.cleanup()

    def collect(self):
        return exporter['collect'](self.root, self.rules)

    def test_exports_only_reviewed_files_with_hashes(self):
        (self.root / 'src/.env').write_text('SECRET=local-only\n')
        (self.root / 'data').mkdir()
        (self.root / 'data/private.py').write_text('local-only\n')
        self.rules['directories'].append('data')
        output = self.base / 'result'
        with contextlib.redirect_stdout(io.StringIO()):
            exporter['export'](self.root, output, self.rules)
        manifest = json.loads((output / 'SOURCE_MANIFEST.json').read_text())
        self.assertEqual(['README.md', 'src/a.py'], [row['path'] for row in manifest['files']])
        self.assertTrue(all(len(row['sha256']) == 64 for row in manifest['files']))
        self.assertFalse((output / 'src/.env').exists())

    def test_keeps_business_packages_named_like_runtime_directories(self):
        expected = {'README.md', 'src/a.py'}
        for name in ('config', 'logs', 'data', 'coverage', 'evidence',
                     'verification', 'screenshots', 'baseline'):
            path = self.root / 'src' / name / 'model.py'
            path.parent.mkdir()
            path.write_text('public = True\n')
            expected.add(path.relative_to(self.root).as_posix())
        self.assertEqual(expected, {str(path) for path in self.collect()})

    def test_keeps_upstream_legal_headers_in_data_packages(self):
        relative = ('third-party/license-notices/spring-data/source-headers/'
                    'org/springframework/data/relational/ManagedTypes.java.txt')
        path = self.root / relative
        path.parent.mkdir(parents=True)
        path.write_text('Upstream copyright and license header\n')
        self.rules['directories'].append('third-party')
        self.rules['sourceExtensions'].append('.txt')
        output = self.base / 'legal-result'
        with contextlib.redirect_stdout(io.StringIO()):
            exporter['export'](self.root, output, self.rules)
        self.assertEqual(path.read_bytes(), (output / relative).read_bytes())

    def test_excludes_runtime_and_internal_evidence_at_known_locations(self):
        for directory in ('config', 'data', 'logs', 'coverage', 'playwright-report',
                          'test-results', 'bootstrap/config', 'bootstrap/data',
                          'bootstrap/logs', 'doc/evidence', 'doc/verification',
                          'doc/screenshots', 'doc/baseline'):
            path = self.root / directory / 'private.py'
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('local_only = True\n')
            self.rules['directories'].append(directory)
        self.assertEqual({'README.md', 'src/a.py'},
                         {str(path) for path in self.collect()})

    def test_rejects_internal_output(self):
        with self.assertRaises(ValueError):
            exporter['export'](self.root, self.root / 'out', self.rules)

    def test_rejects_source_ancestor_output(self):
        with self.assertRaises(ValueError):
            exporter['export'](self.root, self.base, self.rules)

    def test_refuses_overwrite(self):
        output = self.base / 'result'
        output.mkdir()
        with self.assertRaises(ValueError):
            exporter['export'](self.root, output, self.rules)

    def test_rejects_internal_and_external_symlinks(self):
        link = self.root / 'src/link.py'
        for target in [self.root / 'src/a.py', self.base / 'outside']:
            link.symlink_to(target)
            with self.assertRaises(ValueError):
                self.collect()
            link.unlink()

    def test_rejects_private_key_without_echoing_value(self):
        marker = '-----BEGIN ' + 'PRIVATE KEY-----'
        (self.root / 'src/leak.py').write_text(marker)
        with self.assertRaises(ValueError) as failure:
            exporter['scan'](self.root, self.collect())
        self.assertNotIn(marker, str(failure.exception))

    def test_rejects_sensitive_literal(self):
        (self.root / 'src/leak.py').write_text('service_secret = "' + 'A' * 32 + '"\n')
        with self.assertRaises(ValueError):
            exporter['scan'](self.root, self.collect())

    def test_fixture_placeholder_does_not_hide_later_sensitive_value(self):
        tests = self.root / 'tests'
        tests.mkdir()
        fixture = tests / 'fixture.py'
        placeholder = 'api_key = "fixture-existing-placeholder-value"\n'
        fixture.write_text(placeholder)
        exporter['scan'](self.root, [Path('tests/fixture.py')])
        fixture.write_text(placeholder + 'api_key = "' + 'A' * 32 + '"\n')
        with self.assertRaises(ValueError):
            exporter['scan'](self.root, [Path('tests/fixture.py')])
        (self.root / 'src/leak.py').write_text(placeholder)
        with self.assertRaises(ValueError):
            exporter['scan'](self.root, [Path('src/leak.py')])

    def test_rejects_unreviewed_file_type(self):
        (self.root / 'src/unreviewed.png').write_bytes(b'not-reviewed')
        with self.assertRaises(ValueError):
            self.collect()

    def test_rejects_readme_link_not_selected(self):
        (self.root / 'README.md').write_text('[private](not-selected.md)\n')
        with self.assertRaises(ValueError):
            exporter['validate_markdown_links'](self.root, self.collect())

    def test_validates_nested_public_document_links(self):
        (self.root / 'src/guide.md').write_text('[private](../not-selected.md)\n')
        self.rules['sourceExtensions'].append('.md')
        with self.assertRaises(ValueError):
            exporter['validate_markdown_links'](self.root, self.collect())

    def test_rejects_allowlist_traversal(self):
        with self.assertRaises(ValueError):
            exporter['safe_relative']('../outside')


if __name__ == '__main__':
    unittest.main()
