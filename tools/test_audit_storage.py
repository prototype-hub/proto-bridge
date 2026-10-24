"""Protect immutable history from incomplete storage exports and unsafe repair."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


TOOL = Path(__file__).with_name('audit-storage.py')


class StorageAuditTest(unittest.TestCase):
    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory()
        self.addCleanup(self.workspace.cleanup)
        self.root = Path(self.workspace.name) / 'storage'
        self.paths = {}
        for name in ('r1', 'r2', 'orphan'):
            site = self.root / 'projects/project/versions' / name / 'site'
            site.mkdir(parents=True)
            (site / 'index.html').write_text(name)
            self.paths[name] = site

    def run_audit(self, manifest, repair=False):
        exported = Path(self.workspace.name) / 'references.json'
        exported.write_text(json.dumps(manifest))
        command = [sys.executable, str(TOOL), '--root', str(self.root),
                   '--manifest', str(exported)]
        if repair:
            command += ['--delete-unreferenced', '--offline', '--confirm', str(self.root)]
        return subprocess.run(command, capture_output=True, text=True)

    def entries(self, names):
        return [{'id': 'logical-version', 'projectId': 'project',
                 'storagePath': self.paths[name].relative_to(self.root).as_posix()}
                for name in names]

    def complete(self, entries, errors=0):
        return {'format': 'prototype-hub-storage-references', 'formatVersion': 2,
                'sourceSchema': 'immutable-revisions-v1', 'referencesComplete': True,
                'receiptReferencesChecked': True, 'integrityErrors': errors, 'entries': entries}

    def test_legacy_current_only_manifest_is_read_only(self):
        entries = self.entries(('r2',))
        self.assertEqual(0, self.run_audit(entries).returncode)
        result = self.run_audit(entries, repair=True)
        self.assertEqual(2, result.returncode)
        self.assertIn('complete revision reference export', result.stderr)
        self.assertTrue(all(path.exists() for path in self.paths.values()))

    def test_complete_export_retains_history_and_only_reclaims_unreferenced_directory(self):
        result = self.run_audit(self.complete(self.entries(('r1', 'r2'))), repair=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(self.paths['r1'].exists())
        self.assertTrue(self.paths['r2'].exists())
        self.assertFalse(self.paths['orphan'].parent.exists())

    def test_invalid_reference_never_starts_repair(self):
        entries = self.entries(('r1',))
        entries.append({'id': 'bad', 'projectId': 'project', 'storagePath': '../outside'})
        result = self.run_audit(self.complete(entries), repair=True)
        self.assertEqual(2, result.returncode)
        self.assertTrue(all(path.exists() for path in self.paths.values()))

    def test_missing_revision_schema_or_broken_associations_cannot_authorize_repair(self):
        manifest = self.complete(self.entries(('r2',)))
        variants = [dict(manifest, sourceSchema='legacy-current-only'),
                    dict(manifest, integrityErrors=1),
                    {key: value for key, value in manifest.items() if key != 'integrityErrors'}]
        for candidate in variants:
            with self.subTest(candidate=candidate):
                self.assertEqual(2, self.run_audit(candidate, repair=True).returncode)
                self.assertTrue(all(path.exists() for path in self.paths.values()))

    def test_deleted_revision_entries_remain_referenced(self):
        entries = self.entries(('r1', 'r2'))
        entries[0].update(deletedTime=1720000000000, revisionId='retained-history')
        result = self.run_audit(self.complete(entries), repair=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(self.paths['r1'].exists())
        self.assertTrue(self.paths['r2'].exists())

    def test_legacy_path_alias_never_authorizes_offline_repair(self):
        entries = self.entries(('r1', 'r2'))
        entries[0]['storagePath'] = 'projects/project/versions/alias/../r1/site'
        result = self.run_audit(self.complete(entries), repair=True)
        self.assertEqual(2, result.returncode)
        self.assertIn('Invalid storage boundary', result.stderr)
        self.assertTrue(all(path.exists() for path in self.paths.values()))

    def test_unchecked_receipt_export_is_diagnostic_only(self):
        manifest = self.complete(self.entries(('r1', 'r2')))
        manifest.pop('receiptReferencesChecked')
        self.assertEqual(0, self.run_audit(manifest).returncode)
        self.assertEqual(2, self.run_audit(manifest, repair=True).returncode)
        self.assertTrue(self.paths['orphan'].exists())

    def test_database_integrity_error_fails_even_when_all_references_exist(self):
        manifest = self.complete(self.entries(('r1', 'r2', 'orphan')), errors=1)
        result = self.run_audit(manifest)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual([{'kind': 'database-integrity', 'count': 1}], json.loads(result.stdout))
        self.assertEqual(2, self.run_audit(manifest, repair=True).returncode)
        self.assertTrue(all(path.exists() for path in self.paths.values()))

    def test_missing_reference_fails_read_and_never_repairs_other_directories(self):
        manifest = self.complete(self.entries(('r1', 'r2')))
        self.paths['r1'].rename(self.paths['r1'].with_name('missing-site'))
        for repair in (False, True):
            with self.subTest(repair=repair):
                result = self.run_audit(manifest, repair=repair)
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn('missing-files', result.stdout)
                self.assertTrue(self.paths['orphan'].exists())

    def test_nested_symlink_fails_read_and_never_repairs_other_directories(self):
        outside = Path(self.workspace.name) / 'outside'
        outside.write_text('unrelated')
        (self.paths['r1'] / 'linked-asset').symlink_to(outside)
        manifest = self.complete(self.entries(('r1', 'r2')))
        for repair in (False, True):
            with self.subTest(repair=repair):
                result = self.run_audit(manifest, repair=repair)
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn('symlink', result.stdout)
                self.assertTrue(self.paths['orphan'].exists())
        self.assertEqual('unrelated', outside.read_text())


if __name__ == '__main__':
    unittest.main()
