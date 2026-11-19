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
          CREATE TABLE p_revision_material(id text primary key,project_id text,version_id text,
           revision_id text,material_type text,storage_path text,file_size bigint,sha256 text,source_notes text);
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

    def script(self, name, mutation=''):
        script = (TOOLS / name).read_text().replace(
            '\\ir prototype-hub-table-names.sql', (TOOLS / 'prototype-hub-table-names.sql').read_text())
        return self.sql(self.prefix() + 'BEGIN;\n' + mutation + '\n' + script + '\nROLLBACK;')

    def export(self, mutation=''):
        return json.loads(self.script('export-storage-manifest.sql', mutation))

    def create_page_delivery(self):
        self.sql(self.prefix() + """
          ALTER TABLE p_revision ADD COLUMN manifest_json text;
          UPDATE p_revision SET manifest_json='{"pages":[{"pageId":"page","path":"index.html","folder":false,"children":[]}]}';
          CREATE TABLE p_page_delivery(id text primary key,project_id text,version_id text,
            revision_id text,page_id text,page_name text,page_path text,state text,assignee_id text,
            assignee_name text,reason text,config_version bigint,updated_at bigint);
          INSERT INTO p_page_delivery VALUES('delivery','project','version','r1','page','Original page',
            'index.html','READY','removed-member','Private historical assignee','Private delivery reason',1,123);
          INSERT INTO p_creation_receipt VALUES('delivery-receipt','version','r1');
          """)

    def test_retained_public_internal_images_and_real_receipt_resources_are_complete(self):
        manifest = self.export()
        self.assertEqual(4, manifest['formatVersion'])
        self.assertEqual('immutable-revisions-materials-v1', manifest['sourceSchema'])
        self.assertTrue(manifest['referencesComplete'])
        self.assertTrue(manifest['receiptReferencesChecked'])
        self.assertTrue(manifest['imageReferencesChecked'])
        self.assertFalse(manifest['pageDeliveryReferencesChecked'])
        self.assertEqual(0, manifest['integrityErrors'])
        self.assertEqual({'public-message', 'internal-message'},
                         {row['messageId'] for row in manifest['imageEntries']})
        self.assertEqual({1, 2}, {row['size'] for row in manifest['imageEntries']})
        self.assertTrue(manifest['entries'])
        for row in manifest['imageEntries']:
            self.assertEqual({'projectId', 'versionId', 'messageId', 'storagePath', 'size', 'sha256'}, set(row))

    def test_revision_material_references_are_complete_without_exporting_source_text(self):
        self.sql(self.prefix() + """
          INSERT INTO p_revision_material VALUES('material','project','version','r1','document',
           'projects/project/materials/material/content',3,repeat('a',64),'Private source notes');
          """)
        manifest = self.export()
        self.assertTrue(manifest['materialReferencesChecked'])
        self.assertEqual(0, manifest['integrityErrors'])
        self.assertEqual('material', manifest['materialEntries'][0]['materialId'])
        self.assertNotIn('Private source notes', json.dumps(manifest))
        for mutation in ("UPDATE p_revision_material SET revision_id='missing';",
                         "UPDATE p_revision_material SET storage_path='../escape';",
                         "UPDATE p_revision_material SET sha256='invalid';"):
            self.assertGreater(self.export(mutation)['integrityErrors'], 0)

    def test_unknown_receipt_resource_or_wrong_revision_is_not_silently_skipped(self):
        for mutation in ("UPDATE p_creation_receipt SET resource_id='missing' WHERE id='message-receipt';",
                         "UPDATE p_creation_receipt SET resource_id=null WHERE id='message-receipt';",
                         "UPDATE p_creation_receipt SET revision_id='missing' WHERE id='message-receipt';",
                         "UPDATE p_review_message SET revision_id='missing' WHERE id='public-message';",
                         "UPDATE p_review_thread SET version_id='missing';"):
            with self.subTest(mutation=mutation):
                self.assertGreater(self.export(mutation)['integrityErrors'], 0)

    def test_optional_viewport_checks_preserve_fixed_scope_without_exporting_manual_text(self):
        self.sql(self.prefix() + """
          ALTER TABLE p_revision ADD COLUMN manifest_json text;
          UPDATE p_revision SET manifest_json='{"pages":[{"pageId":"page","path":"index.html","folder":false,"children":[]}]}';
          CREATE TABLE p_viewport_check(id text primary key,project_id text,version_id text,
            revision_id text,page_id text,title text,observed text);
          INSERT INTO p_viewport_check VALUES('viewport','project','version','r1','page',
            'Private manual title','Private manual observation');
          """)
        manifest = self.export()
        self.assertEqual(0, manifest['integrityErrors'])
        self.assertTrue(manifest['referencesComplete'])
        self.assertNotIn('Private manual', json.dumps(manifest))
        for mutation in ("UPDATE p_viewport_check SET revision_id='missing';",
                         "UPDATE p_viewport_check SET page_id='other-page';",
                         "UPDATE p_viewport_check SET project_id='missing';"):
            with self.subTest(mutation=mutation):
                self.assertGreater(self.export(mutation)['integrityErrors'], 0)

    def test_optional_work_items_check_fixed_associations_without_exporting_private_content(self):
        create = """
          CREATE TABLE p_work_item(id text primary key,project_id text,version_id text,
            revision_id text,page_id text,url text,note text);
          INSERT INTO p_work_item VALUES('item','project','version','r1','page',
            'https://example.invalid/private-task','private business note');
          INSERT INTO p_creation_receipt VALUES('item-receipt','version','r1');
        """
        manifest = self.export(create)
        self.assertTrue(manifest['referencesComplete'])
        self.assertEqual(0, manifest['integrityErrors'])
        self.assertNotIn('private-task', json.dumps(manifest))
        self.assertNotIn('private business note', json.dumps(manifest))
        for mutation in ("UPDATE p_work_item SET project_id='missing';",
                         "UPDATE p_work_item SET version_id='missing';",
                         "UPDATE p_work_item SET revision_id='missing';",
                         "UPDATE p_work_item SET project_id=null;"):
            with self.subTest(mutation=mutation):
                self.assertGreater(self.export(create + mutation)['integrityErrors'], 0)

    def test_page_delivery_checks_fixed_leaf_ownership_and_accepts_historical_assignee(self):
        self.create_page_delivery()
        manifest = self.export()
        self.assertTrue(manifest['referencesComplete'])
        self.assertTrue(manifest['pageDeliveryReferencesChecked'])
        self.assertEqual(0, manifest['integrityErrors'])
        self.assertNotIn('Private historical assignee', json.dumps(manifest))
        self.assertNotIn('Private delivery reason', json.dumps(manifest))
        for mutation in ("UPDATE p_page_delivery SET project_id='missing';",
                         "UPDATE p_page_delivery SET version_id='missing';",
                         "UPDATE p_page_delivery SET revision_id='missing';",
                         "UPDATE p_page_delivery SET project_id=null;",
                         "UPDATE p_page_delivery SET page_id='other';",
                         "UPDATE p_page_delivery SET page_path='other.html';",
                         "UPDATE p_revision SET manifest_json=replace(manifest_json,'false','true');",
                         "UPDATE p_revision SET manifest_json=null;"):
            with self.subTest(mutation=mutation):
                self.assertGreater(self.export(mutation)['integrityErrors'], 0)

    def test_page_delivery_snapshot_hashes_every_field_without_printing_private_text(self):
        self.create_page_delivery()
        before = self.script('snapshot-db.sql')
        self.assertIn('page_deliveries|1|', before)
        self.assertIn('business_rows_p_page_delivery|1|', before)
        self.assertIn('p_page_delivery', next(line for line in before.splitlines()
                                             if line.startswith('business_table_coverage|')))
        self.assertNotIn('Private historical assignee', before)
        self.assertNotIn('Private delivery reason', before)
        for field, value in (('project_id', "'other'"), ('version_id', "'other'"),
                             ('revision_id', "'other'"), ('page_id', "'other'"),
                             ('page_name', "'changed'"), ('page_path', "'changed.html'"),
                             ('state', "'DONE'"), ('assignee_id', "'other'"),
                             ('assignee_name', "'changed'"), ('reason', "'changed'"),
                             ('config_version', '2'), ('updated_at', '124')):
            with self.subTest(field=field):
                after = self.script('snapshot-db.sql', 'UPDATE p_page_delivery SET ' + field + '=' + value + ';')
                self.assertNotEqual(before, after)
        self.assertEqual(before, self.script('snapshot-db.sql'))

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
