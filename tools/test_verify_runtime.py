"""Safety boundary checks for the disposable packaged-runtime verifier."""

import argparse
import contextlib
import copy
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

spec = importlib.util.spec_from_file_location('verify_runtime', Path(__file__).with_name('verify-runtime.py'))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


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
        for lost in ('r1', 'r2', 'replacement', None):
            with self.subTest(lost=lost):
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

                with patch.object(self.api, 'project', side_effect=[{'id': 'retained-project'},
                                                                  {'id': 'empty-project'}]), \
                        patch.object(self.api, 'usage', side_effect=[{'projects': {'used': used}}
                                                                    for used in (1, 1, 2, 1)]), \
                        patch.object(self.api, 'upload', side_effect=upload), \
                        patch.object(self.api, 'replace', side_effect=replace), \
                        patch.object(runtime.Api, 'request', autospec=True, side_effect=request), \
                        patch.object(self.check, 'preview'), \
                        patch.object(self.check, 'deleted_fixture_state', return_value=self.state):
                    if lost:
                        with self.assertRaisesRegex(runtime.VerificationFailure,
                                                    'changed retained fixture files'):
                            self.check.create_deleted_fixture(self.api, {'id': 'viewer', 'username': 'viewer'},
                                                              self.root / 'unused.zip', self.root / 'unused.zip',
                                                              self.storage)
                        self.assertTrue(any(path.exists() for path in files.values()))
                    else:
                        fixture = self.check.create_deleted_fixture(
                            self.api, {'id': 'viewer', 'username': 'viewer'}, self.root / 'unused.zip',
                            self.root / 'unused.zip', self.storage)
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
                    'new-version': 'Version recovery replaced', 'pointer': 'immutable current pointer'}
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
                        patch.object(self.api, 'usage', side_effect=[{'projects': {'used': used}} for used in (0, 0, 1)]), \
                        patch.object(runtime.Api, 'request', autospec=True, side_effect=request), \
                        patch.object(self.check, 'preview', return_value={'entry': 'hash'}), \
                        patch.object(self.check, 'restored_fixture_state', return_value={}):
                    if failure:
                        with self.assertRaisesRegex(runtime.VerificationFailure, failures[failure]):
                            self.check.create_restored_fixture(self.api, self.root / 'unused.zip',
                                                               self.root / 'unused.zip', storage)
                    else:
                        fixture = self.check.create_restored_fixture(self.api, self.root / 'unused.zip',
                                                                    self.root / 'unused.zip', storage)
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
                    self.check.verify_expired_access(api, fixture)
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
