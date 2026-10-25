"""Exercise the actual psql export against an explicitly owned disposable PostgreSQL container."""

import json
import os
from pathlib import Path
import subprocess
import unittest
import uuid


TOOLS = Path(__file__).resolve().parent
CONTAINER = os.environ.get('PROTO_STORAGE_TEST_CONTAINER')


@unittest.skipUnless(CONTAINER, 'requires an owned disposable PROTO_STORAGE_TEST_CONTAINER')
class StorageExportPostgresTest(unittest.TestCase):
    def sql(self, source):
        result = subprocess.run(['docker', 'exec', '-i', CONTAINER, 'psql', '-X', '-qAt',
                                 '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'postgres'],
                                input=source, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip()

    def setUp(self):
        self.schema = 'storage_test_' + uuid.uuid4().hex
        self.sql('CREATE SCHEMA ' + self.schema + ';')
        self.addCleanup(self.sql, 'DROP SCHEMA ' + self.schema + ' CASCADE;')
        self.sql(self.prefix() + """
          CREATE TABLE p_project(id text primary key);
          CREATE TABLE p_version(id text primary key,project_id text,current_revision_id text,
            storage_path text,entry_file text,deleted_time bigint);
          CREATE TABLE p_revision(id text primary key,version_id text,project_id text,storage_path text,entry_file text);
          CREATE TABLE p_share(id text primary key,version_id text,project_id text,revision_id text,revision_mode text);
          CREATE TABLE p_creation_receipt(id text primary key,resource_id text,revision_id text);
          CREATE TABLE p_review_thread(id text primary key,version_id text,project_id text,revision_id text);
          CREATE TABLE p_review_message(id text primary key,thread_id text,version_id text,project_id text,
            revision_id text,visibility text,image_path text,image_content_type text,image_size bigint,
            image_sha256 text,image_width int,image_height int);
          INSERT INTO p_project VALUES('project');
          INSERT INTO p_version VALUES('version','project','r1','projects/project/versions/snapshot/site','index.html',1);
          INSERT INTO p_revision VALUES('r1','version','project','projects/project/versions/snapshot/site','index.html');
          INSERT INTO p_review_thread VALUES('thread','version','project','r1');
          INSERT INTO p_review_message VALUES
            ('public-message','thread','version','project','r1','public',
             'projects/project/review-images/' || repeat('a',43) || '/image','image/png',1,repeat('a',64),1,1),
            ('internal-message','thread','version','project','r1','internal',
             'projects/project/review-images/' || repeat('b',43) || '/image','image/jpeg',2,repeat('b',64),1,2),
            ('text-message','thread','version','project','r1','public',null,null,null,null,null,null);
          INSERT INTO p_creation_receipt VALUES('version-receipt','version','r1'),
            ('thread-receipt','thread','r1'),('message-receipt','public-message','r1'),
            ('internal-receipt','internal-message','r1'),('legacy-receipt','unknown',null);
          """)

    def prefix(self):
        return 'SET search_path TO ' + self.schema + ';\n'

    def export(self, mutation=''):
        script = (TOOLS / 'export-storage-manifest.sql').read_text().replace(
            '\\ir prototype-hub-table-names.sql', (TOOLS / 'prototype-hub-table-names.sql').read_text())
        output = self.sql(self.prefix() + 'BEGIN;\n' + mutation + '\n' + script + '\nROLLBACK;')
        return json.loads(output)

    def test_retained_public_internal_images_and_real_receipt_resources_are_complete(self):
        manifest = self.export()
        self.assertEqual(3, manifest['formatVersion'])
        self.assertEqual('immutable-revisions-review-images-v1', manifest['sourceSchema'])
        self.assertTrue(manifest['referencesComplete'])
        self.assertTrue(manifest['receiptReferencesChecked'])
        self.assertTrue(manifest['imageReferencesChecked'])
        self.assertEqual(0, manifest['integrityErrors'])
        self.assertEqual({'public-message', 'internal-message'},
                         {row['messageId'] for row in manifest['imageEntries']})
        self.assertEqual({1, 2}, {row['size'] for row in manifest['imageEntries']})
        self.assertTrue(manifest['entries'])
        for row in manifest['imageEntries']:
            self.assertEqual({'projectId', 'versionId', 'messageId', 'storagePath', 'size', 'sha256'}, set(row))

    def test_unknown_receipt_resource_or_wrong_revision_is_not_silently_skipped(self):
        for mutation in ("UPDATE p_creation_receipt SET resource_id='missing' WHERE id='message-receipt';",
                         "UPDATE p_creation_receipt SET resource_id=null WHERE id='message-receipt';",
                         "UPDATE p_creation_receipt SET revision_id='missing' WHERE id='message-receipt';",
                         "UPDATE p_review_message SET revision_id='missing' WHERE id='public-message';",
                         "UPDATE p_review_thread SET version_id='missing';"):
            with self.subTest(mutation=mutation):
                self.assertGreater(self.export(mutation)['integrityErrors'], 0)

    def test_partial_invalid_or_duplicate_image_metadata_is_not_full_authority(self):
        for mutation in ("UPDATE p_review_message SET image_sha256=null WHERE id='public-message';",
                         "UPDATE p_review_message SET image_size=0 WHERE id='public-message';",
                         "UPDATE p_review_message SET image_sha256='invalid' WHERE id='public-message';",
                         "UPDATE p_review_message SET image_content_type='image/svg+xml' WHERE id='public-message';",
                         "UPDATE p_review_message SET image_width=16384,image_height=16384 WHERE id='public-message';",
                         "UPDATE p_review_message SET image_path='../outside' WHERE id='public-message';",
                         "UPDATE p_review_message SET image_path=(SELECT image_path FROM p_review_message WHERE id='public-message') WHERE id='internal-message';"):
            with self.subTest(mutation=mutation):
                self.assertGreater(self.export(mutation)['integrityErrors'], 0)

    def test_broken_image_message_thread_project_relationships_are_detected(self):
        for mutation in ("UPDATE p_review_message SET thread_id='missing' WHERE id='public-message';",
                         "UPDATE p_review_message SET project_id='other' WHERE id='public-message';",
                         "UPDATE p_review_message SET project_id=null WHERE id='text-message';",
                         "UPDATE p_review_message SET version_id=null WHERE id='text-message';",
                         "UPDATE p_review_message SET revision_id=null WHERE id='text-message';",
                         "DELETE FROM p_project;", "DELETE FROM p_version;", "DELETE FROM p_revision;"):
            with self.subTest(mutation=mutation):
                self.assertGreater(self.export(mutation)['integrityErrors'], 0)

    def test_pre_image_schema_is_inspection_only_and_keeps_review_receipt_checks(self):
        manifest = self.export('ALTER TABLE p_review_message DROP COLUMN image_path;')
        self.assertFalse(manifest['referencesComplete'])
        self.assertFalse(manifest['imageReferencesChecked'])
        self.assertTrue(manifest['receiptReferencesChecked'])
        self.assertEqual(0, manifest['integrityErrors'])
        self.assertEqual([], manifest['imageEntries'])

    def test_pre_review_schema_is_inspection_only_and_does_not_accept_unknown_receipts(self):
        manifest = self.export('DROP TABLE p_review_thread,p_review_message;')
        self.assertFalse(manifest['referencesComplete'])
        self.assertFalse(manifest['imageReferencesChecked'])
        self.assertGreater(manifest['integrityErrors'], 0)

    def test_pre_revision_schema_retains_legacy_read_only_export(self):
        manifest = self.export('ALTER TABLE p_version DROP COLUMN current_revision_id;')
        self.assertEqual(2, manifest['formatVersion'])
        self.assertFalse(manifest['referencesComplete'])
        self.assertEqual('legacy-current-only', manifest['sourceSchema'])


if __name__ == '__main__':
    unittest.main()
