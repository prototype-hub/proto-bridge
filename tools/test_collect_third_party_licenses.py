#!/usr/bin/env python3
"""Release regression tests with local fixtures; no dependency or license downloads."""
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('collector', ROOT / 'tools/collect-third-party-licenses.py')
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class DistributionLicenseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repository'
        self.dependencies = self.root / 'dependencies.txt'
        self.output = self.root / 'output'
        self.sources = self.root / 'license-sources.json'
        text = b'Fixture license full text\n'
        (self.root / 'license-texts').mkdir()
        (self.root / 'license-texts/Apache-2.0.txt').write_bytes(text)
        self.sources.write_text(json.dumps({'aliases': {'Apache 2.0': ['Apache-2.0']}, 'texts': {
            'Apache-2.0': {'file': 'Apache-2.0.txt', 'sha256': collector.digest(text),
                           'source': 'https://example.invalid/reviewed'}}}))

    def artifact(self, license_name='Apache 2.0', entries=None, inherited=False, classifier=''):
        folder = self.repo / 'example/library/1.0'
        folder.mkdir(parents=True, exist_ok=True)
        pom = '<project><modelVersion>4.0.0</modelVersion>'
        declaration = '<licenses><license><name>' + license_name + '</name><url>https://example.invalid/license</url></license></licenses>'
        if inherited:
            parent = self.repo / 'example/parent/1.0'
            parent.mkdir(parents=True)
            (parent / 'parent-1.0.pom').write_text('<project>' + declaration + '</project>')
            pom += '<parent><groupId>example</groupId><artifactId>parent</artifactId><version>1.0</version></parent>'
        else:
            pom += declaration
        (folder / 'library-1.0.pom').write_text(pom + '</project>')
        jar = folder / ('library-1.0' + ('-' + classifier if classifier else '') + '.jar')
        with zipfile.ZipFile(jar, 'w') as archive:
            for name, data in (entries or {'META-INF/NOTICE.txt': b'Original author attribution\r\n'}).items():
                archive.writestr(name, data)
        coordinate = 'example:library:jar:' + (classifier + ':' if classifier else '') + '1.0'
        self.dependencies.write_text('The following files have been resolved:\n  ' + coordinate + ':runtime:' + str(jar) + ' -- module example.library (auto)\n')
        return jar

    def collect(self):
        return collector.collect(self.dependencies, self.repo, self.output, 'fixture', self.sources)

    def test_inherited_metadata_and_verbatim_attribution(self):
        self.artifact(inherited=True)
        result = self.collect()
        self.assertTrue(result['collectionComplete'])
        self.assertFalse(result['complianceApproved'])
        artifact = result['artifacts'][0]
        self.assertEqual('example/parent/1.0/parent-1.0.pom', artifact['licenseMetadataPom'])
        notice = self.output / artifact['noticeDirectory'] / 'META-INF/NOTICE.txt'
        self.assertEqual(b'Original author attribution\r\n', notice.read_bytes())
        self.assertEqual(collector.digest(notice.read_bytes()), artifact['upstreamNoticeFiles'][0]['sha256'])

    def test_native_classifier_is_retained(self):
        self.artifact(classifier='linux-aarch_64')
        self.assertEqual('example:library:1.0:linux-aarch_64', self.collect()['artifacts'][0]['coordinate'])

    def test_unreviewed_license_blocks_and_records_coordinate(self):
        self.artifact(license_name='Unknown license')
        result = self.collect()
        self.assertFalse(result['collectionComplete'])
        self.assertEqual('example:library:1.0', result['missing'][0]['coordinate'])

    def test_checksum_mismatch_blocks_release(self):
        self.artifact()
        (self.root / 'license-texts/Apache-2.0.txt').write_bytes(b'Changed')
        self.assertIn('checksum mismatch', self.collect()['missing'][0]['reason'])

    def test_empty_or_changed_maven_output_blocks_release(self):
        self.dependencies.write_text('No resolved records\n')
        result = self.collect()
        self.assertFalse(result['collectionComplete'])
        self.assertIn('Empty runtime', result['missing'][0]['reason'])

    def test_notice_zip_path_traversal_is_rejected(self):
        self.artifact(entries={'../NOTICE': b'unsafe'})
        self.assertIn('Unsafe', self.collect()['missing'][0]['reason'])
        self.assertFalse((self.root / 'NOTICE').exists())

    def test_removed_dependencies_cannot_leave_stale_notices(self):
        self.artifact()
        self.collect()
        (self.output / 'stale-license.txt').write_text('obsolete')
        self.collect()
        self.assertFalse((self.output / 'stale-license.txt').exists())

    def test_unmarked_existing_output_is_not_deleted(self):
        self.artifact()
        self.output.mkdir()
        sentinel = self.output / 'user-data'
        sentinel.write_text('keep')
        with self.assertRaisesRegex(ValueError, 'unmarked'):
            self.collect()
        self.assertTrue(sentinel.exists())

    def test_repository_output_is_not_deleted(self):
        self.artifact()
        self.output = self.repo
        with self.assertRaisesRegex(ValueError, 'Unsafe'):
            self.collect()
        self.assertTrue((self.repo / 'example/library/1.0/library-1.0.pom').is_file())

    def test_reviewed_source_notices_are_preserved_and_hashed(self):
        self.artifact()
        text = b'/* Original upstream copyright and permission */'
        notice = self.root / 'license-notices/library/Header.txt'
        notice.parent.mkdir(parents=True)
        notice.write_bytes(text)
        sources = json.loads(self.sources.read_text())
        sources['artifactSupplements'] = {'example:library:1.0': {
            'source': 'https://example.invalid/library-1.0-sources.jar',
            'sourceArchiveSha256': 'fixture', 'status': 'collected', 'files': [{
                'file': 'library/Header.txt', 'sourceEntry': 'Header.java',
                'kind': 'verbatim-source-header', 'sha256': collector.digest(text)}]}}
        self.sources.write_text(json.dumps(sources))
        artifact = self.collect()['artifacts'][0]
        copied = self.output / artifact['noticeDirectory'] / 'source-archive/library/Header.txt'
        self.assertEqual(text, copied.read_bytes())
        notice.write_bytes(b'changed')
        self.assertIn('notice checksum mismatch', self.collect()['missing'][0]['reason'])


if __name__ == '__main__':
    unittest.main()
