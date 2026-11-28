"""Exercise the actual psql export against an explicitly owned disposable PostgreSQL container."""

import itertools
import json
import os
from pathlib import Path
import subprocess
import unittest
import uuid


TOOLS = Path(__file__).resolve().parent
CONTAINER = os.environ.get('PROTO_STORAGE_TEST_CONTAINER')
DATABASE_USER = os.environ.get('PROTO_STORAGE_TEST_USER', 'postgres')
DATABASE_NAME = os.environ.get('PROTO_STORAGE_TEST_DATABASE', 'postgres')


@unittest.skipUnless(CONTAINER, 'requires an owned disposable PROTO_STORAGE_TEST_CONTAINER')
class StorageExportPostgresTest(unittest.TestCase):
    def sql(self, source):
        result = subprocess.run(['docker', 'exec', '-i', CONTAINER, 'psql', '-X', '-qAt',
                                 '-v', 'ON_ERROR_STOP=1', '-U', DATABASE_USER, '-d', DATABASE_NAME],
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
           revision_id text,material_type text,storage_path text,file_size bigint,sha256 text,source_notes text,
           file_name text,media_type text,source_name text,source_url text);
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

    def test_design_token_packages_export_retained_raw_graph_and_hash_all_private_metadata(self):
        self.create_page_traceability()
        self.sql(self.prefix() + """
          CREATE TABLE p_design_token_package(id text primary key,project_id text,version_id text,revision_id text,
            file_name text,file_size bigint,sha256 text,storage_path text,standard_version text,validator_version text,
            valid boolean,token_count int,group_count int,issue_count int,report text,
            source_tool text,source_name text,source_url text,source_notes text,
            config_version bigint,created_at bigint,updated_at bigint);
          INSERT INTO p_design_token_package VALUES('package','project','version','r1','tokens.json',2,
            repeat('a',64),'projects/project/design-token-packages/pack/content','2025.10','2025.10-1',true,0,0,0,
            '{"valid":true,"issues":[],"truncated":false,"inputs":{}}','Penpot','Private source',
            'https://example.test/source','Private source note',0,100,100);
        """)
        result = self.export()
        self.assertEqual(5, result['formatVersion'])
        self.assertTrue(result['tokenPackageReferencesChecked'])
        self.assertEqual(0, result['integrityErrors'])
        self.assertEqual('package', result['tokenPackageEntries'][0]['packageId'])
        self.assertNotIn('Private source', json.dumps(result))
        self.assertEqual(0, self.export("UPDATE p_design_token_package SET source_tool=null,source_name=null,source_url=null,source_notes=null;")['integrityErrors'])
        before = self.script('snapshot-db.sql')
        self.assertIn('business_rows_p_design_token_package|1|', before)
        self.assertIn('design_token_package|1|', before)
        after = self.script('snapshot-db.sql', "UPDATE p_design_token_package SET source_notes='Changed private note';")
        self.assertNotEqual(before, after)
        for assignment in ("project_id='missing'", "version_id='missing'", "revision_id='missing'",
                           "storage_path='projects/foreign/design-token-packages/pack/content'", "sha256='bad'",
                           'file_size=1048577', "standard_version='future'", 'token_count=5001',
                           'config_version=-1', 'updated_at=99', "source_url='javascript:alert(1)'"):
            mutation = 'UPDATE p_design_token_package SET ' + assignment + ';'
            self.assertGreater(self.export(mutation)['integrityErrors'], 0, mutation)
            self.assertNotIn('current_scope_errors|0', self.script('preflight-db.sql', mutation).splitlines())

    def create_page_traceability(self):
        self.sql(self.prefix() + """
          ALTER TABLE p_share ADD COLUMN token text;
          ALTER TABLE p_version ADD COLUMN status text;
          ALTER TABLE p_revision ADD COLUMN manifest_json text;
          UPDATE p_revision SET manifest_json='{"pages":[{"pageId":"parent","path":"index.html","folder":false,"children":[{"pageId":"child","path":"child.html","folder":false,"children":[]}]}]}';
          CREATE TABLE p_page_relation(id text,project_id text,version_id text,revision_id text,
            from_page_id text,to_page_id text,label text,note text,config_version bigint,created_at bigint,updated_at bigint);
          CREATE TABLE p_page_trace_context(id text,project_id text,version_id text,revision_id text,
            page_id text,entry boolean,source_refs text,config_version bigint,created_at bigint,updated_at bigint);
          CREATE TABLE p_page_trace_decision(id text,project_id text,version_id text,revision_id text,
            page_id text,title text,decision text,rationale text,author_id text,updated_by text,
            config_version bigint,created_at bigint,updated_at bigint);
          INSERT INTO p_page_relation VALUES('relation','project','version','r1','parent','child',
            'Private relationship','Private relationship note',1,100,101);
          INSERT INTO p_page_trace_context VALUES('context','project','version','r1','parent',true,
            '[{"kind":"work-item","id":"deleted-source","expectedVersion":1},{"kind":"material","id":"changed-source","expectedVersion":2}]',2,100,102);
          INSERT INTO p_page_trace_decision VALUES('decision','project','version','r1','parent',
            'Private change title','Private decision','Private rationale','removed-author','removed-updater',3,100,103);
        """)

    def test_page_traceability_checks_full_fixed_scope_and_accepts_missing_historical_sources(self):
        self.create_page_traceability()
        result = self.export()
        self.assertTrue(result['pageTraceabilityReferencesChecked'])
        self.assertTrue(result['referencesComplete'])
        self.assertEqual(0, result['integrityErrors'])
        for table in ('p_page_relation', 'p_page_trace_context', 'p_page_trace_decision'):
            for assignment in ("project_id='missing'", "version_id='missing'", "revision_id='missing'",
                               'config_version=-1', 'created_at=-1', 'updated_at=99'):
                mutation = 'UPDATE ' + table + ' SET ' + assignment + ';'
                self.assertGreater(self.export(mutation)['integrityErrors'], 0, mutation)
                self.assertNotIn('current_scope_errors|0', self.script('preflight-db.sql', mutation).splitlines())
        for mutation in ("UPDATE p_page_relation SET from_page_id='missing';",
                         "UPDATE p_page_relation SET to_page_id='parent';",
                         "UPDATE p_page_relation SET label='';",
                         "UPDATE p_page_trace_context SET page_id='missing';",
                         "UPDATE p_page_trace_context SET entry=null;",
                         "UPDATE p_page_trace_decision SET page_id='missing';",
                         "UPDATE p_page_trace_decision SET decision='';",
                         "UPDATE p_page_trace_decision SET rationale='';",
                         "UPDATE p_page_trace_decision SET author_id='';"):
            self.assertGreater(self.export(mutation)['integrityErrors'], 0, mutation)
        self.assertEqual(0, self.export("UPDATE p_page_trace_context SET entry=false,source_refs='[]';")['integrityErrors'])

    def test_page_traceability_source_shape_versions_duplicates_and_limit_fail_closed(self):
        self.create_page_traceability()
        for value in ('{}', '[{}]', '[{"kind":"unknown","id":"id","expectedVersion":0}]',
                      '[{"kind":"material","id":"id","expectedVersion":-1}]',
                      '[{"kind":"material","id":"id","expectedVersion":1.5}]',
                      '[{"kind":"material","id":"id","expectedVersion":9007199254740992}]',
                      '[{"kind":"material","id":"id","expectedVersion":"1"}]',
                      '[{"kind":"material","id":"id","expectedVersion":1,"extra":true}]',
                      '[{"kind":"material","id":"id","expectedVersion":1},{"kind":"material","id":"id","expectedVersion":2}]'):
            self.assertGreater(self.export("UPDATE p_page_trace_context SET source_refs='" + value + "';")['integrityErrors'], 0, value)
        self.assertGreater(self.export("UPDATE p_page_trace_context SET source_refs=(SELECT jsonb_agg(jsonb_build_object('kind','material','id','source-'||i,'expectedVersion',0))::text FROM generate_series(1,41) i);")['integrityErrors'], 0)
        self.assertGreater(self.export("INSERT INTO p_page_trace_context SELECT 'duplicate',project_id,version_id,revision_id,page_id,entry,source_refs,config_version,created_at,updated_at FROM p_page_trace_context;")['integrityErrors'], 0)

    def test_partial_page_traceability_graph_never_claims_complete_reference_coverage(self):
        self.sql(self.prefix() + 'ALTER TABLE p_revision ADD COLUMN manifest_json text; CREATE TABLE p_page_trace_context(id text,project_id text,version_id text,revision_id text,page_id text,entry boolean,source_refs text,config_version bigint,created_at bigint,updated_at bigint);')
        # Partial presence is itself corruption, even with no new rows.
        result = self.export()
        self.assertFalse(result['pageTraceabilityReferencesChecked'])
        self.assertFalse(result['referencesComplete'])
        self.assertGreater(result['integrityErrors'], 0)

    def test_page_traceability_snapshot_hashes_all_private_rows_and_pointer_order(self):
        self.create_page_traceability()
        before = self.script('snapshot-db.sql')
        for private in ('Private relationship', 'Private change title', 'deleted-source', 'removed-author'):
            self.assertNotIn(private, before)
        for table in ('p_page_relation', 'p_page_trace_context', 'p_page_trace_decision'):
            self.assertIn('business_rows_' + table + '|1|', before)
            self.assertIn(table, self.script('preflight-db.sql'))
        mutations = ("UPDATE p_page_relation SET note='Different';",
                     "UPDATE p_page_relation SET from_page_id='child',to_page_id='parent';",
                     "UPDATE p_page_trace_context SET source_refs=jsonb_build_array(source_refs::jsonb->1,source_refs::jsonb->0)::text;",
                     "UPDATE p_page_trace_context SET source_refs=jsonb_set(source_refs::jsonb,'{0,expectedVersion}','4')::text;",
                     "UPDATE p_page_trace_decision SET updated_by='another-historical-actor';",
                     "UPDATE p_page_trace_decision SET rationale='Different rationale';")
        for mutation in mutations:
            self.assertNotEqual(before, self.script('snapshot-db.sql', mutation), mutation)

    def create_code_mappings(self):
        self.create_page_traceability()
        self.sql(self.prefix() + """
          CREATE TABLE p_component_code_mapping(id text primary key,project_id text,version_id text,revision_id text,
            page_id text,alias text,repository_path text,repository_url text,documentation_url text,snippet text,
            framework text,framework_version text,description_id text,description_version bigint,
            config_version bigint,created_at bigint,updated_at bigint);
          INSERT INTO p_component_code_mapping VALUES('code-card','project','version','r1','parent','Private code alias',
            'src/private/Secret.vue','https://example.invalid/repository','https://example.invalid/document',
            '<script>  ordinary text  </script>','Vue','3','deleted-description',2,3,100,103);
        """)

    def test_code_mapping_scope_text_and_pointer_shape_checked_without_requiring_live_source(self):
        self.create_code_mappings()
        manifest = self.export()
        self.assertTrue(manifest['codeMappingReferencesChecked'])
        self.assertEqual(0, manifest['integrityErrors'])
        self.assertEqual(0, self.export('UPDATE p_component_code_mapping SET description_id=null,description_version=null;')['integrityErrors'])
        for assignment in ("project_id='missing'", "version_id='missing'", "revision_id='missing'", "page_id='missing'",
                           'config_version=-1', 'created_at=-1', 'updated_at=99', "alias=''", "repository_path=''",
                           "snippet=null", "framework=null", "repository_url='javascript:alert(1)'",
                           "documentation_url='https://user:secret@example.invalid/doc'", 'description_version=-1',
                           'description_version=9007199254740992', 'description_id=null'):
            mutation = 'UPDATE p_component_code_mapping SET ' + assignment + ';'
            self.assertGreater(self.export(mutation)['integrityErrors'], 0, assignment)
            self.assertNotIn('current_scope_errors|0', self.script('preflight-db.sql', mutation).splitlines(), assignment)

    def test_code_mapping_snapshot_hashes_every_private_field_without_printing_card_content(self):
        self.create_code_mappings()
        before = self.script('snapshot-db.sql')
        self.assertIn('business_rows_p_component_code_mapping|1|', before)
        self.assertIn('component_code_mapping|1|', before)
        for private in ('Private code alias', 'Secret.vue', '<script>', 'deleted-description'):
            self.assertNotIn(private, before)
            self.assertNotIn(private, self.script('export-storage-manifest.sql'))
        for assignment in ("alias='Different'", "repository_path='different.vue'", "snippet='Different indentation'",
                           "repository_url='https://other.invalid/repo'", "documentation_url='https://other.invalid/doc'",
                           "framework='Other'", "framework_version='4'", 'description_version=3', 'config_version=4'):
            self.assertNotEqual(before, self.script('snapshot-db.sql', 'UPDATE p_component_code_mapping SET ' + assignment + ';'))
        self.assertEqual(before, self.script('snapshot-db.sql'))

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
        self.assertFalse(manifest['elementDescriptionReferencesChecked'])
        self.assertFalse(manifest['demoScriptReferencesChecked'])
        self.assertFalse(manifest['organizationReferencesChecked'])
        self.assertFalse(manifest['codeMappingReferencesChecked'])
        self.assertEqual(0, manifest['integrityErrors'])
        self.assertEqual({'public-message', 'internal-message'},
                         {row['messageId'] for row in manifest['imageEntries']})
        self.assertEqual({1, 2}, {row['size'] for row in manifest['imageEntries']})
        self.assertTrue(manifest['entries'])
        for row in manifest['imageEntries']:
            self.assertEqual({'projectId', 'versionId', 'messageId', 'storagePath', 'size', 'sha256'}, set(row))

    def test_revision_material_references_are_complete_without_exporting_source_text(self):
        self.sql(self.prefix() + """
          INSERT INTO p_revision_material(id,project_id,version_id,revision_id,material_type,storage_path,file_size,sha256,source_notes)
           VALUES('material','project','version','r1','document',
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

    def test_independent_links_validate_scope_and_no_payload_without_exporting_targets(self):
        self.sql(self.prefix() + """
          INSERT INTO p_revision_material VALUES('link','project','version','r1','link','',0,'',
           'Private link notes','','','Private link title','https://private.example/prd');
          """)
        manifest = self.export()
        self.assertEqual(0, manifest['integrityErrors'])
        self.assertEqual([], manifest['materialEntries'])
        for private in ('private.example', 'Private link title', 'Private link notes'):
            self.assertNotIn(private, json.dumps(manifest))
        for mutation in ("UPDATE p_revision_material SET revision_id='missing';",
                         "UPDATE p_revision_material SET file_name='fake.pdf';",
                         "UPDATE p_revision_material SET media_type='application/pdf';",
                         "UPDATE p_revision_material SET file_size=1;",
                         "UPDATE p_revision_material SET sha256=repeat('a',64);",
                         "UPDATE p_revision_material SET storage_path='projects/project/materials/link/content';",
                         "UPDATE p_revision_material SET source_name='';",
                         "UPDATE p_revision_material SET source_url=null;",
                         "UPDATE p_revision_material SET material_type='unknown';"):
            with self.subTest(mutation=mutation):
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

    def create_element_description(self):
        self.sql(self.prefix() + """
          ALTER TABLE p_share ADD COLUMN token text;
          ALTER TABLE p_version ADD COLUMN status text;
          ALTER TABLE p_revision ADD COLUMN manifest_json text;
          UPDATE p_revision SET manifest_json='{"pages":[{"pageId":"page","path":"index.html","folder":false,"children":[]}]}';
          CREATE TABLE p_element_description(id text primary key,project_id text,version_id text,
            revision_id text,page_id text,title text,purpose text,input_rules text,permission_rules text,
            navigation_rules text,notes text,anchor text,config_version bigint,created_at bigint,updated_at bigint);
          INSERT INTO p_element_description VALUES('description','project','version','r1','page',
            'Private object title','Private purpose','Private input','Private permission','Private navigation',
            'Private notes','{"shape":"point","x":12}',0,100,101);
          INSERT INTO p_creation_receipt VALUES('description-receipt','version','r1');
          """)

    def test_element_description_checks_fixed_leaf_scope_without_exporting_text_or_geometry(self):
        self.create_element_description()
        manifest = self.export()
        self.assertTrue(manifest['elementDescriptionReferencesChecked'])
        self.assertEqual(0, manifest['integrityErrors'])
        self.assertNotIn('Private', json.dumps(manifest))
        self.assertNotIn('shape', json.dumps(manifest))
        for mutation in ("UPDATE p_element_description SET project_id='missing';",
                         "UPDATE p_element_description SET version_id='missing';",
                         "UPDATE p_element_description SET revision_id='missing';",
                         "UPDATE p_element_description SET project_id=null;",
                         "UPDATE p_element_description SET page_id='other';",
                         "UPDATE p_element_description SET page_id=null;",
                         "UPDATE p_revision SET manifest_json=replace(manifest_json,'false','true');",
                         "UPDATE p_revision SET manifest_json=null;"):
            with self.subTest(mutation=mutation):
                self.assertGreater(self.export(mutation)['integrityErrors'], 0)
                self.assertNotIn('current_scope_errors|0', self.script('preflight-db.sql', mutation))

    def create_demo_script(self):
        self.sql(self.prefix() + """
          ALTER TABLE p_share ADD COLUMN token text;
          ALTER TABLE p_version ADD COLUMN status text;
          ALTER TABLE p_revision ADD COLUMN manifest_json text;
          UPDATE p_revision SET manifest_json='{"pages":[{"pageId":"parent","path":"index.html","folder":false,"children":[{"pageId":"child","path":"child.html","folder":false,"children":[]}]},{"pageId":"directory","folder":true,"children":[]}]}';
          CREATE TABLE p_demo_script(id text primary key,project_id text,version_id text,
            revision_id text,title text,objective text,prerequisites text,reset_note text,steps text,
            share_enabled boolean,config_version bigint,created_at bigint,updated_at bigint);
          INSERT INTO p_demo_script VALUES('script','project','version','r1','Private demo title',
            'Private demo objective','Private prerequisites','Private reset guidance',
            '[{"id":"first","pageId":"parent","narration":"Private first narration"},{"id":"child","pageId":"child","narration":"Private child narration"},{"id":"revisit","pageId":"parent","narration":"Private repeat narration"}]',
            true,2,100,101);
          INSERT INTO p_creation_receipt VALUES('demo-receipt','version','r1');
          """)

    def test_demo_scripts_check_parent_pages_repeat_visits_shape_and_fixed_scope(self):
        self.create_demo_script()
        manifest = self.export()
        self.assertEqual(4, manifest['formatVersion'])
        self.assertTrue(manifest['demoScriptReferencesChecked'])
        self.assertTrue(manifest['referencesComplete'])
        self.assertEqual(0, manifest['integrityErrors'])
        self.assertNotIn('Private', json.dumps(manifest))
        self.assertNotIn('narration', json.dumps(manifest))
        mutations = ["project_id='missing'", "version_id='missing'", "revision_id='missing'",
                     "project_id=null", "share_enabled=null", "config_version=-1",
                     "config_version=9007199254740992",
                     "steps='null'", "steps='{}'", "steps='\"scalar\"'", "steps='[]'",
                     "steps='[null]'", "steps='[false]'", "steps='[[]]'",
                     "steps=jsonb_set(steps::jsonb,'{0,pageId}','\"missing\"')::text",
                     "steps=jsonb_set(steps::jsonb,'{0,pageId}','\"directory\"')::text",
                     "steps=jsonb_set(steps::jsonb,'{1,id}',steps::jsonb->0->'id')::text",
                     "steps=jsonb_set(steps::jsonb,'{0,id}','1')::text",
                     "steps=jsonb_set(steps::jsonb,'{0,pageId}','false')::text",
                     "steps=jsonb_set(steps::jsonb,'{0,narration}','null')::text",
                     "steps=jsonb_set(steps::jsonb,'{0,unknown}','true')::text",
                     "steps=(steps::jsonb #- '{0,narration}')::text"]
        for assignment in mutations:
            with self.subTest(assignment=assignment):
                mutation = 'UPDATE p_demo_script SET ' + assignment + ';'
                self.assertGreater(self.export(mutation)['integrityErrors'], 0)
                self.assertNotIn('current_scope_errors|0', self.script('preflight-db.sql', mutation))
        self.assertGreater(self.export('UPDATE p_revision SET manifest_json=null;')['integrityErrors'], 0)
        self.assertEqual(0, self.export('UPDATE p_demo_script SET share_enabled=false;')['integrityErrors'])

    def test_demo_script_snapshot_hashes_thirteen_fields_and_step_order_without_text(self):
        self.create_demo_script()
        before = self.script('snapshot-db.sql')
        self.assertIn('demo_scripts|1|', before)
        self.assertIn('business_rows_p_demo_script|1|', before)
        self.assertNotIn('Private', before)
        self.assertNotIn('narration', before)
        self.assertIn('p_demo_script', self.script('preflight-db.sql'))
        fields = [('id', "'other'"), ('project_id', "'other'"), ('version_id', "'other'"),
                  ('revision_id', "'other'"), ('title', "'changed'"), ('objective', "'changed'"),
                  ('prerequisites', "'changed'"), ('reset_note', "'changed'"),
                  ('steps', "jsonb_set(steps::jsonb,'{0,narration}','\"changed\"')::text"),
                  ('share_enabled', 'false'), ('config_version', '3'), ('created_at', '102'), ('updated_at', '103')]
        self.assertEqual(13, len(fields))
        for field, value in fields:
            with self.subTest(field=field):
                self.assertNotEqual(before, self.script('snapshot-db.sql', 'UPDATE p_demo_script SET ' + field + '=' + value + ';'))
        self.assertNotEqual(before, self.script('snapshot-db.sql', "UPDATE p_demo_script SET steps=jsonb_build_array(steps::jsonb->2,steps::jsonb->0,steps::jsonb->1)::text;"))
        self.assertEqual(before, self.script('snapshot-db.sql'))

    def test_element_description_snapshot_hashes_all_text_geometry_scope_and_cas_fields(self):
        self.create_element_description()
        before = self.script('snapshot-db.sql')
        self.assertIn('element_descriptions|1|', before)
        self.assertIn('business_rows_p_element_description|1|', before)
        self.assertIn('p_element_description', next(line for line in before.splitlines()
                                                   if line.startswith('business_table_coverage|')))
        self.assertNotIn('Private', before)
        self.assertNotIn('shape', before)
        preflight = self.script('preflight-db.sql')
        self.assertIn('p_element_description', preflight)
        self.assertIn('current_scope_errors|0', preflight)
        for field, value in (('id', "'other'"), ('project_id', "'other'"), ('version_id', "'other'"),
                             ('revision_id', "'other'"), ('page_id', "'other'"), ('title', "'changed'"),
                             ('purpose', "'changed'"), ('input_rules', "'changed'"),
                             ('permission_rules', "'changed'"), ('navigation_rules', "'changed'"),
                             ('notes', "'changed'"), ('anchor', "'{}'"),
                             ('config_version', '1'), ('created_at', '102'), ('updated_at', '103')):
            with self.subTest(field=field):
                after = self.script('snapshot-db.sql', 'UPDATE p_element_description SET ' + field + '=' + value + ';')
                self.assertNotEqual(before, after)
        self.assertEqual(before, self.script('snapshot-db.sql'))

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



    def create_revision_approval(self):
        self.sql(self.prefix() + """
          CREATE TABLE p_revision_approval(id text primary key,project_id text,version_id text,
            revision_id text,config_version bigint,action text,approved boolean,published boolean,
            approval_id text,publication_id text,actor_id text,actor_name text,note text,create_time bigint);
          INSERT INTO p_revision_approval VALUES
            ('approval','project','version','r1',1,'approve',true,false,'approval',null,'old-user','Historical name','Private approval note',11),
            ('publication','project','version','r1',2,'publish',true,true,'approval','publication','old-user','Historical name','Private release note',12);
        """)

    def test_approval_scope_and_historical_pointers_are_checked_without_exporting_private_text(self):
        self.create_revision_approval()
        result = self.export()
        self.assertEqual(0, result['integrityErrors'])
        self.assertTrue(result['approvalReferencesChecked'])
        self.assertNotIn('Private approval note', json.dumps(result))
        self.assertNotIn('Historical name', json.dumps(result))
        for mutation in ("UPDATE p_revision_approval SET revision_id='missing';",
                         "UPDATE p_revision_approval SET project_id='missing';",
                         "UPDATE p_revision_approval SET approval_id='missing' WHERE id='publication';",
                         "UPDATE p_revision_approval SET publication_id='approval' WHERE id='publication';",
                         "UPDATE p_revision_approval SET approved=false WHERE id='publication';"):
            with self.subTest(mutation=mutation):
                self.assertGreater(self.export(mutation)['integrityErrors'], 0)

    def create_organization(self):
        self.sql(self.prefix() + """
          ALTER TABLE p_share ADD COLUMN token text;
          ALTER TABLE p_version ADD COLUMN status text;
          ALTER TABLE p_project ADD COLUMN group_name text;
          ALTER TABLE p_project ADD COLUMN archived_time bigint;
          ALTER TABLE p_project ADD COLUMN organization_version bigint;
          ALTER TABLE p_project ADD COLUMN cover text;
          CREATE TABLE s_user(id text primary key,status int);
          INSERT INTO s_user VALUES('reader',1),('disabled-reader',0);
          CREATE TABLE p_project_tag(id text primary key,project_id text,tag_name text);
          CREATE TABLE p_project_favorite(id text primary key,project_id text,user_id text,
            favorite boolean,config_version bigint,created_at bigint,updated_at bigint);
          UPDATE p_project SET group_name='Private group',archived_time=123,organization_version=2,
            cover='https://private.example/cover.png';
          INSERT INTO p_project(id) VALUES('legacy-project');
          INSERT INTO p_project_tag VALUES('tag-a','project','Private tag'),('tag-b','project','Second tag');
          INSERT INTO p_project_favorite VALUES('favorite','project','reader',false,2,100,101),
            ('other-favorite','project','disabled-reader',true,1,100,100);
          """)

    def test_organization_graph_checks_retained_false_preferences_without_exporting_labels_or_urls(self):
        self.create_organization()
        manifest = self.export()
        self.assertEqual(4, manifest['formatVersion'])
        self.assertTrue(manifest['organizationReferencesChecked'])
        self.assertTrue(manifest['referencesComplete'])
        self.assertEqual(0, manifest['integrityErrors'])
        for private in ('Private group', 'Private tag', 'private.example', 'reader', 'favorite'):
            self.assertNotIn(private, json.dumps(manifest))
        # Disabled accounts remain valid retained graph nodes; a missing account does not.
        mutations = (
            "UPDATE p_project_tag SET project_id='missing' WHERE id='tag-a';",
            "UPDATE p_project_tag SET tag_name='' WHERE id='tag-a';",
            "UPDATE p_project_tag SET tag_name=chr(160)||'Private tag' WHERE id='tag-a';",
            "UPDATE p_project_tag SET tag_name=repeat('t',33) WHERE id='tag-a';",
            "INSERT INTO p_project_tag VALUES('duplicate','project','Private tag');",
            "INSERT INTO p_project_tag SELECT 'extra-'||i,'project','Extra '||i FROM generate_series(1,10) i;",
            "UPDATE p_project_favorite SET project_id='missing' WHERE id='favorite';",
            "UPDATE p_project_favorite SET user_id='missing' WHERE id='favorite';",
            "UPDATE p_project_favorite SET favorite=null WHERE id='favorite';",
            "UPDATE p_project_favorite SET config_version=0 WHERE id='favorite';",
            "UPDATE p_project_favorite SET config_version=9007199254740992 WHERE id='favorite';",
            "UPDATE p_project_favorite SET created_at=null WHERE id='favorite';",
            "UPDATE p_project_favorite SET updated_at=99 WHERE id='favorite';",
            "INSERT INTO p_project_favorite VALUES('duplicate','project','reader',true,1,100,100);",
            "UPDATE p_project SET group_name='' WHERE id='project';",
            "UPDATE p_project SET group_name=chr(65279)||'Private group' WHERE id='project';",
            "UPDATE p_project SET group_name=repeat('g',65) WHERE id='project';",
            "UPDATE p_project SET archived_time=-1 WHERE id='project';",
            "UPDATE p_project SET organization_version=-1 WHERE id='project';",
            "UPDATE p_project SET organization_version=9007199254740992 WHERE id='project';")
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                self.assertGreater(self.export(mutation)['integrityErrors'], 0)
                self.assertNotIn('current_scope_errors|0', self.script('preflight-db.sql', mutation))
        self.assertEqual(manifest, self.export())
        # Literal case-sensitive labels can be shared across distinct projects.
        valid = "INSERT INTO p_project_tag VALUES('other-project-tag','legacy-project','Private tag');"
        self.assertEqual(0, self.export(valid)['integrityErrors'])

    def test_partial_organization_columns_without_relations_fail_closed(self):
        self.sql(self.prefix() + 'ALTER TABLE p_share ADD COLUMN token text; ALTER TABLE p_version ADD COLUMN status text;')
        columns = {'group_name': 'text', 'archived_time': 'bigint', 'organization_version': 'bigint'}
        for size in (1, 2):
            for names in itertools.combinations(columns, size):
                mutation = ''.join('ALTER TABLE p_project ADD COLUMN ' + name + ' ' + columns[name] + ';' for name in names)
                with self.subTest(columns=names):
                    manifest = self.export(mutation)
                    self.assertFalse(manifest['organizationReferencesChecked'])
                    self.assertFalse(manifest['referencesComplete'])
                    self.assertGreater(manifest['integrityErrors'], 0)
                    preflight = self.script('preflight-db.sql', mutation)
                    errors = next(line for line in preflight.splitlines() if line.startswith('current_scope_errors|'))
                    self.assertGreater(int(errors.split('|')[1]), 0)

    def test_partial_organization_schema_never_claims_complete_reference_coverage(self):
        self.sql(self.prefix() + 'CREATE TABLE p_project_tag(id text,project_id text,tag_name text);')
        manifest = self.export()
        self.assertFalse(manifest['organizationReferencesChecked'])
        self.assertFalse(manifest['referencesComplete'])
        self.assertGreater(manifest['integrityErrors'], 0)

    def test_organization_snapshot_hashes_all_thirteen_new_fields_and_existing_cover(self):
        self.create_organization()
        before = self.script('snapshot-db.sql')
        self.assertIn('business_rows_p_project_tag|2|', before)
        self.assertIn('business_rows_p_project_favorite|2|', before)
        for private in ('Private group', 'Private tag', 'private.example', 'disabled-reader'):
            self.assertNotIn(private, before)
        changes = {
            'p_project': [('group_name', "'Changed group'"), ('archived_time', '124'),
                          ('organization_version', '3'), ('cover', "'https://changed.invalid/image.png'")],
            'p_project_tag': [('id', "'changed-tag'"), ('project_id', "'legacy-project'"), ('tag_name', "'Changed tag'")],
            'p_project_favorite': [('id', "'changed-favorite'"), ('project_id', "'legacy-project'"),
                ('user_id', "'changed-reader'"), ('favorite', 'true'), ('config_version', '3'),
                ('created_at', '99'), ('updated_at', '102')]}
        for table, fields in changes.items():
            identity = {'p_project': 'project', 'p_project_tag': 'tag-a', 'p_project_favorite': 'favorite'}[table]
            label = 'business_rows_' + table + '|'
            original = next(line for line in before.splitlines() if line.startswith(label))
            for field, value in fields:
                with self.subTest(table=table, field=field):
                    after = self.script('snapshot-db.sql', 'UPDATE ' + table + ' SET ' + field + '=' + value + " WHERE id='" + identity + "';")
                    self.assertNotEqual(original, next(line for line in after.splitlines() if line.startswith(label)))
        self.assertEqual(before, self.script('snapshot-db.sql'))

    def test_approval_snapshot_hashes_full_rows_without_printing_private_content(self):
        self.create_revision_approval()
        before = self.script('snapshot-db.sql')
        self.assertIn('business_rows_p_revision_approval|2|', before)
        self.assertIn('revision_approvals|2|', before)
        self.assertNotIn('Private approval note', before)
        for field, value in (('note', "'changed'"), ('actor_name', "'Renamed'"),
                             ('create_time', '99'), ('publication_id', "'other'")):
            after = self.script('snapshot-db.sql', 'UPDATE p_revision_approval SET ' + field + '=' + value + ';')
            self.assertNotEqual(next(line for line in before.splitlines() if line.startswith('revision_approvals|')),
                                next(line for line in after.splitlines() if line.startswith('revision_approvals|')))


if __name__ == '__main__':
    unittest.main()
