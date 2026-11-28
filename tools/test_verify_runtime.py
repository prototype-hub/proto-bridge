"""Safety boundary checks for the disposable packaged-runtime verifier."""

import argparse
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
from email.message import Message
from email import policy
from email.parser import BytesParser

spec = importlib.util.spec_from_file_location('verify_runtime', Path(__file__).with_name('verify-runtime.py'))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class CurrentGraphVerificationTest(unittest.TestCase):
    def test_restore_expiry_window_accepts_only_registered_original_deadlines_and_separates_boundaries(self):
        before = {table: {} for table in runtime.REVIEW_TABLES}
        before['p_review_challenge'] = {'prelaunch': {'hash': 'a', 'deadline': 99},
            'during': {'hash': 'b', 'deadline': 105}, 'unexpired': {'hash': 'c', 'deadline': 200}}
        registered = runtime.registered_review_deadlines(before)
        after = copy.deepcopy(before)
        del after['p_review_challenge']['prelaunch']
        del after['p_review_challenge']['during']
        counts, details = runtime.review_expiry_observation(before, after, 100, 110, registered)
        self.assertEqual(2, counts['p_review_challenge'])
        self.assertEqual({'p_review_challenge': {'prelaunch': 99}}, details['expiredBeforeLaunch'])
        self.assertEqual({'p_review_challenge': {'during': 105}}, details['expiredDuringReadback'])
        with self.assertRaises(runtime.VerificationFailure):
            runtime.review_expiry_observation(before, after, 100, 104, registered)
        for change in ('unregistered', 'changed-original-deadline', 'unexpired-loss', 'retained-rewrite'):
            plan, current = copy.deepcopy(registered), copy.deepcopy(after)
            if change == 'unregistered': del plan['p_review_challenge']['during']
            if change == 'changed-original-deadline': plan['p_review_challenge']['during'] = 104
            if change == 'unexpired-loss': current['p_review_challenge'].clear()
            if change == 'retained-rewrite': current['p_review_challenge']['unexpired']['hash'] = 'changed'
            with self.subTest(change=change), self.assertRaises(runtime.VerificationFailure):
                runtime.review_expiry_observation(before, current, 100, 110, plan)

    def test_original_visit_ticket_uses_its_real_expiry_and_never_inserts_on_either_branch(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'; jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            api = runtime.Api('http://127.0.0.1:10001', check.secrets_seen); api.token = 'platform-token'
            visits = {'path': '/original-visit', 'recentTicket': 'original-frozen-ticket', 'ticketExpiresAt': 100}
            for clock, expected in ((.099, 200), (.100, 403), (.101, 403)):
                with self.subTest(clock=clock), patch.object(runtime.time, 'time', return_value=clock), \
                        patch.object(check, 'sql', side_effect=['original-row-hash', 'original-row-hash']), \
                        patch.object(runtime.Api, 'request', autospec=True) as request:
                    check.verify_visit_replay(api, visits, 'owned')
                    self.assertEqual(expected, request.call_args.kwargs['expected'])
                    self.assertEqual({'ticket': 'original-frozen-ticket'}, request.call_args.args[3])
                    self.assertIsNone(request.call_args.args[0].token)
                    self.assertEqual(expected == 403, check.report['originalVisitTicketReplay']['naturallyExpiredRejected403'])
            with patch.object(runtime.time, 'time', return_value=.101), \
                    patch.object(check, 'sql', side_effect=['original-row-hash', 'changed-row-hash']), \
                    patch.object(runtime.Api, 'request'):
                with self.assertRaisesRegex(runtime.VerificationFailure, 'inserted or changed stored visits'):
                    check.verify_visit_replay(api, visits, 'owned')

    def test_material_restore_rejects_changed_source_bytes_or_private_download_headers(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'; jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            original = runtime.sample_png((25, 90, 155, 255))
            row = {'id': 'material', 'projectId': 'project', 'versionId': 'version', 'revisionId': 'revision',
                   'sourceName': 'Private material source', 'fileSize': len(original), 'mediaType': 'image/png',
                   'sha256': hashlib.sha256(original).hexdigest()}
            fixture = {'path': '/materials', 'row': row, 'original': original, 'body': b'original-multipart',
                       'headers': {'Idempotency-Key': 'original-upload'}}
            current = copy.deepcopy(row)
            content = original
            headers = {'Content-Type': 'image/png', 'Content-Disposition': 'attachment; filename="material.png"',
                       'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff',
                       'Content-Security-Policy': "sandbox; default-src 'none'"}
            api = unittest.mock.Mock()
            def respond(method, path, payload=None, **kwargs):
                if path.endswith('/content'): return content, headers
                if path.endswith('/_query'): return {'total': 1, 'data': [current]}
                return current
            api.request.side_effect = respond
            check.verify_revision_material_fixture(api, fixture)
            self.assertNotIn('Private material source', json.dumps(check.report))
            self.assertIn(unittest.mock.call('POST', '/materials', b'original-multipart',
                                            headers={'Idempotency-Key': 'original-upload'}), api.request.call_args_list)
            current['sourceName'] = 'Changed source'
            with self.assertRaisesRegex(runtime.VerificationFailure, 'metadata, source or fixed scope'):
                check.verify_revision_material_fixture(api, fixture)
            current = copy.deepcopy(row)
            content = original + b'changed'
            with self.assertRaisesRegex(runtime.VerificationFailure, 'original bytes or hash'):
                check.verify_revision_material_fixture(api, fixture)
            content = original
            headers['Content-Disposition'] = 'inline'
            with self.assertRaisesRegex(runtime.VerificationFailure, 'attachment security boundary'):
                check.verify_revision_material_fixture(api, fixture)

    def test_shared_current_graph_runs_real_material_readback_before_backup_and_after_restore(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'; jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            fixture = {name: object() for name in ('approval', 'demoScripts', 'organization', 'pageTraceability',
                                                  'codeMappings', 'tokenPackages', 'usabilityStudies', 'revisionMaterial')}
            with contextlib.ExitStack() as stack:
                for name in ('approval', 'demo_script', 'organization', 'page_traceability', 'code_mapping',
                             'token_package', 'usability'):
                    stack.enter_context(patch.object(check, 'verify_' + name + '_fixture'))
                material = stack.enter_context(patch.object(check, 'verify_revision_material_fixture',
                                                          side_effect=RuntimeError('material-readback-reached')))
                api = unittest.mock.Mock()
                with self.assertRaisesRegex(RuntimeError, 'material-readback-reached'):
                    check.verify_current_graph(api, fixture, 'owned')
                material.assert_called_once_with(api, fixture['revisionMaterial'])

    def test_usability_restore_rejects_private_content_loss_recreated_withdrawal_and_csv_disclosure(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'; jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            study = {'id': 'study', 'state': 'closed', 'revisionId': 'revision', 'configVersion': 3,
                     'introduction': 'Private definition'}
            own = {'id': 'answer', 'status': 'submitted', 'contentVersion': 2, 'configVersion': 2,
                   'taskAnswers': [{'taskId': 'task', 'outcome': 'completed', 'feedback': 'Private task answer'}],
                   'answers': [], 'feedback': 'Private overall answer'}
            withdrawn = dict(own, id='withdrawn', status='withdrawn', taskAnswers=[], feedback='')
            member = {key: own[key] for key in ('id', 'contentVersion', 'status', 'taskAnswers', 'answers', 'feedback')}
            aggregate = {'submittedCount': 1, 'tasks': [], 'questions': [], 'excludedTextQuestionCount': 1}
            fixture = {'path': '/studies', 'row': study, 'member': member, 'aggregate': aggregate,
                'csv': b'section,item,option,count\r\n', 'operations': [('POST', '/studies', {'clientRequestId': 'original'})],
                'participants': [{'visitor': {'grant': {}, 'cookie': 'cookie'}, 'row': row,
                    'operations': [('consent', {'clientRequestId': 'original'})]} for row in (own, withdrawn)]}
            current = copy.deepcopy(study)
            member_page = {'total': 1, 'data': [member]}
            current_aggregate = copy.deepcopy(aggregate)
            csv_bytes = fixture['csv']
            replay_withdrawn = copy.deepcopy(withdrawn)
            api = unittest.mock.Mock()
            def respond(method, path, payload=None, **kwargs):
                if kwargs.get('expected') == 404: return None
                if '/responses/_query' in path: return member_page
                if '/responses/' in path: return member
                if path.endswith('/summary.csv'):
                    return csv_bytes, {'Cache-Control': 'private, no-store', 'Content-Disposition': 'attachment; filename="summary.csv"',
                                       'Content-Type': 'text/csv; charset=UTF-8', 'X-Content-Type-Options': 'nosniff'}
                if path.endswith('/summary'): return current_aggregate
                if path.endswith('/_query'): return {'total': 1, 'data': [current]}
                return current
            def review(api, grant, operation, payload, **kwargs):
                original = withdrawn if review.calls // 2 else own
                review.calls += 1
                if operation.endswith('/context'): return {'study': study, 'participation': original}, None
                return {'status': 'current', 'participation': replay_withdrawn if original['status'] == 'withdrawn' else original}, None
            review.calls = 0
            api.request.side_effect = respond
            def verify():
                review.calls = 0
                with patch.object(check, 'verify_review_identity', return_value={'csrfToken': 'csrf'}), \
                        patch.object(check, 'review_call', side_effect=review):
                    check.verify_usability_fixture(api, fixture)
            verify()
            self.assertTrue(check.report['usabilityStudies']['withdrawnAnswersClearedAndExcluded'])
            self.assertNotIn('Private task answer', json.dumps(check.report))
            self.assertNotIn('Private definition', json.dumps(check.report))
            current['introduction'] = 'changed'
            with self.assertRaisesRegex(runtime.VerificationFailure, 'definition lost'): verify()
            current = copy.deepcopy(study)
            replay_withdrawn['status'] = 'draft'
            with self.assertRaisesRegex(runtime.VerificationFailure, 'resurrected'): verify()
            replay_withdrawn = copy.deepcopy(withdrawn)
            member_page = {'total': 2, 'data': [member, withdrawn]}
            with self.assertRaisesRegex(runtime.VerificationFailure, 'member results'): verify()
            member_page = {'total': 1, 'data': [member]}
            current_aggregate['submittedCount'] = 2
            with self.assertRaisesRegex(runtime.VerificationFailure, 'aggregate changed'): verify()
            current_aggregate = copy.deepcopy(aggregate)
            fixture['csv'] = csv_bytes = b'Private free answer'
            with self.assertRaisesRegex(runtime.VerificationFailure, 'disclosed a participant'): verify()

    def test_shared_current_graph_runs_usability_restore_readback(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'; jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            fixture = {name: object() for name in ('approval', 'demoScripts', 'organization', 'pageTraceability',
                                                  'codeMappings', 'tokenPackages', 'usabilityStudies')}
            with contextlib.ExitStack() as stack:
                for name in ('approval', 'demo_script', 'organization', 'page_traceability', 'code_mapping', 'token_package'):
                    stack.enter_context(patch.object(check, 'verify_' + name + '_fixture'))
                usability = stack.enter_context(patch.object(check, 'verify_usability_fixture',
                                                          side_effect=RuntimeError('usability-readback-reached')))
                api = unittest.mock.Mock()
                with self.assertRaisesRegex(RuntimeError, 'usability-readback-reached'):
                    check.verify_current_graph(api, fixture, 'owned')
                usability.assert_called_once_with(api, fixture['usabilityStudies'])

    def test_token_package_restore_rejects_changed_scope_source_hash_missing_and_binary_boundary(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'; jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            original = b'\xef\xbb\xbf{ "n": {"$type":"number", "$value":3} }\r\n'
            row = {'id': 'package', 'projectId': 'project', 'versionId': 'version', 'revisionId': 'revision',
                   'sourceName': 'Current private source', 'sourceUrl': None, 'configVersion': 1,
                   'sha256': hashlib.sha256(original).hexdigest(), 'fileSize': len(original)}
            fixture = {'path': '/packages', 'row': row, 'body': b'original-multipart', 'original': original,
                       'headers': {'Idempotency-Key': 'original-current'},
                       'deletedHeaders': {'Idempotency-Key': 'original-deleted'}, 'deletedId': 'removed',
                       'update': {'clientRequestId': 'original-source'}, 'delete': {'clientRequestId': 'original-delete'},
                       'document': {'valid': True, 'inputs': {'density': 'compact'}},
                       'chosen': {'valid': True, 'inputs': {'density': 'comfortable'}}}
            current = copy.deepcopy(row)
            missing = {'id': 'removed', 'status': 'missing', 'package': None}
            content = original
            headers = {'Content-Type': 'application/octet-stream', 'Content-Disposition': 'attachment; filename="tokens.json"',
                       'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff',
                       'Content-Security-Policy': "sandbox; default-src 'none'"}
            api = unittest.mock.Mock()
            def respond(method, path, payload=None, **kwargs):
                if kwargs.get('expected') == 404: return None
                if path.endswith('/_query'): return {'total': 1, 'data': [current]}
                if path.endswith('/_import'):
                    return missing if kwargs['headers'] == fixture['deletedHeaders'] else {
                        'id': 'package', 'status': 'current', 'package': current}
                if path.endswith('/_delete'): return True
                if path.endswith('/_document'):
                    return fixture['chosen'] if payload.get('inputs') else fixture['document']
                if path.endswith('/content'): return content, headers
                return current
            api.request.side_effect = respond
            check.verify_token_package_fixture(api, fixture)
            self.assertTrue(check.report['designTokenPackages']['originalImportReadsCurrentMissing'])
            self.assertEqual(len(original), check.report['designTokenPackages']['originalByteCount'])
            self.assertNotIn('private', json.dumps(check.report['designTokenPackages']).lower())
            self.assertIn(unittest.mock.call('POST', '/packages/_import', fixture['body'],
                                            headers=fixture['headers']), api.request.call_args_list)
            for field, value in [('sha256', 'b' * 64), ('sourceName', 'Changed'), ('revisionId', 'foreign')]:
                current[field] = value
                with self.subTest(field=field), self.assertRaisesRegex(runtime.VerificationFailure, 'hash, source, fixed scope'):
                    check.verify_token_package_fixture(api, fixture)
                current = copy.deepcopy(row)
            current = None
            with self.assertRaises(runtime.VerificationFailure):
                check.verify_token_package_fixture(api, fixture)
            current = copy.deepcopy(row)
            missing = {'id': 'removed', 'status': 'current', 'package': row}
            with self.assertRaisesRegex(runtime.VerificationFailure, 'retain its missing result'):
                check.verify_token_package_fixture(api, fixture)
            missing = {'id': 'removed', 'status': 'missing', 'package': None}
            content = original + b' '
            with self.assertRaisesRegex(runtime.VerificationFailure, 'original bytes or hash changed'):
                check.verify_token_package_fixture(api, fixture)
            content = original
            headers['Content-Type'] = 'application/json'
            with self.assertRaisesRegex(runtime.VerificationFailure, 'envelope-free binary'):
                check.verify_token_package_fixture(api, fixture)

    def test_shared_current_graph_verification_runs_token_package_readback(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'; jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            fixture = {name: object() for name in ('approval', 'demoScripts', 'organization', 'pageTraceability',
                                                  'codeMappings', 'tokenPackages')}
            with contextlib.ExitStack() as stack:
                for name in ('approval', 'demo_script', 'organization', 'page_traceability', 'code_mapping'):
                    stack.enter_context(patch.object(check, 'verify_' + name + '_fixture'))
                tokens = stack.enter_context(patch.object(check, 'verify_token_package_fixture',
                                                          side_effect=RuntimeError('token-readback-reached')))
                api = unittest.mock.Mock()
                with self.assertRaisesRegex(RuntimeError, 'token-readback-reached'):
                    check.verify_current_graph(api, fixture, 'owned')
                tokens.assert_called_once_with(api, fixture['tokenPackages'])

    def test_code_mapping_restore_checks_private_text_sources_and_exact_deleted_batch(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'; jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            fields = {'alias': 'Card', 'repositoryPath': 'src/Private.vue', 'repositoryUrl': '',
                      'documentationUrl': '', 'snippet': '<script>\n  plain text\n</script>',
                      'framework': 'Vue', 'frameworkVersion': '3'}
            row = dict(fields, id='code-card', configVersion=2,
                       description={'id': 'source', 'expectedVersion': 0, 'status': 'changed',
                                    'currentVersion': 1, 'title': 'Current description'})
            result = {'items': [{'id': 'code-card', 'status': 'current', 'card': row},
                                {'id': 'removed-card', 'status': 'missing', 'card': None}]}
            fixture = {'path': '/code', 'rows': [row], 'result': result, 'payload': {'clientRequestId': 'original-import'},
                       'update': {'clientRequestId': 'original-update'}, 'delete': {'clientRequestId': 'original-delete'},
                       'deletedId': 'removed-card'}
            current = copy.deepcopy(row)
            api = unittest.mock.Mock()
            package = {'format': 'prototype-hub-code-mapping-cards', 'schemaVersion': 1, 'cards': [fields]}
            def respond(method, path, payload=None, **kwargs):
                if kwargs.get('expected') == 404: return None
                if path.endswith('/_query'): return {'total': 1, 'data': [current]}
                if path.endswith('/_import'): return result
                if path.endswith('/_delete'): return True
                if path.endswith('/_export'):
                    return json.dumps(package).encode(), {'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff'}
                return current
            api.request.side_effect = respond
            check.verify_code_mapping_fixture(api, fixture)
            self.assertTrue(check.report['codeMappings']['originalImportReadsExactCurrentMissingBatch'])
            self.assertIn(unittest.mock.call('POST', '/code/_import', fixture['payload']), api.request.call_args_list)
            for field, value in [('snippet', 'Altered'), ('repositoryPath', 'other.vue'),
                                 ('configVersion', 0), ('description', None)]:
                current[field] = value
                with self.assertRaisesRegex(runtime.VerificationFailure, 'Restored code mapping'):
                    check.verify_code_mapping_fixture(api, fixture)
                current = copy.deepcopy(row)
            result['items'][1] = {'id': 'removed-card', 'status': 'current', 'card': row}
            # The stored expected envelope is independent of the server response under test.
            fixture['result'] = {'items': [{'id': 'code-card', 'status': 'current', 'card': row},
                                          {'id': 'removed-card', 'status': 'missing', 'card': None}]}
            with self.assertRaisesRegex(runtime.VerificationFailure, 'exact current/missing batch'):
                check.verify_code_mapping_fixture(api, fixture)

    def test_page_traceability_restore_keeps_full_rows_and_original_key_current_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'; jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            relation = {'id': 'edge', 'fromPageId': 'page', 'toPageId': 'target', 'note': 'Private', 'configVersion': 2}
            decision = {'id': 'choice', 'authorId': 'original', 'updatedBy': 'updater', 'rationale': 'Private reason', 'configVersion': 1}
            context = {'id': 'context', 'pageId': 'page', 'entry': True, 'configVersion': 3,
                       'references': [{'kind': 'material', 'id': 'missing', 'expectedVersion': 0,
                                       'status': 'missing', 'currentVersion': None, 'title': None},
                                      {'kind': 'description', 'id': 'changed', 'expectedVersion': 1,
                                       'status': 'changed', 'currentVersion': 2, 'title': 'Current source'}]}
            fixture = {'path': '/trace', 'pageId': 'page', 'relation': relation, 'decision': decision,
                       'context': context, 'missingSourceId': 'missing', 'operations': [
                           ('POST', '/create', {'clientRequestId': 'original-edge'}, 'relation'),
                           ('PUT', '/context', {'clientRequestId': 'original-context'}, 'context')]}
            current = {'relation': relation, 'decision': decision, 'context': context}
            api = unittest.mock.Mock()
            def respond(method, path, payload=None, **kwargs):
                if kwargs.get('expected') == 404: return None
                if path.endswith('/entries/_query'): return {'total': 1, 'data': [{'pageId': 'page'}]}
                if path.endswith('/choice'): return current['decision']
                if path.endswith('/context'): return current['context']
                return current['relation']
            api.request.side_effect = respond
            check.verify_page_traceability_fixture(api, fixture)
            self.assertEqual(2, check.report['pageTraceability']['originalKeysReadCurrentState'])
            self.assertIn(unittest.mock.call('POST', '/create', fixture['operations'][0][2]), api.request.call_args_list)
            for name, field, value in [('relation', 'toPageId', 'other'), ('relation', 'configVersion', 0),
                                       ('decision', 'authorId', 'other'), ('decision', 'rationale', ''),
                                       ('context', 'entry', False), ('context', 'configVersion', 4),
                                       ('context', 'references', list(reversed(context['references'])))]:
                with self.subTest(name=name, field=field):
                    current[name] = dict(fixture[name], **{field: value})
                    with self.assertRaisesRegex(runtime.VerificationFailure, 'Restored page tracing'):
                        check.verify_page_traceability_fixture(api, fixture)
                    current[name] = fixture[name]

    def test_organization_fixture_keeps_cover_archive_labels_and_two_independent_favorite_versions(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'
            jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            original = {'id': 'project', 'name': 'Original', 'description': 'Original description',
                        'cover': '', 'groupName': '', 'tags': [], 'archivedTime': None,
                        'organizationVersion': 0, 'favorite': False, 'favoriteVersion': 0, 'updatedAt': 10}
            first = dict(original, cover='https://example.invalid/original-runtime-cover.png',
                         groupName='Original shared group', tags=['Original tag', 'Second tag'],
                         archivedTime=100, organizationVersion=1, updatedAt=20)
            shared = dict(first, groupName='Current shared group', tags=['Current tag', 'Second tag'], organizationVersion=2)
            liked = dict(shared, favorite=True, favoriteVersion=1)
            owner = dict(shared, favoriteVersion=2)
            reader_row = dict(shared, favorite=True, favoriteVersion=1)
            for broken in (False, True):
                api = unittest.mock.Mock(origin='http://owned.invalid')
                api.request.side_effect = [original, dict(original, cover=first['cover']), first, shared,
                                           liked, dict(owner, updatedAt=99) if broken else owner]
                reader = unittest.mock.Mock()
                reader.request.return_value = reader_row
                with patch.object(runtime, 'Api', return_value=reader):
                    if broken:
                        with self.assertRaisesRegex(runtime.VerificationFailure, 'Personal preference changed'):
                            check.create_organization_fixture(api, original, {'id': 'reader', 'username': 'reader'})
                    else:
                        fixture = check.create_organization_fixture(api, original, {'id': 'reader', 'username': 'reader'})
                        self.assertEqual(owner, fixture['owner'])
                        self.assertEqual(reader_row, fixture['reader'])
                        self.assertEqual([True, False], [row['favorite'] for row in fixture['favoriteOperations']])
                        self.assertEqual(2, len({row['clientRequestId'] for row in fixture['organizationOperations']}))
                        self.assertEqual({'name', 'description', 'cover'}, set(api.request.call_args_list[1].args[2]))

    def test_organization_restore_replays_original_keys_and_rejects_each_changed_persisted_field(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'
            jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            owner = {'groupName': 'Current group', 'tags': ['a', 'b'], 'archivedTime': 100,
                     'organizationVersion': 2, 'favorite': False, 'favoriteVersion': 2, 'cover': '/cover.png', 'updatedAt': 30}
            reader_row = dict(owner, favorite=True, favoriteVersion=1)
            fixture = {'path': '/project', 'owner': owner, 'reader': reader_row,
                       'member': {'username': 'reader'}, 'organizationOperations': [{'clientRequestId': 'original-org'}],
                       'favoriteOperations': [{'favorite': True, 'clientRequestId': 'original-like'},
                                              {'favorite': False, 'clientRequestId': 'original-unlike'}],
                       'readerFavorite': {'favorite': True, 'clientRequestId': 'reader-original'}}
            api = unittest.mock.Mock(); api.request.return_value = owner
            reader = unittest.mock.Mock(); reader.request.return_value = reader_row
            with patch.object(runtime, 'Api', return_value=reader):
                check.verify_organization_fixture(api, fixture)
                self.assertIn(unittest.mock.call('PUT', '/project/_favorite', fixture['favoriteOperations'][0]), api.request.call_args_list)
                for field, value in [('groupName', 'Changed'), ('tags', ['a']), ('archivedTime', None),
                    ('organizationVersion', 3), ('favorite', True), ('favoriteVersion', 0), ('cover', '/other.png'), ('updatedAt', 31)]:
                    with self.subTest(field=field):
                        api.request.return_value = dict(owner, **{field: value})
                        with self.assertRaisesRegex(runtime.VerificationFailure, 'Restored organization'):
                            check.verify_organization_fixture(api, fixture)

    def test_demo_fixture_uses_real_parent_and_child_pages_then_reorders_and_disables(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'
            jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            pages = [{'pageId': 'parent', 'folder': False, 'children': [
                {'pageId': 'child', 'folder': False, 'children': []}]}]
            rows = {}
            counter = 0
            api = unittest.mock.Mock()
            def respond(method, path, payload=None):
                nonlocal counter
                if path.endswith('/pages'):
                    return pages
                if path.endswith('/_delete'):
                    return True
                if method == 'POST':
                    counter += 1
                    identity = 'script-' + str(counter)
                    rows[identity] = dict(payload, id=identity, configVersion=0)
                else:
                    identity = path.rsplit('/', 1)[1]
                    rows[identity] = dict(payload, id=identity, configVersion=rows[identity]['configVersion'] + 1)
                return copy.deepcopy(rows[identity])
            api.request.side_effect = respond
            fixture = check.create_demo_script_fixture(api, 'version', 'fixed', {'token': 'capability'})
            original = fixture['operations'][0][2]
            self.assertEqual(['parent', 'child', 'parent'], [step['pageId'] for step in original['steps']])
            self.assertEqual(['revisit', 'first', 'second'], [step['id'] for step in fixture['row']['steps']])
            self.assertEqual(2, fixture['row']['configVersion'])
            self.assertFalse(fixture['row']['shareEnabled'])
            self.assertTrue(fixture['shared']['shareEnabled'])
            self.assertEqual(3, len({payload['clientRequestId'] for _, _, payload in fixture['operations']}))
            api.request.side_effect = lambda *args: [{'pageId': 'directory', 'folder': True}]
            with self.assertRaisesRegex(runtime.VerificationFailure, 'two actual openable'):
                check.create_demo_script_fixture(api, 'version', 'fixed', {})

    def test_demo_restore_checks_full_rows_current_keys_and_exact_public_projections(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'
            jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            path = '/fixed/demo-scripts'
            row = {'id': 'private', 'projectId': 'project', 'versionId': 'version', 'revisionId': 'fixed',
                   'title': 'Private demo', 'objective': 'Original goal', 'prerequisites': '', 'resetNote': '',
                   'steps': [{'id': 'a', 'pageId': 'page', 'narration': 'First'},
                             {'id': 'b', 'pageId': 'page', 'narration': 'Repeat'}],
                   'shareEnabled': False, 'configVersion': 2, 'createdAt': 1, 'updatedAt': 3}
            shared = dict(row, id='shared', title='Shared demo', shareEnabled=True, configVersion=0, createdAt=2)
            fixture = {'path': path, 'row': row, 'shared': shared,
                       'operations': [('POST', path, {'clientRequestId': 'original-private'})],
                       'sharedPayload': {'clientRequestId': 'original-shared'},
                       'deleted': {'id': 'deleted'}, 'deletedPayload': {'clientRequestId': 'deleted-create'},
                       'delete': {'clientRequestId': 'original-delete', 'expectedVersion': 0},
                       'grant': {'token': 'share-token', 'accessKey': 'grant-key'}}
            summary_fields = ('id', 'projectId', 'versionId', 'revisionId', 'title', 'shareEnabled',
                              'configVersion', 'createdAt', 'updatedAt')
            public_fields = ('id', 'revisionId', 'title', 'objective', 'prerequisites', 'resetNote', 'steps')
            def run(*, changed_row=False, leak_summary=False, leak_public=False, reordered=False):
                api = unittest.mock.Mock(origin='http://127.0.0.1:1')
                internal = copy.deepcopy(row)
                if changed_row: internal['steps'].reverse()
                summaries = [{field: item[field] for field in summary_fields} for item in (shared, row)]
                if leak_summary: summaries[0]['steps'] = shared['steps']
                def respond(method, target, payload=None, expected=200):
                    if target.endswith('/_query'): return {'total': 2, 'data': summaries}
                    if expected != 200: return {}
                    if target.endswith('/_delete'): return True
                    return copy.deepcopy(shared if target.endswith('/shared') or payload == fixture['sharedPayload'] else internal)
                api.request.side_effect = respond
                anonymous = unittest.mock.Mock()
                public_summary = {field: shared[field] for field in ('id', 'revisionId', 'title')}
                context = {field: copy.deepcopy(shared[field]) for field in public_fields}
                if leak_public: context['configVersion'] = 0
                if reordered: context['steps'].reverse()
                anonymous.request.side_effect = [{'total': 1, 'data': [public_summary]}, context, {}]
                with patch.object(runtime, 'Api', return_value=anonymous):
                    check.verify_demo_script_fixture(api, fixture)
                self.assertEqual('grant-key', anonymous.request.call_args_list[0].args[2]['accessKey'])
            run()
            for changes in ({'changed_row': True}, {'leak_summary': True}, {'leak_public': True}, {'reordered': True}):
                with self.subTest(changes=changes), self.assertRaises(runtime.VerificationFailure):
                    run(**changes)

    def test_approval_fixture_uses_four_actions_and_original_keys_after_withdraw_and_revoke(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'
            jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            initial = {'versionId': 'version', 'revisionId': 'fixed', 'configVersion': 0,
                       'approved': False, 'published': False, 'revision': {'manifestSha256': 'a' * 64}}
            transitions = [('approve', True, False), ('publish', True, True), ('withdraw', True, False),
                           ('revoke', False, False), ('approve', True, False), ('publish', True, True)]
            states = [dict(initial, configVersion=index + 1, approved=approved, published=published)
                      for index, (_, approved, published) in enumerate(transitions)]
            history = {'total': 6, 'data': [{'configVersion': index + 1, 'action': item[0]}
                                           for index, item in reversed(list(enumerate(transitions)))]}
            api = unittest.mock.Mock()
            api.request.side_effect = [initial, states[0], states[1], states[2], states[2],
                                       states[3], states[3], states[4], states[5], history]
            fixture = check.create_approval_fixture(api, 'version', 'fixed')
            self.assertEqual(states[-1], fixture['state'])
            self.assertEqual(history, fixture['history'])
            calls = api.request.call_args_list
            self.assertEqual(calls[2], calls[4])
            self.assertEqual(calls[1], calls[6])
            self.assertEqual(list(range(6)), [p['expectedVersion'] for p in fixture['operations']])
            self.assertEqual(6, len({p['clientRequestId'] for p in fixture['operations']}))
            self.assertTrue(all('/versions/version/revisions/fixed/approval' in call.args[1] for call in calls))
            api.request.side_effect = [initial, states[0], states[1], states[2], states[1]]
            with self.assertRaisesRegex(runtime.VerificationFailure, 'revived'):
                check.create_approval_fixture(api, 'version', 'fixed')

    def test_approval_restore_rejects_changed_state_summary_audit_and_old_key_history_append(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'
            jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            state = {'revisionId': 'fixed', 'configVersion': 6, 'approved': True, 'published': True,
                     'revision': {'manifestSha256': 'a' * 64},
                     'approval': {'actorName': 'Original member', 'note': 'Original note'}}
            history = {'total': 6, 'data': [{'id': 'original-decision', 'note': 'Original note'}]}
            fixture = {'path': '/fixed/approval', 'state': state, 'history': history,
                       'operations': [{'clientRequestId': 'original-key', 'expectedVersion': 0, 'action': 'approve'}]}
            api = unittest.mock.Mock()
            api.request.side_effect = [copy.deepcopy(state), copy.deepcopy(state), copy.deepcopy(history)]
            check.verify_approval_fixture(api, fixture)
            self.assertEqual(fixture['operations'][0], api.request.call_args_list[1].args[2])
            for changed in (dict(state, revision={'manifestSha256': 'b' * 64}), dict(state, published=False),
                            dict(state, approval={'actorName': 'Renamed member', 'note': 'Original note'})):
                with self.subTest(changed=changed):
                    api.request.side_effect = [changed]
                    with self.assertRaises(runtime.VerificationFailure):
                        check.verify_approval_fixture(api, fixture)
            api.request.side_effect = [state, dict(state, configVersion=1)]
            with self.assertRaisesRegex(runtime.VerificationFailure, 'current full state'):
                check.verify_approval_fixture(api, fixture)
            api.request.side_effect = [state, state, dict(history, total=7)]
            with self.assertRaisesRegex(runtime.VerificationFailure, 'immutable decision history'):
                check.verify_approval_fixture(api, fixture)

    def test_current_coverage_fails_for_missing_extra_empty_or_corrupt_business_table(self):
        manifest = {table: {'count': 1, 'contentMd5': 'a' * 32} for table in runtime.TABLES}
        self.assertEqual(38, len(runtime.BUSINESS_TABLES))
        self.assertIn('p_project_tag', runtime.BUSINESS_TABLES)
        self.assertIn('p_project_favorite', runtime.BUSINESS_TABLES)
        self.assertIn('p_page_delivery', runtime.BUSINESS_TABLES)
        self.assertIn('p_revision_approval', runtime.BUSINESS_TABLES)
        self.assertIn('p_demo_script', runtime.BUSINESS_TABLES)
        self.assertIn('p_component_code_mapping', runtime.BUSINESS_TABLES)
        self.assertIn('p_design_token_package', runtime.BUSINESS_TABLES)
        self.assertIn('p_element_description', runtime.BUSINESS_TABLES)
        self.assertIn('p_viewport_check', runtime.BUSINESS_TABLES)
        self.assertIn('p_revision_material', runtime.BUSINESS_TABLES)
        self.assertEqual(manifest, runtime.current_manifest(manifest))
        for table in runtime.BUSINESS_TABLES:
            for mutation in ('omit', 'empty', 'hash'):
                broken = copy.deepcopy(manifest)
                if mutation == 'omit':
                    del broken[table]
                elif mutation == 'empty':
                    broken[table]['count'] = 0
                else:
                    broken[table]['contentMd5'] = 'invalid'
                with self.subTest(table=table, mutation=mutation), self.assertRaises(runtime.VerificationFailure):
                    runtime.current_manifest(broken)
        with self.assertRaises(runtime.VerificationFailure):
            runtime.current_manifest(dict(manifest, p_unreviewed={'count': 1, 'contentMd5': 'a' * 32}))

    def test_prestart_row_comparison_rejects_changed_receipt_content_and_every_omitted_table(self):
        before = {table: {'original-id': 'original-full-row-hash'} for table in runtime.TABLES}
        runtime.compare_database_rows(before, copy.deepcopy(before))
        for table in runtime.TABLES:
            for mutation in ('omit', 'id', 'content'):
                after = copy.deepcopy(before)
                if mutation == 'omit':
                    del after[table]
                elif mutation == 'id':
                    after[table] = {'replacement-id': 'original-full-row-hash'}
                else:
                    after[table]['original-id'] = 'changed-fingerprint-or-content'
                with self.subTest(table=table, mutation=mutation), self.assertRaises(runtime.VerificationFailure):
                    runtime.compare_database_rows(before, after)

    def test_expiry_requires_exact_registration_before_start_and_never_allows_durable_audit_loss(self):
        before = {table: {} for table in runtime.REVIEW_TABLES}
        before['p_review_session'] = {'registered': {'hash': 'a', 'deadline': 99},
                                      'other': {'hash': 'b', 'deadline': 99}}
        after = copy.deepcopy(before)
        del after['p_review_session']['registered']
        with self.assertRaises(runtime.VerificationFailure):
            runtime.compare_review_rows(before, after, 100)
        allowed = {'p_review_session': {'registered'}}
        runtime.compare_review_rows(before, after, 100, allowed)
        del after['p_review_session']['other']
        with self.assertRaises(runtime.VerificationFailure):
            runtime.compare_review_rows(before, after, 100, allowed)
        self.assertNotIn('p_review_association', runtime.REVIEW_TABLES)
        self.assertIn('p_review_association', runtime.BUSINESS_TABLES)

    def test_current_database_table_discovery_fails_closed_before_reading_partial_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'
            jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            with patch.object(check, 'sql', return_value='p_project') as sql:
                with self.assertRaises(runtime.VerificationFailure):
                    check.database_manifest('owned')
                self.assertEqual(1, sql.call_count)
            check.processes.append(object())
            with self.assertRaises(runtime.VerificationFailure):
                check.expire_current_rows('owned', {})
            with self.assertRaises(runtime.VerificationFailure):
                check.verify_sql_diagnostics('owned', {})

    def test_sql_negative_uses_reply_receipt_and_rejects_zero_row_mutation_with_case_number(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'
            jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            fixture = {'usabilityStudies': {'row': {'id': 'study'},
                           'participants': [{'row': {'id': 'submitted'}}, {'row': {'id': 'withdrawn'}}]},
                       'tokenPackages': {'row': {'id': 'token-package'}},
                       'codeMappings': {'rows': [{'id': 'code-card'}]}, 'pageTraceability': {'relation': {'id': 'edge'}, 'context': {'id': 'context'}, 'decision': {'id': 'decision'}},
                       'associations': {'active': {'id': 'association', 'targetGuestId': 'target'}},
                       'project': {'id': 'organization-project'}, 'organization': {'member': {'id': 'reader'}},
                       'demoScripts': {'shared': {'id': 'demo'}},
                       'copyProject': {'id': 'foreign-project'}, 'copyVersion': {'revisionId': 'foreign-revision'},
                       'copy': [{'context': {'snapshotId': 'snapshot'}, 'entries': {'data': [{'id': 'entry'}]}},
                                {'context': {'snapshotId': 'empty'}}], 'visits': {'recentId': 'visit'},
                       'review': {'discussion': {'thread': {'id': 'thread'},
                           'messages': [{'id': 'initial-without-receipt'}], 'operations': [
                               {'method': 'POST', 'path': 'threads', 'id': 'thread'},
                               {'method': 'POST', 'path': '/thread/messages', 'id': 'actual-reply'}]}}}
            baseline = f'business_table_coverage|{len(runtime.BUSINESS_TABLES)}|current'
            def script(database, name, mutation='', variables=None, zero_reply=False):
                if variables:
                    self.assertEqual('actual-reply', variables['message'])
                invalidation = 'enabled=false' in mutation
                if name == 'snapshot-db.sql':
                    no_change = zero_reply and "resource_id=:'message'" in mutation
                    return baseline if not mutation or no_change else baseline + '-changed'
                if name == 'export-storage-manifest.sql':
                    return json.dumps({'integrityErrors': int(bool(mutation) and not invalidation),
                                       'referencesComplete': True, 'demoScriptReferencesChecked': True,
                                       'organizationReferencesChecked': True, 'pageTraceabilityReferencesChecked': True,
                                       'codeMappingReferencesChecked': True, 'tokenPackageReferencesChecked': True,
                                       'usabilityReferencesChecked': True})
                return 'current_scope_errors|' + str(int(bool(mutation) and not invalidation))
            with patch.object(check, 'sql_script', side_effect=script):
                check.verify_sql_diagnostics('owned', fixture)
            self.assertEqual(47, check.report['operationalSql']['crossScopeNegativeCases'])
            with patch.object(check, 'sql_script', side_effect=lambda *args: script(*args, zero_reply=True)):
                with self.assertRaisesRegex(runtime.VerificationFailure, 'SQL negative case 10 did not change'):
                    check.verify_sql_diagnostics('owned', fixture)

    def test_restored_associated_write_preserves_authorship_history_replay_and_denial_scopes(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'fixture.jar'
            jar.write_bytes(b'not executed')
            check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), Path(temporary))
            target = {'grant': {'revisionId': 'original-revision'}, 'cookie': 'original-cookie',
                      'identity': {'displayName': 'Actual target'}}
            thread = {'id': 'thread', 'authorName': 'Original author', 'state': 'open',
                      'anchor': {'x': 1}, 'updatedAt': 1}
            old = {'id': 'original', 'visibility': 'public'}
            internal = {'id': 'private-note', 'visibility': 'internal'}
            fixture = {'associations': {'target': target, 'revoked': {'sourceThreadId': 'revoked'}},
                       'copyVersion': {'revisionId': 'foreign-revision'}, 'review': {'discussion': {
                           'thread': thread, 'base': {'pageId': 'page'}, 'memberPath': '/member',
                           'messages': [old, internal]}}}
            saved = {'id': 'new-reply', 'threadId': 'thread', 'authorType': 'guest',
                     'verificationLevel': 'email', 'authorName': 'Actual target', 'visibility': 'public'}
            api = unittest.mock.Mock()
            api.request.return_value = {'data': [dict(thread, updatedAt=2)]}
            def run(changes=None, leak=False, changed_author=False):
                current_saved = dict(saved, **(changes or {}))
                responses = [(current_saved, None), (current_saved, None), ({}, None), ({}, None),
                             ({'total': 2 + leak, 'data': [old, saved] + ([internal] if leak else [])}, None)]
                api.request.return_value = {'data': [dict(thread, updatedAt=2,
                    authorName='Changed author' if changed_author else thread['authorName'])]}
                with patch.object(check, 'verify_review_identity', return_value={'csrfToken': 'proof'}), \
                        patch.object(check, 'review_call', side_effect=responses) as request, \
                        patch.object(check, 'sql', return_value='3'):
                    check.verify_restored_current_writes(api, fixture, 'restored')
                    self.assertEqual(5, request.call_count)
                    self.assertEqual([404, 403], [call.kwargs['expected'] for call in request.call_args_list[2:4]])
                    self.assertEqual('foreign-revision', request.call_args_list[3].args[1]['revisionId'])
            run()
            for options in ({'changes': {'authorName': 'Wrong target'}},
                            {'changes': {'verificationLevel': 'unverified'}},
                            {'changes': {'visibility': 'internal'}}, {'leak': True}, {'changed_author': True}):
                with self.subTest(options=options), self.assertRaises(runtime.VerificationFailure):
                    run(**options)

    def test_new_business_payloads_and_visit_tickets_are_registered_for_redaction(self):
        values = {key: 'private-' + key for key in ('visitTicket', 'clientRequestId', 'businessTerm',
                  'note', 'actorName', 'requirementUrl', 'evidenceReference', 'title', 'objective',
                  'prerequisites', 'resetNote', 'narration')}
        seen = set()
        runtime.Api('http://127.0.0.1:1', seen).remember_secrets(values)
        self.assertEqual(set(values.values()), seen)


class ReviewIdentityRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='runtime-review-test-')
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        jar = root / 'fixture.jar'
        jar.write_bytes(b'not executed')
        self.check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), root)
        self.grant = {'token': 'share', 'revisionId': 'fixed-r1', 'accessKey': 'grant-proof'}
        self.api = runtime.Api('http://127.0.0.1:10001', self.check.secrets_seen)

    def test_review_http_uses_scoped_cookie_csrf_and_explicit_ui_origin_without_admin_token(self):
        self.api.token = 'internal-token-must-not-leak'
        self.check.ui_port = 10002
        headers = Message()
        headers.add_header('Set-Cookie', 'prototype-review=rotated; HttpOnly; SameSite=Strict; '
                           'Path=/api/prototype-hub/public/shares/share/review')
        body = json.dumps({'success': True, 'result': {'csrfToken': 'next-csrf'}}).encode()
        with patch.object(runtime.Api, 'request', return_value=(body, headers)) as request:
            result, cookie = self.check.review_call(self.api, self.grant, 'context', {},
                                                    cookie='old-cookie', csrf='old-csrf')
        self.assertEqual('rotated', cookie)
        self.assertEqual('next-csrf', result['csrfToken'])
        args = request.call_args
        self.assertEqual('/api/prototype-hub/public/shares/share/review/context', args.args[1])
        self.assertEqual({'revisionId': 'fixed-r1', 'accessKey': 'grant-proof'}, args.args[2])
        self.assertEqual({'Origin': 'http://127.0.0.1:10002', 'Cookie': 'prototype-review=old-cookie',
                          'X-Review-CSRF': 'old-csrf'}, args.kwargs['headers'])
        for value in ('old-cookie', 'old-csrf', 'rotated', 'next-csrf'):
            self.assertIn(value, self.check.secrets_seen)
        self.assertNotIn('internal-token-must-not-leak', str(args))

    def test_restore_identity_check_rejects_anonymous_or_wrong_guest_instead_of_passing(self):
        fixture = {'grant': self.grant, 'cookie': 'private-cookie', 'guestId': 'stable-guest'}
        for session in (None, {'guestId': 'different-guest'}):
            with self.subTest(authenticated=session is not None), \
                    patch.object(self.check, 'review_call', return_value=({'session': session}, None)):
                with self.assertRaises(runtime.VerificationFailure):
                    self.check.verify_review_identity(self.api, fixture)

    def test_identity_requires_original_profile_level_and_expiration(self):
        identity = {'guestId': 'stable-guest', 'verificationLevel': 'unverified',
                    'expiresAt': int(time.time() * 1000) + 60000, 'displayName': 'Original'}
        fixture = {'grant': self.grant, 'cookie': 'private-cookie', 'guestId': 'stable-guest',
                   'identity': identity}
        with patch.object(self.check, 'review_call', return_value=({'session': identity}, None)):
            self.check.verify_review_identity(self.api, fixture)
        for field, value in (('expiresAt', identity['expiresAt'] + 1),
                             ('verificationLevel', 'email'), ('displayName', 'Changed')):
            with self.subTest(field=field), patch.object(self.check, 'review_call',
                    return_value=({'session': dict(identity, **{field: value})}, None)):
                with self.assertRaises(runtime.VerificationFailure):
                    self.check.verify_review_identity(self.api, fixture)
        expired = dict(identity, expiresAt=1)
        with patch.object(self.check, 'review_call', return_value=({'session': expired}, None)):
            with self.assertRaises(runtime.VerificationFailure):
                self.check.verify_review_identity(self.api, dict(fixture, identity=expired))

    def test_review_http_preserves_put_and_registers_one_time_capabilities(self):
        token = secrets.token_urlsafe(32)
        body = json.dumps({'success': True, 'result': {'invitationToken': token}}).encode()
        with patch.object(runtime.Api, 'request', return_value=(body, Message())) as request:
            self.check.review_call(self.api, self.grant, 'threads/id/status', {}, method='PUT')
        self.assertEqual('PUT', request.call_args.args[0])
        self.assertIn(token, self.check.secrets_seen)

    def test_all_nine_tables_are_nonempty_and_a_missing_table_cannot_pass(self):
        expected = {'p_review_guest', 'p_review_challenge', 'p_review_session', 'p_review_rate',
                    'p_review_unverified_guest', 'p_review_invitation', 'p_review_thread',
                    'p_review_message', 'p_review_event'}
        self.assertEqual(expected, set(runtime.REVIEW_TABLES))
        self.assertTrue(expected <= set(runtime.TABLES))
        manifest = {table: {'count': 1, 'contentMd5': 'a' * 32} for table in expected}
        self.assertEqual(manifest, runtime.review_manifest(manifest))
        for table in expected:
            for missing in (True, False):
                broken = copy.deepcopy(manifest)
                if missing:
                    del broken[table]
                else:
                    broken[table]['count'] = 0
                with self.subTest(table=table, missing=missing):
                    with self.assertRaises(runtime.VerificationFailure):
                        runtime.review_manifest(broken)
        with patch.object(self.check, 'sql', side_effect=['\n'.join(runtime.BUSINESS_TABLES)] +
                [json.dumps({'count': 1, 'contentMd5': 'a' * 32})] * len(runtime.TABLES)) as sql:
            self.check.database_manifest('restored')
        self.assertEqual(len(runtime.TABLES) + 1, sql.call_count)
        self.assertTrue(all(call.args[0] == 'restored' for call in sql.call_args_list))

    def test_startup_only_allows_expired_deletions_not_rewrites_new_rows_or_omitted_tables(self):
        before = {table: {'id': {'hash': 'digest', 'deadline': None}}
                  for table in runtime.REVIEW_TABLES}
        self.assertTrue(all(value == 0 for value in runtime.compare_review_rows(before, before, 100).values()))
        for table in runtime.REVIEW_TABLES:
            for change in ('delete', 'rewrite', 'insert', 'omit'):
                after = copy.deepcopy(before)
                if change == 'delete':
                    after[table].clear()
                elif change == 'rewrite':
                    after[table]['id']['hash'] = 'different'
                elif change == 'insert':
                    after[table]['new'] = {'hash': 'new', 'deadline': 200}
                else:
                    del after[table]
                with self.subTest(table=table, change=change):
                    with self.assertRaises(runtime.VerificationFailure):
                        runtime.compare_review_rows(before, after, 100)
        before['p_review_challenge']['id']['deadline'] = 99
        after = copy.deepcopy(before)
        after['p_review_challenge'].clear()
        self.assertEqual(1, runtime.compare_review_rows(before, after, 100,
            runtime.registered_review_expiry(before, 100))['p_review_challenge'])
        with self.assertRaises(runtime.VerificationFailure):
            runtime.compare_review_rows(before, after, 98)

    def invitation_fixture(self):
        items = {}
        for state in ('pending', 'revoked', 'consumed'):
            items[state] = {'email': state + '@example.invalid', 'token': secrets.token_urlsafe(32),
                            'row': {'id': state, 'status': state, 'configVersion': 0,
                                    'expiresAt': int(time.time() * 1000) + 60000}}
        return {'grant': self.grant, 'share': {'id': 'share'}, 'versionId': 'version',
                'projectId': 'project', 'policy': {'configVersion': 1}, 'invitations': items}

    def test_invitation_check_rejects_expiry_state_binding_or_private_response_changes(self):
        fixture = self.invitation_fixture()
        page = {'total': 3, 'data': [item['row'] for item in fixture['invitations'].values()]}
        with patch.object(self.api, 'request', return_value=page), \
                patch.object(self.check, 'sql', return_value='1') as sql:
            self.check.verify_review_invitations(self.api, fixture, 'restored')
        self.assertTrue(all(call.args[0] == 'restored' for call in sql.call_args_list))
        for field, value in (('status', 'pending'), ('expiresAt', 1), ('configVersion', 2),
                             ('maskedEmail', fixture['invitations']['revoked']['email'])):
            broken = copy.deepcopy(page)
            broken['data'][1][field] = value
            with self.subTest(field=field), patch.object(self.api, 'request', return_value=broken), \
                    patch.object(self.check, 'sql', return_value='1'):
                with self.assertRaises(runtime.VerificationFailure):
                    self.check.verify_review_invitations(self.api, fixture, 'restored')
        fixture['invitations']['pending']['row']['expiresAt'] = 1
        with patch.object(self.api, 'request', return_value=page), patch.object(self.check, 'sql', return_value='1'):
            with self.assertRaises(runtime.VerificationFailure):
                self.check.verify_review_invitations(self.api, fixture, 'restored')
        fixture = self.invitation_fixture()
        page = {'total': 3, 'data': [item['row'] for item in fixture['invitations'].values()]}
        with patch.object(self.api, 'request', return_value=page), patch.object(self.check, 'sql', return_value='0'):
            with self.assertRaises(runtime.VerificationFailure):
                self.check.verify_review_invitations(self.api, fixture, 'restored')

    def test_expired_pending_otp_is_not_renewed_to_make_restore_pass(self):
        fixture = self.invitation_fixture()
        fixture['invitations']['pending']['challenge'] = {'expiresAt': 1}
        self.check.mailbox = argparse.Namespace(messages=[])
        with patch.object(self.check, 'review_call',
                return_value=({'csrfToken': 'proof', 'code': 'PROTOTYPE_HUB_REVIEW_INVITATION_INVALID'}, 'cookie')) as call:
            with self.assertRaises(runtime.VerificationFailure):
                self.check.verify_restored_invitation(self.api, fixture)
        self.assertFalse(any(args.args[2] == 'verify' for args in call.call_args_list))

    def test_discussion_check_rejects_internal_visibility_leaks_or_missing_atomic_receipts(self):
        public = [{'id': str(index), 'visibility': 'public'} for index in range(3)]
        note = {'id': 'internal-note', 'visibility': 'internal'}
        fixture = {'grant': self.grant, 'cookie': 'wrong', 'unverified': {'cookie': 'author'},
                   'projectId': 'project', 'versionId': 'version', 'discussion': {
                       'base': {'revisionId': 'fixed-r1', 'pageId': 'page'}, 'memberPath': '/member',
                       'thread': {'id': 'thread'}, 'messages': public + [note], 'noteId': note['id'],
                       'operations': []}}
        summary = {'messages': 4, 'receipts': 5, 'receiptHash': 'a' * 32, 'bound': True, 'events': sorted([
            'thread.created', 'message.created', 'note.created', 'thread.resolved',
            'message.created', 'thread.reopened'])}
        def run(leak=False, receipts=5, foreign=False, receipt_hash='a' * 32,
                access_mode='own', can_reopen=False):
            def review(api, grant, operation, payload, **kwargs):
                if operation == 'context':
                    return {'csrfToken': 'csrf'}, 'anonymous'
                if kwargs.get('expected') in (403, 404):
                    return {'success': False}, None
                if operation == 'threads/_query':
                    rows = [] if kwargs['cookie'] == 'wrong' and not foreign else [
                        {'id': 'thread', 'accessMode': access_mode, 'canReopen': can_reopen}]
                else:
                    rows = public + ([note] if leak else [])
                return {'total': len(rows), 'data': rows}, None
            with patch.object(self.check, 'verify_review_identity', return_value={'csrfToken': 'csrf'}), \
                    patch.object(self.check, 'review_call', side_effect=review), \
                    patch.object(self.api, 'request', return_value={'total': 4, 'data': public + [note]}), \
                    patch.object(self.check, 'sql', return_value=json.dumps(dict(summary, receipts=receipts, receiptHash=receipt_hash))):
                return self.check.verify_review_discussion(self.api, fixture, 'restored')
        self.assertEqual(summary, run())
        for options in ({'leak': True}, {'receipts': 4}, {'foreign': True}, {'receipt_hash': 'b' * 32},
                        {'access_mode': 'associated'}, {'can_reopen': True}):
            with self.subTest(options=options), self.assertRaises(runtime.VerificationFailure):
                run(**options)

    def test_browser_created_invitation_tokens_are_redacted_without_be_api_capture(self):
        token = secrets.token_urlsafe(32)
        for value in ('https://example.invalid/share#reviewInvitation=' + token,
                      '#review-invitation=' + token,
                      json.dumps({'invitationToken': token}),
                      json.dumps(json.dumps({'invitationToken': token}))):
            with self.subTest(kind=value[:25]):
                self.assertNotIn(token, self.check.redact(value))

    def test_review_image_multipart_has_exact_json_and_image_parts_without_filename_authority(self):
        payload = {'revisionId': 'fixed-r1', 'pageId': 'page', 'text': 'Private fixture',
                   'clientRequestId': 'stable-key'}
        content = runtime.sample_png((20, 70, 110, 255))
        body, content_type = runtime.review_image_multipart(payload, content)
        message = BytesParser(policy=policy.default).parsebytes(
            ('Content-Type: ' + content_type + '\r\nMIME-Version: 1.0\r\n\r\n').encode() + body)
        parts = list(message.iter_parts())
        self.assertEqual(['request', 'image'], [part.get_param('name', header='content-disposition') for part in parts])
        self.assertEqual(payload, json.loads(parts[0].get_payload(decode=True)))
        self.assertEqual('application/json', parts[0].get_content_type())
        self.assertEqual(content, parts[1].get_payload(decode=True))
        self.assertEqual('ignored.bin', parts[1].get_filename())
        self.assertEqual('application/octet-stream', parts[1].get_content_type())

    def test_public_image_upload_keeps_scoped_cookie_csrf_and_never_inherits_admin_token(self):
        self.api.token = 'must-not-leak'
        response = json.dumps({'success': True, 'result': {'id': 'message'}}).encode()
        with patch.object(runtime.Api, 'request', return_value=(response, Message())) as call:
            self.check.review_call(self.api, self.grant, 'threads/thread/messages-with-image',
                {'pageId': 'page', 'text': 'Private fixture'}, cookie='review-cookie', csrf='proof',
                image=runtime.sample_png((0, 0, 0, 255)))
        headers = call.call_args.kwargs['headers']
        self.assertEqual('prototype-review=review-cookie', headers['Cookie'])
        self.assertEqual('proof', headers['X-Review-CSRF'])
        self.assertTrue(headers['Content-Type'].startswith('multipart/form-data; boundary='))
        self.assertNotIn('X-Access-Token', headers)
        self.assertNotIn('must-not-leak', str(call.call_args))
        self.assertIn('Private fixture', self.check.secrets_seen)

    def test_private_image_bytes_and_every_security_header_are_required(self):
        content = runtime.sample_png((1, 2, 3, 255))
        headers = {'Content-Type': 'image/png', 'X-Content-Type-Options': 'nosniff',
                   'Cache-Control': 'private, no-store', 'Content-Disposition': 'inline; filename="image.png"'}
        self.check.verify_image_content(content, headers, content)
        for field, invalid in (('Content-Type', 'text/html'), ('X-Content-Type-Options', ''),
                               ('Cache-Control', 'private'), ('Cache-Control', 'no-store'),
                               ('Content-Disposition', 'inline; filename="customer.png"')):
            with self.subTest(field=field, invalid=invalid), self.assertRaises(runtime.VerificationFailure):
                self.check.verify_image_content(content, dict(headers, **{field: invalid}), content)
        with self.assertRaises(runtime.VerificationFailure):
            self.check.verify_image_content(content + b'changed', headers, content)

    def test_image_restore_checks_original_db_scope_metadata_and_public_internal_reads(self):
        images = [{'id': level, 'visibility': level, 'content': runtime.sample_png((color, 0, 0, 255))}
                  for level, color in (('public', 10), ('internal', 30))]
        rows = [{'id': image['id'], 'projectId': 'project', 'versionId': 'version',
                 'revisionId': 'fixed-r1', 'threadId': 'thread',
                 'storagePath': 'projects/project/review-images/' + str(index) * 43 + '/image',
                 'contentType': 'image/png', 'size': len(image['content']),
                 'sha256': hashlib.sha256(image['content']).hexdigest(), 'width': 1, 'height': 1}
                for index, image in enumerate(images)]
        fixture = {'grant': self.grant, 'unverified': {'cookie': 'author'}, 'projectId': 'project',
                   'versionId': 'version', 'discussion': {'base': {'revisionId': 'fixed-r1', 'pageId': 'page'},
                   'thread': {'id': 'thread'}, 'memberPath': '/member', 'images': images}}
        headers = {'Content-Type': 'image/png', 'X-Content-Type-Options': 'nosniff',
                   'Cache-Control': 'private, no-store', 'Content-Disposition': 'inline; filename=image.png'}
        def read(method, path, payload, **kwargs):
            self.assertEqual('POST', method)
            if kwargs.get('expected') == 404:
                self.assertIn('/internal/image', path)
                return b'not disclosed', {}
            image = next(image for image in images if '/' + image['id'] + '/image' in path)
            return image['content'], headers
        with patch.object(self.check, 'sql', return_value=json.dumps(rows)), \
                patch.object(runtime.Api, 'request', side_effect=read) as call:
            self.check.verify_review_images(self.api, fixture, 'restored', {'csrfToken': 'proof'})
        self.assertEqual(4, call.call_count)
        self.assertEqual(404, call.call_args.kwargs['expected'])
        self.assertNotIn('X-Access-Token', call.call_args.kwargs['headers'])
        for field, value in (('sha256', 'b' * 64), ('storagePath', '../outside'),
                             ('versionId', 'foreign'), ('size', 1), ('width', 2)):
            modified = copy.deepcopy(rows)
            modified[0][field] = value
            with self.subTest(field=field), patch.object(self.check, 'sql', return_value=json.dumps(modified)), \
                    self.assertRaises(runtime.VerificationFailure):
                self.check.verify_review_images(self.api, fixture, 'restored', {'csrfToken': 'proof'})


class RuntimeSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='runtime-safety-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.jar = self.root / 'fixture.jar'
        self.jar.write_bytes(b'unit boundary; never launched')
        self.secret = secrets.token_urlsafe(30)

    def args(self):
        return argparse.Namespace(jar=self.jar, java=Path(sys.executable),
                                  postgres_image='postgres:16-alpine',
                                  report=self.root / 'report.json', frontend_dir=None)

    def main_with_failure(self, *, execution_error=None, cleanup_error=None):
        check = runtime.RuntimeCheck(self.args(), self.root)
        secret = self.secret
        check.secrets_seen.add(secret)
        with patch.object(runtime, 'arguments', return_value=self.args()), \
                patch.object(runtime, 'RuntimeCheck', return_value=check), \
                patch.object(check, 'execute', side_effect=execution_error), \
                patch.object(check, 'cleanup', side_effect=cleanup_error) as cleanup, \
                contextlib.redirect_stdout(io.StringIO()) as output:
            result = runtime.main([])
        return result, check, cleanup, output.getvalue(), secret

    def test_failed_execution_cleans_owned_resources_and_returns_failure(self):
        secret = self.secret
        result, check, cleanup, output, _ = self.main_with_failure(
            execution_error=runtime.VerificationFailure('Unable to use ' + secret))
        self.assertEqual(1, result)
        cleanup.assert_called_once()
        self.assertEqual('failed', check.report['status'])
        self.assertNotIn(secret, output)
        self.assertNotIn(secret, self.args().report.read_text())

    def test_cleanup_failure_cannot_be_reported_as_success(self):
        result, check, cleanup, output, _ = self.main_with_failure(
            cleanup_error=runtime.VerificationFailure('Cleanup failed for owned process'))
        self.assertEqual(1, result)
        cleanup.assert_called_once()
        self.assertEqual('failed', check.report['status'])
        self.assertIn('cleanupError', json.loads(output))

    def test_cleanup_error_cannot_disclose_invitation_tokens_in_report_or_stdout(self):
        token = secrets.token_urlsafe(32)
        result, _, _, output, _ = self.main_with_failure(cleanup_error=runtime.VerificationFailure(
            'Failed cleanup at /share#reviewInvitation=' + token))
        self.assertEqual(1, result)
        self.assertNotIn(token, output)
        self.assertNotIn(token, self.args().report.read_text())

    def test_unexpected_exception_replaces_a_previous_passed_report(self):
        self.args().report.write_text('{"status":"passed"}')
        result, check, cleanup, output, secret = self.main_with_failure(
            execution_error=TypeError('Unexpected external data with private values'))
        self.assertEqual(1, result)
        cleanup.assert_called_once()
        report = json.loads(self.args().report.read_text())
        self.assertEqual('failed', report['status'])
        self.assertIn('TypeError', report['error'])
        self.assertNotIn('private values', output)

    def test_keyboard_interrupt_still_cleans_owned_resources(self):
        check = runtime.RuntimeCheck(self.args(), self.root)
        with patch.object(runtime, 'arguments', return_value=self.args()), \
                patch.object(runtime, 'RuntimeCheck', return_value=check), \
                patch.object(check, 'execute', side_effect=KeyboardInterrupt), \
                patch.object(check, 'cleanup') as cleanup:
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(130, runtime.main([]))
        cleanup.assert_called_once()
        self.assertEqual('failed', json.loads(self.args().report.read_text())['status'])

    def test_cleanup_stops_only_registered_child_processes(self):
        owned = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'],
                                 start_new_session=True)
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'],
                                     start_new_session=True)
        try:
            check = runtime.RuntimeCheck(self.args(), self.root)
            check.processes.append(owned)
            check.cleanup()
            self.assertIsNotNone(owned.poll())
            self.assertIsNone(unrelated.poll())
        finally:
            for process in (owned, unrelated):
                if process.poll() is None:
                    process.terminate()
                process.wait(timeout=5)

    def test_frontend_group_is_stopped_when_wait_times_out(self):
        check = runtime.RuntimeCheck(self.args(), self.root)
        check.args.frontend_dir = self.root
        cli = self.root / 'node_modules/@playwright/test/cli.js'
        cli.parent.mkdir(parents=True)
        cli.touch()
        process = unittest.mock.Mock()
        process.wait.side_effect = subprocess.TimeoutExpired('frontend', 330)
        api = runtime.Api('http://127.0.0.1:1', set())
        with patch.object(runtime.subprocess, 'Popen', return_value=process) as start, \
                patch.object(check, 'stop') as stop:
            with self.assertRaises(subprocess.TimeoutExpired):
                check.frontend(api, self.root / 'sample.zip')
        self.assertTrue(start.call_args.kwargs['start_new_session'])
        self.assertEqual(['node', str(cli)], start.call_args.args[0][:2])
        self.assertIn('--global-timeout', start.call_args.args[0])
        self.assertIn(process, check.processes)
        stop.assert_called_once_with(process)

    def test_failed_container_creation_still_cleans_by_verified_name_and_label(self):
        check = runtime.RuntimeCheck(self.args(), self.root)
        with patch.object(check, 'command', side_effect=runtime.VerificationFailure('Docker timeout')):
            with self.assertRaises(runtime.VerificationFailure):
                check.postgres()
        identity = {'Id': 'owned-container-id', 'Config': {
            'Labels': {'com.prototype-hub.runtime-owner': check.container_owner}}}
        inspected = subprocess.CompletedProcess([], 0, json.dumps(identity).encode(), b'')
        with patch.object(runtime.subprocess, 'run', return_value=inspected) as inspect, \
                patch.object(check, 'command') as remove:
            check.cleanup()
        self.assertEqual(check.container_name, inspect.call_args.args[0][-1])
        remove.assert_called_once_with(['docker', 'rm', '-f', 'owned-container-id'])

    def test_container_with_different_ownership_is_never_removed(self):
        check = runtime.RuntimeCheck(self.args(), self.root)
        check.container_attempted = True
        inspected = subprocess.CompletedProcess([], 0, b'{"Id":"unrelated","Config":{"Labels":{}}}', b'')
        with patch.object(runtime.subprocess, 'run', return_value=inspected), \
                patch.object(check, 'command') as remove:
            with self.assertRaises(runtime.VerificationFailure):
                check.cleanup()
        remove.assert_not_called()

    def test_workspace_cleanup_failure_is_not_published_as_passed(self):
        check = runtime.RuntimeCheck(self.args(), self.root)
        directory = unittest.mock.MagicMock()
        directory.__enter__.return_value = str(self.root)
        directory.__exit__.side_effect = OSError('Cannot remove directory')
        with patch.object(runtime, 'arguments', return_value=self.args()), \
                patch.object(runtime, 'RuntimeCheck', return_value=check), \
                patch.object(runtime.tempfile, 'TemporaryDirectory', return_value=directory), \
                patch.object(check, 'execute'), patch.object(check, 'cleanup'), \
                contextlib.redirect_stdout(io.StringIO()):
            result = runtime.main([])
        self.assertEqual(1, result)
        report = json.loads(self.args().report.read_text())
        self.assertEqual('failed', report['status'])
        self.assertIn('cleanupError', report)

    def test_private_report_replaces_a_link_without_writing_its_target(self):
        protected = self.root / 'unrelated-data'
        protected.write_text('unchanged')
        report = self.root / 'report.json'
        report.symlink_to(protected)
        runtime.write_private(report, '{"status":"passed"}\n')
        self.assertEqual('unchanged', protected.read_text())
        self.assertFalse(report.is_symlink())
        self.assertEqual(0o600, stat.S_IMODE(report.stat().st_mode))

    def test_original_fixture_is_stable_and_includes_nested_relative_assets(self):
        first = runtime.sample_zip(self.root / 'first.zip')
        second = runtime.sample_zip(self.root / 'second.zip')
        self.assertEqual(runtime.digest_file(first), runtime.digest_file(second))
        with runtime.zipfile.ZipFile(first) as archive:
            self.assertEqual(5, len(archive.namelist()))
            self.assertIn(b'src="nested/child.html"', archive.read('index.html'))
            self.assertIn(b'Runtime fixture nested page', archive.read('nested/child.html'))
            self.assertIn(b'../assets/icon.svg', archive.read('nested/detail.html'))

    def test_internal_preview_exchanges_identity_then_reads_every_resource_anonymously(self):
        api = runtime.Api('http://127.0.0.1:1', set())
        api.token = self.secret
        version = {'id': 'version', 'previewUrl': None, 'runtimeBaseUrl': None}
        base = '/api/prototype-hub/runtime/version/' + runtime.secrets.token_hex(32)
        grant = {'runtimeBaseUrl': base, 'previewUrl': base + '/index.html',
                 'expiresAt': int(runtime.time.time() * 1000) + 300000}
        headers = {'Content-Security-Policy': 'sandbox allow-scripts allow-forms'}
        responses = [grant, (b'Runtime fixture home', headers),
                     (b'Runtime fixture nested page', headers),
                     ((runtime.FIXTURE / 'assets/style.css').read_bytes(), headers)]
        check = runtime.RuntimeCheck(self.args(), self.root)
        with patch.object(runtime.Api, 'request', autospec=True, side_effect=responses) as request:
            hashes = check.preview(api, version)
        self.assertEqual({'entry', 'child', 'css'}, set(hashes))
        self.assertEqual(4, request.call_count)
        exchange = request.call_args_list[0]
        self.assertEqual(self.secret, exchange.args[0].token)
        self.assertEqual(('POST', '/api/prototype-hub/versions/version/preview-session'),
                         exchange.args[1:])
        for read in request.call_args_list[1:]:
            self.assertIsNone(read.args[0].token)
            self.assertEqual('GET', read.args[1])

    def test_public_share_preview_uses_its_grant_without_member_session_exchange(self):
        api = runtime.Api('http://127.0.0.1:1', set())
        api.token = self.secret
        base = '/api/prototype-hub/public/shares/external/grant/runtime'
        grant = {'runtimeBaseUrl': base, 'previewUrl': base + '/index.html'}
        headers = {'Content-Security-Policy': 'sandbox allow-scripts allow-forms'}
        responses = [(b'Runtime fixture home', headers),
                     (b'Runtime fixture nested page', headers),
                     ((runtime.FIXTURE / 'assets/style.css').read_bytes(), headers)]
        check = runtime.RuntimeCheck(self.args(), self.root)
        with patch.object(runtime.Api, 'request', autospec=True, side_effect=responses) as request:
            check.preview(api, grant)
        self.assertEqual(3, request.call_count)
        for read in request.call_args_list:
            self.assertIsNone(read.args[0].token)
            self.assertEqual('GET', read.args[1])
            self.assertTrue(read.args[2].startswith(base))
        self.assertIn('p_project_member', runtime.TABLES)
        self.assertIn('p_preview_session', runtime.TABLES)
        self.assertIn('p_revision', runtime.TABLES)

    def test_historical_preview_selects_revision_without_using_current_pointer(self):
        api = runtime.Api('http://127.0.0.1:1', set())
        api.token = self.secret
        base = '/api/prototype-hub/runtime/version/' + runtime.secrets.token_hex(32)
        grant = {'runtimeBaseUrl': base, 'previewUrl': base + '/index.html',
                 'expiresAt': int(runtime.time.time() * 1000) + 300000}
        headers = {'Content-Security-Policy': 'sandbox allow-scripts'}
        responses = [grant, (b'Runtime fixture home', headers),
                     (b'Runtime fixture nested page', headers),
                     ((runtime.FIXTURE / 'assets/style.css').read_bytes(), headers)]
        check = runtime.RuntimeCheck(self.args(), self.root)
        with patch.object(runtime.Api, 'request', autospec=True, side_effect=responses) as request:
            check.preview(api, {'id': 'logical', 'revisionId': 'original',
                                'currentRevisionId': 'current'})
        self.assertEqual(('POST', '/api/prototype-hub/versions/logical/revisions/original/preview-session'),
                         request.call_args_list[0].args[1:])

    def test_revision_restore_check_rejects_receipt_rebound_to_current_content(self):
        original = {'id': 'logical', 'revisionId': 'original'}
        current = {'id': 'logical', 'revisionId': 'current'}
        summary = {'manifestSha256': 'a' * 64, 'fileCount': 6, 'totalBytes': 100}
        api = unittest.mock.Mock()
        api.request.side_effect = [dict(summary, id='current'), [dict(summary, id='current')],
                                   [dict(summary, id='original')]]
        api.upload.return_value = {'id': 'logical', 'revisionId': 'current',
                                   'currentRevisionId': 'current', 'revisionCount': 2}
        check = runtime.RuntimeCheck(self.args(), self.root)
        with self.assertRaisesRegex(runtime.VerificationFailure, 'original snapshot'):
            check.verify_revisions(api, {'id': 'project'}, original, current,
                                   self.root / 'unused.zip', 'key', {}, {})

    def test_replacement_fixture_changes_bytes_without_changing_file_paths(self):
        old = runtime.sample_zip(self.root / 'old.zip')
        new = runtime.sample_zip(self.root / 'new.zip', replacement=True)
        repeated = runtime.sample_zip(self.root / 'repeated.zip', replacement=True)
        self.assertNotEqual(runtime.digest_file(old), runtime.digest_file(new))
        self.assertEqual(runtime.digest_file(new), runtime.digest_file(repeated))
        with runtime.zipfile.ZipFile(old) as before, runtime.zipfile.ZipFile(new) as after:
            self.assertEqual(before.namelist(), after.namelist())
            self.assertNotEqual(before.read('index.html'), after.read('index.html'))

    def system_images(self):
        return {'userId': 'runtime-user',
                'branding': {'url': '/api/file/static/20261021/' + str(runtime.uuid.uuid4()) + '.png',
                             'content': runtime.sample_png((35, 88, 140, 255))},
                'avatar': {'url': '/api/file/static/20261021/' + str(runtime.uuid.uuid4()) + '.png',
                           'content': runtime.sample_png((132, 60, 91, 255))}}

    def system_api(self, images):
        api = unittest.mock.Mock(origin='http://127.0.0.1:1')
        api.request.side_effect = [{'id': images['userId'], 'avatar': images['avatar']['url']},
                                   {'logo': images['branding']['url']}]
        return api

    def test_system_upload_cannot_redirect_verification_to_remote_or_traversal_paths(self):
        local = self.system_images()['avatar']['url']
        invalid = ('https://example.invalid/' + self.secret,
                   '//example.invalid' + local,
                   local + '?accessKey=' + self.secret,
                   local.replace('/20261021/', '/20261021/../20261021/'))
        for value in invalid:
            with self.subTest(kind=value.split('/')[0]):
                with self.assertRaises(runtime.VerificationFailure) as failure:
                    runtime.system_file_url(value)
                self.assertNotIn(self.secret, str(failure.exception))

    def test_system_file_reads_are_anonymous_and_compare_each_uploaded_sample(self):
        images = self.system_images()
        headers = {'Content-Type': 'image/png', 'X-Content-Type-Options': 'nosniff'}
        responses = [(images[name]['content'], headers) for name in ('branding', 'avatar')]
        check = runtime.RuntimeCheck(self.args(), self.root)
        with patch.object(runtime.Api, 'request', autospec=True, side_effect=responses) as read:
            hashes = check.verify_system_files(self.system_api(images), images)
        self.assertNotEqual(hashes['branding'], hashes['avatar'])
        self.assertEqual(2, read.call_count)
        for call in read.call_args_list:
            self.assertIsNone(call.args[0].token)
            self.assertEqual('GET', call.args[1])
        with patch.object(runtime.Api, 'request', return_value=(b'corrupted', headers)):
            with self.assertRaisesRegex(runtime.VerificationFailure, 'bytes differ'):
                check.verify_system_files(self.system_api(images), images)

    def test_system_restore_rejects_changed_database_references_before_reading_files(self):
        images = self.system_images()
        check = runtime.RuntimeCheck(self.args(), self.root)
        for profile, branding in (
                ({'id': images['userId'], 'avatar': ''}, {'logo': images['branding']['url']}),
                ({'id': images['userId'], 'avatar': images['avatar']['url']}, {'logo': ''})):
            api = unittest.mock.Mock(origin='http://127.0.0.1:1')
            api.request.side_effect = [profile, branding]
            with patch.object(runtime.Api, 'request') as read:
                with self.assertRaisesRegex(runtime.VerificationFailure, 'reference changed'):
                    check.verify_system_files(api, images)
            read.assert_not_called()

    def test_missing_or_linked_storage_cannot_be_accepted_as_an_empty_snapshot(self):
        missing = self.root / 'missing-system-files'
        outside = self.root / 'unrelated-files'
        outside.mkdir()
        (outside / 'keep.png').write_bytes(b'unrelated')
        linked = self.root / 'linked-system-files'
        linked.symlink_to(outside, target_is_directory=True)
        for unsafe in (missing, linked):
            with self.assertRaises(runtime.VerificationFailure):
                runtime.storage_manifest(unsafe)
        self.assertEqual(b'unrelated', (outside / 'keep.png').read_bytes())

class RevisionQualityVerificationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='runtime-quality-test-')
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        jar = root / 'fixture.jar'
        jar.write_bytes(b'never launched')
        self.check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), root)
        self.api = runtime.Api('http://127.0.0.1:1', self.check.secrets_seen)
        self.api.token = 'private-platform-token'
        self.original, self.current = ({'id': 'logical', 'revisionId': rid} for rid in ('r1', 'r2'))

    def responder(self, fault=None):
        reports = {}

        def request(identity, method, path, payload=None, expected=200, **options):
            self.assertEqual('GET', method)
            if expected in (401, 404):
                self.assertEqual(401 if identity.token is None else 404, expected)
                return {}
            if path.endswith('/current-revision'):
                return {'id': 'r1' if fault == 'pointer' else 'r2'}
            rid = path.split('/revisions/')[1].split('/')[0]
            if not path.endswith('/quality'):
                return {'manifestSha256': ('a' if rid == 'r1' else 'b') * 64}
            report = {'versionId': 'logical', 'revisionId': rid,
                      'manifestSha256': ('a' if rid == 'r1' else 'b') * 64, 'entryFile': 'index.html',
                      'complete': True, 'findings': [],
                      'limits': {'maxTextBytes': 2 * 1024 * 1024, 'maxTotalTextBytes': 32 * 1024 * 1024,
                                 'maxReferences': 100000, 'maxFindings': 1000},
                      'summary': {'files': 5, 'scannedDocuments': 4, 'scannedBytes': 1000,
                                  'checkedReferences': 9, 'errors': 0, 'warnings': 0, 'findingsTotal': 0,
                                  'omittedFindings': 0, 'skippedDocuments': 0,
                                  'uncheckedReferences': dict(external=0, rootAbsolute=0, fragment=0,
                                                              unsupportedScheme=0)}}
            if fault == 'fixed': report['revisionId'] = 'r2'
            if fault == 'manifest': report['manifestSha256'] = 'f' * 64
            if fault == 'bounds': report['limits']['maxReferences'] += 1
            if fault == 'partial': report['complete'] = False
            if fault == 'files': report['summary']['files'] -= 1
            if fault == 'references': report['summary']['checkedReferences'] -= 1
            if fault == 'unchecked': report['summary']['uncheckedReferences']['rootAbsolute'] = 3
            if fault == 'findings': report['summary']['findingsTotal'] = 1
            if fault == 'private': report['storagePath'] = '/private/internal'
            if fault == 'unstable' and rid in reports: report['summary']['scannedBytes'] += 1
            reports[rid] = report
            return report
        return request

    def verify(self, fault=None):
        with patch.object(runtime.Api, 'request', autospec=True, side_effect=self.responder(fault)) as calls:
            self.check.verify_revision_quality(self.api, self.original, self.current, 'another-project-rid')
        return calls

    def test_quality_verifies_fixed_reports_and_anonymous_and_foreign_scope_denials(self):
        calls = self.verify()
        self.assertTrue(self.check.report['revisionQuality']['foreignRevisionRejected'])
        denials = [call for call in calls.call_args_list if call.kwargs.get('expected') in (401, 404)]
        self.assertEqual([404, 404, 401], [call.kwargs['expected'] for call in denials])
        self.assertIn('/revisions/another-project-rid/quality', denials[1].args[2])
        self.assertIsNone(denials[-1].args[0].token)
        self.assertNotIn('private-platform-token', json.dumps(self.check.report))

    def test_wrong_identity_bounds_counts_completeness_or_current_fallback_never_passes(self):
        for fault in ('fixed', 'manifest', 'bounds', 'partial', 'files', 'references', 'unchecked',
                      'findings', 'private', 'unstable', 'pointer'):
            with self.subTest(fault=fault), self.assertRaises(runtime.VerificationFailure):
                self.verify(fault)
            self.assertNotIn('revisionQuality', self.check.report)


class RevisionComparisonVerificationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='runtime-comparison-test-')
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        jar = root / 'fixture.jar'
        jar.write_bytes(b'never launched')
        self.check = runtime.RuntimeCheck(argparse.Namespace(jar=jar, postgres_image='unused'), root)
        self.api = runtime.Api('http://127.0.0.1:1', self.check.secrets_seen)
        self.api.token = 'private-platform-token'
        self.original = {'id': 'logical', 'revisionId': 'r1'}
        self.current = {'id': 'logical', 'revisionId': 'r2'}
        self.before = {'entry': 'a' * 64, 'child': 'b' * 64, 'css': 'c' * 64}
        self.after = {'entry': 'd' * 64, 'child': 'e' * 64, 'css': 'c' * 64}
        names = {'index.html': 'Runtime fixture home',
                 'nested/child.html': 'Runtime fixture nested page',
                 'nested/detail.html': 'Runtime fixture detail'}
        self.rows = {'pages': [], 'files': []}
        for path, name in names.items():
            changed = path != 'nested/detail.html'
            page = {'path': path, 'pageId': runtime.hashlib.sha256(path.encode()).hexdigest(), 'name': name}
            self.rows['pages'].append({'path': path, 'status': 'changed' if changed else 'unchanged',
                                       'left': page, 'right': dict(page, name=name + (' revision two' if changed else ''))})
        for path, key in (('assets/icon.svg', None), ('assets/style.css', 'css'), ('index.html', 'entry'),
                          ('nested/child.html', 'child'), ('nested/detail.html', None)):
            metadata = {'size': 100, 'sha256': self.before[key] if key else 'f' * 64, 'mediaType': 'text/plain'}
            self.rows['files'].append({'path': path, 'status': 'changed' if key in ('entry', 'child') else 'unchanged',
                                       'left': metadata, 'right': dict(metadata, sha256=self.after[key] if key else 'f' * 64)})

    def responder(self, fault=None):
        def request(identity, method, path, payload=None, expected=200, **options):
            self.assertEqual('GET', method)
            if expected in (401, 404):
                self.assertEqual(401 if identity.token is None else 404, expected)
                return {}
            if path.endswith('/current-revision'):
                return {'id': 'r1' if fault == 'current-pointer' else 'r2'}
            query = runtime.urllib.parse.parse_qs(runtime.urllib.parse.urlsplit(path).query)
            self.assertEqual(['r1'], query['leftRevisionId'])
            same = query['rightRevisionId'] == ['r1']
            result = {'versionId': 'logical', 'left': {'id': 'r1'},
                      'right': {'id': 'r1' if same else 'r2'}, 'summary': {}}
            for kind, source in self.rows.items():
                rows = copy.deepcopy(source)
                if same:
                    for row in rows:
                        row.update(status='unchanged', right=copy.deepcopy(row['left']))
                result['summary'][kind] = {status: sum(row['status'] == status for row in rows)
                                           for status in ('added', 'removed', 'changed', 'unchanged')}
                if query.get('includeUnchanged') != ['true']:
                    rows = [row for row in rows if row['status'] != 'unchanged']
                index, size = int(query.get(kind + 'PageIndex', ['0'])[0]), int(query.get(kind + 'PageSize', ['50'])[0])
                result[kind] = {'data': rows[index * size:(index + 1) * size],
                                'total': len(rows), 'pageIndex': index, 'pageSize': size}
            if fault == 'pair': result['right']['id'] = 'another'
            if fault == 'counts': result['summary']['files']['changed'] += 1
            if not same and 'pagesPageSize' not in query and 'filesPageSize' not in query:
                if fault == 'classification': result['pages']['data'][0]['status'] = 'unchanged'
                if fault == 'page-name': result['pages']['data'][0]['right']['name'] = 'current fallback'
                if fault == 'page-id': result['pages']['data'][0]['left']['pageId'] = 'invented'
                if fault == 'file-sha':
                    next(row for row in result['files']['data'] if row['path'] == 'index.html')['right']['sha256'] = '0' * 64
                if query.get('includeUnchanged') != ['true']:
                    if fault == 'filtered-total': result['files']['total'] = 5
                    if fault == 'unchanged-leak': result['pages']['data'].append(copy.deepcopy(self.rows['pages'][-1]))
            if 'pagesPageSize' in query:
                if fault == 'paging': result['pages']['data'] = copy.deepcopy(self.rows['pages'])
                if fault == 'coupled-pagers': result['files']['data'] = []
            if same and fault == 'historical-fallback': result['right']['id'] = 'r2'
            return result
        return request

    def verify(self, fault=None):
        with patch.object(runtime.Api, 'request', autospec=True, side_effect=self.responder(fault)) as calls:
            self.check.verify_revision_comparison(self.api, self.original, self.current,
                                                   self.before, self.after, 'another-project-rid')
        return calls

    def test_comparison_verifies_independent_pagers_and_anonymous_and_foreign_scope_denials(self):
        calls = self.verify()
        self.assertTrue(self.check.report['revisionComparison']['foreignRevisionRejected'])
        denials = [call for call in calls.call_args_list if call.kwargs.get('expected') in (401, 404)]
        self.assertEqual([404, 404, 401], [call.kwargs['expected'] for call in denials])
        self.assertIn('rightRevisionId=another-project-rid', denials[1].args[2])
        self.assertIsNone(denials[-1].args[0].token)
        self.assertNotIn('private-platform-token', json.dumps(self.check.report))

    def test_wrong_metadata_filter_counts_paging_or_current_fallback_never_passes(self):
        for fault in ('pair', 'counts', 'classification', 'page-name', 'page-id', 'file-sha',
                      'filtered-total', 'unchanged-leak', 'paging', 'coupled-pagers',
                      'historical-fallback', 'current-pointer'):
            with self.subTest(fault=fault), self.assertRaises(runtime.VerificationFailure):
                self.verify(fault)
            self.assertNotIn('revisionComparison', self.check.report)


class RetainedDeletionVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='runtime-deletion-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        jar = self.root / 'fixture.jar'
        jar.write_bytes(b'never launched')
        args = argparse.Namespace(jar=jar, java=Path(sys.executable),
                                  postgres_image='postgres:16-alpine',
                                  report=self.root / 'report.json', frontend_dir=None)
        self.check = runtime.RuntimeCheck(args, self.root)
        self.api = runtime.Api('http://127.0.0.1:1', self.check.secrets_seen)
        self.api.token = 'private-platform-token'
        self.storage = self.root / 'storage'
        self.storage.mkdir()
        (self.storage / 'retained.html').write_bytes(b'kept after deletion')
        self.state = {name: {'count': count, 'contentMd5': 'a' * 32}
                      for name, count in (('projects', 2), ('versions', 2), ('revisions', 3),
                                          ('shares', 2), ('receipts', 4), ('members', 1))}
        self.state.update(sessions=0, invalidReferences=0)
        public = {'previewUrl': '/api/public/private-grant/index.html',
                  'pagesUrl': '/api/public/private-grant/pages',
                  'runtimeBaseUrl': '/api/public/private-grant'}
        internal = {'previewUrl': '/api/runtime/private-session/index.html',
                    'runtimeBaseUrl': '/api/runtime/private-session'}
        (self.root / 'unused.zip').write_bytes(b'original sample')
        self.fixture = {'project': {'id': 'retained-project'}, 'projectName': 'Retained',
                        'projectKey': 'original-project-key', 'emptyProject': {'id': 'empty-project'},
                        'emptyKey': 'empty-project-key', 'original': {'id': 'retained-version', 'revisionId': 'r1'},
                        'current': {'revisionId': 'r2'}, 'versionKey': 'original-version-key',
                        'replacement': {'id': 'replacement-version'}, 'replacementKey': 'replacement-key',
                        'member': {'username': 'fixture-viewer'}, 'shares': ({'token': 'private-share'},),
                        'publicGrants': (public,), 'internalGrants': (internal,),
                        'files': runtime.storage_manifest(self.storage), 'state': self.state}

    def test_deleted_state_requires_all_rows_references_and_cleared_sessions(self):
        for field in ('revisions', 'shares', 'receipts'):
            broken = dict(self.state, **{field: {'count': 0, 'contentMd5': 'a' * 32}})
            with self.subTest(field=field), patch.object(self.check, 'sql', return_value=json.dumps(broken)):
                with self.assertRaisesRegex(runtime.VerificationFailure, 'lost retained'):
                    self.check.deleted_fixture_state('disposable', self.fixture)
        for field in ('sessions', 'invalidReferences'):
            with self.subTest(field=field), patch.object(self.check, 'sql',
                    return_value=json.dumps(dict(self.state, **{field: 1}))):
                with self.assertRaisesRegex(runtime.VerificationFailure, 'grant or broke'):
                    self.check.deleted_fixture_state('disposable', self.fixture)
        with patch.object(self.check, 'sql', return_value=json.dumps(self.state)) as sql:
            self.assertEqual(self.state, self.check.deleted_fixture_state('disposable', self.fixture))
        self.assertIn(":'project'", sql.call_args.args[1])
        self.assertNotIn('retained-project', sql.call_args.args[1])
        self.assertEqual('retained-project', sql.call_args.kwargs['variables']['project'])

    def test_all_deleted_grants_are_read_anonymously_and_file_loss_fails(self):
        def request(identity, method, path, payload=None, expected=200, headers=None, **options):
            if path == '/api/authorize/login':
                return {'token': 'temporary-viewer-token'}
            if expected == 409:
                return {'code': 'PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT'}
            return []
        with patch.object(runtime.Api, 'request', autospec=True, side_effect=request) as calls, \
                patch.object(self.check, 'deleted_fixture_state', return_value=self.state):
            self.check.verify_deleted_fixture(self.api, self.fixture, self.root / 'unused.zip',
                                               self.storage, 'disposable')
        anonymous_reads = [call for call in calls.call_args_list
                           if call.args[2].startswith(('/api/public/', '/api/runtime/'))]
        self.assertEqual(5, len(anonymous_reads))
        for call in anonymous_reads:
            self.assertIsNone(call.args[0].token)
            self.assertEqual('GET', call.args[1])
            self.assertEqual(403 if '/runtime/' in call.args[2] else 404,
                             call.kwargs['expected'])
        self.assertEqual(4, sum(call.kwargs.get('expected', call.args[4] if len(call.args) > 4 else 200) == 409
                                for call in calls.call_args_list))
        self.assertNotIn('private-', json.dumps(self.check.report))
        (self.storage / 'retained.html').unlink()
        with patch.object(runtime.Api, 'request', autospec=True, side_effect=request), \
                patch.object(self.check, 'deleted_fixture_state', return_value=self.state):
            with self.assertRaisesRegex(runtime.VerificationFailure, 'file identities or bytes'):
                self.check.verify_deleted_fixture(self.api, self.fixture, self.root / 'unused.zip',
                                                   self.storage, 'disposable')

    def test_creation_captures_each_file_before_deletion_and_rejects_partial_loss(self):
        scenarios = [(lost, False, False) for lost in ('r1', 'r2', 'replacement', None)]
        scenarios += [(None, True, False), (None, False, True)]
        for lost, reused, early_release in scenarios:
            with self.subTest(lost=lost, reused_at_capacity=reused, early_release=early_release):
                project_root = self.storage / 'projects'
                if project_root.exists():
                    shutil.rmtree(project_root)
                files = {}
                state = {'current': False, 'activeVersions': 0}

                def write_revision(revision):
                    path = project_root / 'retained-project/versions' / revision / 'site/index.html'
                    path.parent.mkdir(parents=True)
                    path.write_text(revision)
                    files[revision] = path

                def upload(project, zip_path, key):
                    revision = 'replacement' if files else 'r1'
                    write_revision(revision)
                    state['activeVersions'] += 1
                    return {'id': 'replacement-version' if revision == 'replacement' else 'retained-version',
                            'revisionId': revision}

                def replace(version, zip_path):
                    write_revision('r2')
                    state['current'] = True
                    return dict(version, revisionId='r2')

                def request(identity, method, path, payload=None, expected=200, headers=None, **options):
                    if path == '/api/authorize/login':
                        return {'token': 'temporary-viewer-token'}
                    if method == 'POST' and path.endswith('/shares'):
                        return {'token': 'latest' if payload.get('revisionMode') else 'fixed'}
                    if '/public/shares/' in path:
                        return {'revisionId': 'r2' if state['current'] else 'r1'}
                    if method == 'GET' and '/versions/_usage?' in path:
                        return {'versions': {'used': state['activeVersions']}}
                    if method == 'DELETE' and '/versions/' in path:
                        state['activeVersions'] -= 1
                        is_replacement = path.endswith('/replacement-version')
                        if lost and ((lost == 'replacement') == is_replacement):
                            files[lost].unlink()
                    return {}

                roots = {'project': {'id': 'retained-project'}, 'projectKey': 'original-project-key',
                         'emptyProject': {'id': 'empty-project'}, 'emptyKey': 'original-empty-key'} if reused else None
                usage = (5, 5, 5, 5) if reused else ((1, 1, 2, 1) if early_release else (1, 2, 3, 3))
                with patch.object(self.api, 'project', side_effect=(AssertionError('Cannot allocate a sixth root')
                        if reused else [{'id': 'retained-project'}, {'id': 'empty-project'}])) as creates, \
                        patch.object(self.api, 'usage', side_effect=[{'projects': {'used': used}} for used in usage]), \
                        patch.object(self.api, 'upload', side_effect=upload), \
                        patch.object(self.api, 'replace', side_effect=replace), \
                        patch.object(runtime.Api, 'request', autospec=True, side_effect=request), \
                        patch.object(self.check, 'preview'), \
                        patch.object(self.check, 'deleted_fixture_state', return_value=self.state):
                    if lost or early_release:
                        with self.assertRaisesRegex(runtime.VerificationFailure,
                                'retained capacity' if early_release else 'changed retained fixture files'):
                            self.check.create_deleted_fixture(self.api, {'id': 'viewer', 'username': 'viewer'},
                                                              self.root / 'unused.zip', self.root / 'unused.zip',
                                                              self.storage, roots)
                        self.assertTrue(any(path.exists() for path in files.values()))
                    else:
                        fixture = self.check.create_deleted_fixture(
                            self.api, {'id': 'viewer', 'username': 'viewer'}, self.root / 'unused.zip',
                            self.root / 'unused.zip', self.storage, roots)
                        if reused:
                            creates.assert_not_called()
                        self.assertEqual({path.relative_to(self.storage).as_posix() for path in files.values()},
                                         set(fixture['files']))

    def test_disposable_sql_passes_ids_as_quoted_psql_variables(self):
        hostile_id = "value' with quotes"
        with patch.object(self.check, 'command', return_value=b'{}') as command:
            self.check.sql('disposable', "select :'project';", variables={'project': hostile_id})
        self.assertIn('project=' + hostile_id, command.call_args.args[0])
        self.assertEqual(b"select :'project';", command.call_args.kwargs['input_data'])

    def recovered_fixture(self):
        public = self.fixture['publicGrants'][0]
        return {'project': {'id': 'recovered-project'}, 'projectName': 'Recovered',
                'projectKey': 'recovered-project-key', 'original': {'id': 'recovered-version', 'revisionId': 'r1'},
                'current': {'id': 'recovered-version', 'revisionId': 'r2'},
                'versionKey': 'recovered-version-key', 'originalHashes': {'entry': 'original'},
                'currentHashes': {'entry': 'current'}, 'oldShare': {'id': 'old', 'token': 'private-old'},
                'oldPublic': public, 'oldInternal': self.fixture['internalGrants'][0],
                'newShare': {'id': 'new', 'token': 'private-new'}, 'files': self.fixture['files'],
                'state': {name: {'count': count, 'contentMd5': 'b' * 32}
                          for name, count in (('projects', 1), ('versions', 1), ('revisions', 2),
                                              ('oldShares', 1), ('newShares', 1), ('receipts', 2))}
                | {'invalidReferences': 0}}

    def test_recovered_state_rejects_revived_old_share_and_missing_receipt(self):
        fixture = self.recovered_fixture()
        for table in ('projects', 'versions', 'revisions', 'oldShares', 'newShares', 'receipts'):
            broken = dict(fixture['state'], **{table: {'count': 0, 'contentMd5': 'b' * 32}})
            with self.subTest(table=table), patch.object(self.check, 'sql', return_value=json.dumps(broken)):
                with self.assertRaisesRegex(runtime.VerificationFailure, 'changed retained'):
                    self.check.restored_fixture_state('disposable', fixture)
        with patch.object(self.check, 'sql', return_value=json.dumps(
                dict(fixture['state'], invalidReferences=1))):
            with self.assertRaisesRegex(runtime.VerificationFailure, 'receipt revision binding'):
                self.check.restored_fixture_state('disposable', fixture)

    def test_recovered_verification_reads_old_capabilities_anonymously_and_rejects_file_loss(self):
        fixture = self.recovered_fixture()
        def request(identity, method, path, payload=None, expected=200, **options):
            if path.endswith('/shares'):
                return [{'id': 'old', 'revoked': True, 'enabled': False}]
            return {}
        self.api.project = unittest.mock.Mock(return_value=fixture['project'])
        self.api.usage = unittest.mock.Mock(return_value={'projects': {'used': 5}})
        with patch.object(runtime.Api, 'request', autospec=True, side_effect=request) as calls, \
                patch.object(self.check, 'verify_revisions'), patch.object(self.check, 'verify_share'), \
                patch.object(self.check, 'restored_fixture_state', return_value=fixture['state']):
            self.check.verify_restored_fixture(self.api, fixture, self.root / 'unused.zip',
                                               self.storage, 'disposable')
        denied = [call for call in calls.call_args_list if call.kwargs.get('expected') in (403, 404)]
        self.assertEqual(7, len(denied))
        self.assertTrue(all(call.args[0].token is None for call in denied))
        self.assertNotIn('private-', json.dumps(self.check.report))
        (self.storage / 'retained.html').unlink()
        with patch.object(runtime.Api, 'request', autospec=True, side_effect=request), \
                patch.object(self.check, 'verify_revisions'), patch.object(self.check, 'verify_share'), \
                patch.object(self.check, 'restored_fixture_state', return_value=fixture['state']):
            with self.assertRaisesRegex(runtime.VerificationFailure, 'file identities or bytes'):
                self.check.verify_restored_fixture(self.api, fixture, self.root / 'unused.zip',
                                                   self.storage, 'disposable')

    def test_recovered_verification_rejects_unchanged_file_graph_with_revived_share(self):
        fixture = self.recovered_fixture()
        self.api.project = unittest.mock.Mock(return_value=fixture['project'])
        self.api.usage = unittest.mock.Mock(return_value={'projects': {'used': 2}})
        with patch.object(runtime.Api, 'request', return_value=[{'id': 'old', 'revoked': False, 'enabled': True}]), \
                patch.object(self.check, 'verify_revisions'), patch.object(self.check, 'verify_share') as new_share:
            with self.assertRaisesRegex(runtime.VerificationFailure, 're-enabled a retained share'):
                self.check.verify_restored_fixture(self.api, fixture, self.root / 'unused.zip',
                                                   self.storage, 'disposable')
        new_share.assert_not_called()

    def test_recovery_fixture_rejects_cascading_or_replaced_identity_and_pointer(self):
        failures = {'cascade': 'also restored a deleted child', 'new-project': 'Project recovery replaced',
                    'new-version': 'Version recovery replaced', 'pointer': 'immutable current pointer',
                    'restore-extra-slot': 'consumed an extra project slot',
                    'soft-delete-release': 'soft deletion changed retained project capacity',
                    'restore-increment': 'allocated a second slot'}
        for failure in (*failures, None):
            with self.subTest(failure=failure):
                storage = self.storage / str(failure)
                storage.mkdir()
                def write(revision):
                    target = storage / revision / 'site/index.html'
                    target.parent.mkdir(parents=True)
                    target.write_text(revision)
                def project(name, key, expected=200):
                    return {'code': 'PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT'} if expected == 409 else {'id': 'project'}
                def upload(project, archive, key, expected=200):
                    if expected == 409:
                        return {'code': 'PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT'}
                    write('r1')
                    return {'id': 'version', 'revisionId': 'r1'}
                def replace(version, archive):
                    write('r2')
                    return {'id': 'version', 'revisionId': 'r2'}
                def request(identity, method, path, payload=None, expected=200, **options):
                    if expected in (404, 409):
                        return {}
                    if '/_deleted/_query' in path:
                        return {'data': [] if failure == 'cascade' else [{'id': 'version', 'deletedTime': 1}]}
                    if path.endswith('/projects/project/_restore'):
                        return {'id': 'different' if failure == 'new-project' else 'project'}
                    if path.endswith('/versions/version/_restore'):
                        return {'id': 'different' if failure == 'new-version' else 'version',
                                'currentRevisionId': 'r1' if failure == 'pointer' else 'r2'}
                    if path.endswith('/shares'):
                        return {'id': 'share', 'token': 'private-share'}
                    return {'previewUrl': '/api/private/index.html', 'runtimeBaseUrl': '/api/private',
                            'pagesUrl': '/api/private/pages'}
                with patch.object(self.api, 'project', side_effect=project), \
                        patch.object(self.api, 'upload', side_effect=upload), \
                        patch.object(self.api, 'replace', side_effect=replace), \
                        patch.object(self.api, 'usage', side_effect=[{'projects': {'used': used}} for used in {
                            'restore-extra-slot': (5, 6), 'soft-delete-release': (5, 5, 4),
                            'restore-increment': (5, 5, 5, 6)}.get(failure, (5, 5, 5, 5))]), \
                        patch.object(runtime.Api, 'request', autospec=True, side_effect=request), \
                        patch.object(self.check, 'preview', return_value={'entry': 'hash'}), \
                        patch.object(self.check, 'restored_fixture_state', return_value={}):
                    if failure:
                        with self.assertRaisesRegex(runtime.VerificationFailure, failures[failure]):
                            self.check.create_restored_fixture(self.api, self.root / 'unused.zip',
                                                               self.root / 'unused.zip', storage,
                                                               {'project': {'id': 'project'}, 'projectKey': 'original', 'projectName': 'Original project'})
                    else:
                        fixture = self.check.create_restored_fixture(self.api, self.root / 'unused.zip',
                                                                    self.root / 'unused.zip', storage,
                                                               {'project': {'id': 'project'}, 'projectKey': 'original', 'projectName': 'Original project'})
                        self.assertEqual(2, len(fixture['files']))


class ExpiredCleanupVerificationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='runtime-expiry-test-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'unused.jar').write_bytes(b'never launched')
        args = argparse.Namespace(jar=self.root / 'unused.jar', java=Path(sys.executable),
                                  postgres_image='postgres:16-alpine',
                                  report=self.root / 'report.json', frontend_dir=None)
        self.check = runtime.RuntimeCheck(args, self.root)
        self.storage = self.root / 'storage'
        self.storage.mkdir()
        self.retained = self.storage / 'retained.html'
        self.retained.write_bytes(b'Keep exact bytes')
        self.fixture = {'project': {'id': 'expired-project'}, 'emptyProject': {'id': 'empty-project'},
                        'original': {'id': 'expired-version'}, 'replacement': {'id': 'expired-replacement'},
                        'projectName': 'Original name', 'projectKey': 'original-key'}
        self.counts = dict.fromkeys(('projects', 'versions', 'revisions', 'shares', 'receipts',
                                    'members', 'sessions', 'cleanup'), 0)

    def test_aging_and_queue_seeding_refuse_running_writers(self):
        self.check.processes.append(object())
        with patch.object(self.check, 'sql') as sql:
            with self.assertRaisesRegex(runtime.VerificationFailure, 'writer is running'):
                self.check.expire_fixture('disposable', self.fixture)
            with self.assertRaisesRegex(runtime.VerificationFailure, 'writer is running'):
                self.check.seed_pending_cleanup('disposable', self.storage, self.fixture)
            sql.assert_not_called()
        self.assertEqual({'retained.html'}, set(runtime.storage_manifest(self.storage)))

    def test_aging_binds_only_owned_ids_and_explicit_elapsed_deadline(self):
        with patch.object(runtime.time, 'time', return_value=2000000000), \
                patch.object(self.check, 'sql') as sql:
            self.check.expire_fixture('disposable', self.fixture)
        self.assertIn('deleted_time IS NOT NULL', sql.call_args.args[1])
        self.assertNotIn('expired-project', sql.call_args.args[1])
        self.assertEqual(str(2000000000000 - 30 * 24 * 60 * 60 * 1000 - 60000),
                         sql.call_args.kwargs['variables']['deleted'])

    def test_expired_graph_requires_every_relation_even_when_files_are_gone(self):
        for counts in ({}, dict(self.counts, sessions=-1), dict(self.counts, cleanup=False)):
            with self.subTest(counts=counts), patch.object(self.check, 'sql', return_value=json.dumps(counts)):
                with self.assertRaisesRegex(runtime.VerificationFailure, 'incomplete or invalid'):
                    self.check.expired_fixture_counts('disposable', self.fixture)
        with patch.object(self.check, 'sql', return_value=json.dumps(self.counts)):
            self.assertEqual(self.counts, self.check.expired_fixture_counts('disposable', self.fixture))

    def pending(self):
        with patch.object(self.check, 'sql') as sql:
            pending = self.check.seed_pending_cleanup('disposable', self.storage, self.fixture)
        self.assertIn("'expired-snapshot'", sql.call_args.args[1])
        self.assertEqual(self.fixture['project']['id'], sql.call_args.kwargs['variables']['project'])
        self.assertEqual(2, len(pending['files']))
        self.assertTrue(sql.call_args.kwargs['variables']['path'].startswith(
            'projects/expired-project/versions/'))
        return pending

    def remove_pending(self, pending):
        for name in pending['files']:
            (self.storage / name).unlink()

    def test_cleanup_accepts_only_exact_owned_file_difference(self):
        pending = self.pending()
        before = runtime.storage_manifest(self.storage)
        self.remove_pending(pending)
        with patch.object(self.check, 'sql', return_value='0'):
            expected = self.check.verify_cleanup('disposable', self.storage, before, pending)
        self.assertEqual({'retained.html': runtime.digest_file(self.retained)}, expected)
        self.retained.write_bytes(b'Unexpected modification')
        with patch.object(self.check, 'sql', return_value='0'):
            with self.assertRaisesRegex(runtime.VerificationFailure, 'outside the exact owned fixture'):
                self.check.verify_cleanup('disposable', self.storage, before, pending)
        self.retained.unlink()
        with patch.object(self.check, 'sql', return_value='0'):
            with self.assertRaisesRegex(runtime.VerificationFailure, 'outside the exact owned fixture'):
                self.check.verify_cleanup('disposable', self.storage, before, pending)

    def test_missing_source_file_cannot_be_reclassified_as_expected_cleanup(self):
        pending = self.pending()
        before = runtime.storage_manifest(self.storage)
        before.pop(next(iter(pending['files'])))
        with self.assertRaisesRegex(runtime.VerificationFailure, 'not present unchanged'):
            self.check.verify_cleanup('disposable', self.storage, before, pending)

    def test_pending_queue_or_orphan_graph_prevents_success_after_file_deletion(self):
        pending = self.pending()
        before = runtime.storage_manifest(self.storage)
        self.remove_pending(pending)
        for queue, field in (('1', None), ('0', 'receipts'), ('0', 'members'), ('0', 'sessions')):
            with self.subTest(queue=queue, field=field), \
                    patch.object(self.check, 'sql', return_value=queue), \
                    patch.object(self.check, 'expired_fixture_counts',
                                 return_value=dict(self.counts, **({field: 1} if field else {}))), \
                    patch.object(runtime.time, 'monotonic', side_effect=[0, 61]):
                with self.assertRaisesRegex(runtime.VerificationFailure, 'did not drain'):
                    self.check.verify_cleanup('disposable', self.storage, before, pending,
                                               expired=dict(self.fixture, files={}))

    def test_purged_grants_remain_anonymous_and_old_key_cannot_revive_identity(self):
        api = runtime.Api('http://127.0.0.1:1', self.check.secrets_seen)
        api.token = 'private-platform-token'
        fixture = dict(self.fixture, shares=({'token': 'private-share'},),
                       publicGrants=({'previewUrl': '/api/public/private-grant/index.html'},),
                       internalGrants=({'previewUrl': '/api/runtime/private-grant/index.html'},))
        def request(identity, method, path, *args, **kwargs):
            return {'data': []} if '/_deleted/_query' in path else None
        for recreated_id in ('new-project', 'expired-project'):
            with self.subTest(recreated_id=recreated_id), \
                    patch.object(api, 'project', return_value={'id': recreated_id}) as create, \
                    patch.object(runtime.Api, 'request', autospec=True, side_effect=request) as calls:
                if recreated_id == 'expired-project':
                    with self.assertRaisesRegex(runtime.VerificationFailure, 'revived the old project ID'):
                        self.check.verify_expired_access(api, fixture)
                else:
                    reusable = self.check.verify_expired_access(api, fixture)
                    self.assertEqual({'project': {'id': 'new-project'}, 'projectKey': 'original-key',
                                      'projectName': 'Original name'}, reusable)
                    create.assert_called_once_with('Original name', 'original-key')
                    for call in calls.call_args_list:
                        if call.args[2].startswith(('/api/public/', '/api/runtime/',
                                                   '/api/prototype-hub/public/')):
                            self.assertIsNone(call.args[0].token)
                            self.assertEqual(403 if '/api/runtime/' in call.args[2] else 404,
                                             call.kwargs['expected'])


@unittest.skipUnless(os.environ.get('PROTO_RUNTIME_PLAYWRIGHT_DIR'),
                     'Set PROTO_RUNTIME_PLAYWRIGHT_DIR to an installed frontend for real CLI probes')
class PlaywrightLifecycleTest(unittest.TestCase):
    """Exercise the real public CLI and detached webServer, without opening a browser."""

    def probe(self, interrupt):
        frontend = Path(os.environ['PROTO_RUNTIME_PLAYWRIGHT_DIR']).resolve()
        package = frontend / 'node_modules/@playwright/test'
        self.assertTrue((package / 'cli.js').is_file())
        node = shutil.which('node')
        self.assertIsNotNone(node)
        with tempfile.TemporaryDirectory(prefix='runtime-playwright-probe-') as temporary:
            root = Path(temporary)
            modules = root / 'node_modules/@playwright'
            modules.mkdir(parents=True)
            (modules / 'test').symlink_to(package, target_is_directory=True)
            port = runtime.free_port()
            pid_file, started = root / 'server.pid', root / 'test.started'
            server = root / 'server.cjs'
            server.write_text("const fs=require('node:fs');require('node:http').createServer((q,s)=>"
                              "s.end('owned lifecycle probe')).listen(" + str(port) + ", '127.0.0.1',"
                              "()=>fs.writeFileSync(" + json.dumps(str(pid_file)) + ",String(process.pid)));\n")
            (root / 'paused.spec.js').write_text(
                'const {test}=require(' + json.dumps(str(package)) + ');\n'
                "test('owned paused lifecycle probe',async()=>{require('node:fs').writeFileSync(" +
                json.dumps(str(started)) + ",'ready');await new Promise(()=>{});});\n")
            config = {'testDir': str(root), 'testMatch': 'paused.spec.js', 'timeout': 120000,
                      'workers': 1, 'reporter': 'list', 'outputDir': str(root / 'results'),
                      'webServer': {'command': shlex.quote(node) + ' ' + shlex.quote(str(server)),
                                    'url': 'http://127.0.0.1:' + str(port),
                                    'reuseExistingServer': False, 'timeout': 10000}}
            (root / 'playwright.live.config.ts').write_text('module.exports=' + json.dumps(config) + ';\n')
            jar, report = root / 'unused.jar', root / 'report.json'
            jar.touch()
            driver = root / 'driver.py'
            driver.write_text(
                'import importlib.util,sys\nfrom pathlib import Path\n'
                'spec=importlib.util.spec_from_file_location("runtime",' +
                repr(str(Path(runtime.__file__).resolve())) + ')\n'
                'runtime=importlib.util.module_from_spec(spec);spec.loader.exec_module(runtime)\n'
                'runtime.FRONTEND_GLOBAL_TIMEOUT_MS=120000\nruntime.FRONTEND_WAIT_SECONDS=15\n'
                'def execute(check):\n'
                '    check.stage="real-playwright-lifecycle-probe"\n'
                '    check.frontend(runtime.Api("http://127.0.0.1:1",set()),Path("unused.zip"))\n'
                'runtime.RuntimeCheck.execute=execute\n'
                'sys.exit(runtime.main(["--jar",' + repr(str(jar)) + ',"--frontend-dir",' +
                repr(str(root)) + ',"--report",' + repr(str(report)) + ']))\n')
            process = subprocess.Popen([sys.executable, str(driver)], stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            server_pid = None
            try:
                deadline = time.monotonic() + 12
                while not started.exists() and time.monotonic() < deadline:
                    self.assertIsNone(process.poll(), 'Probe CLI exited before the paused test')
                    time.sleep(0.05)
                self.assertTrue(started.is_file(), 'Real CLI did not start its detached webServer/test')
                server_pid = int(pid_file.read_text())
                if interrupt:
                    # Interrupt only this probe's verifier, exercising its actual finally path.
                    process.send_signal(signal.SIGINT)
                output, _ = process.communicate(timeout=45)
                self.assertEqual(130 if interrupt else 1, process.returncode, output.decode())
                result = json.loads(report.read_text())
                self.assertEqual('failed', result['status'])
                self.assertNotIn('cleanupError', result)
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    with socket.socket() as connection:
                        closed = connection.connect_ex(('127.0.0.1', port)) != 0
                    try:
                        os.kill(server_pid, 0)
                        gone = False
                    except ProcessLookupError:
                        gone = True
                    if closed and gone:
                        break
                    time.sleep(0.05)
                self.assertTrue(closed, 'Detached owned webServer is still listening')
                self.assertTrue(gone, 'Detached owned webServer PID survived cancellation')
            finally:
                if process.poll() is None:
                    process.send_signal(signal.SIGINT)
                    try:
                        process.wait(timeout=35)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait(timeout=5)
                if server_pid is not None:
                    try:
                        os.kill(server_pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    def test_external_timeout_closes_real_detached_webserver(self):
        self.probe(interrupt=False)

    def test_verifier_interrupt_closes_real_detached_webserver(self):
        self.probe(interrupt=True)


if __name__ == '__main__':
    unittest.main()
