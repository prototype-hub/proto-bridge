#!/usr/bin/env python3
"""Verify a packaged JAR using only disposable PostgreSQL and original HTML data.

This is a process/restore check, not an installer or a production backup tool.
No existing database or storage path can be supplied. Credentials stay in child
environments and private temporary files; reports contain only bounded evidence.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from http.cookies import SimpleCookie
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
from threading import Barrier
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
import zlib

from runtime_mailbox import LocalMailbox


FIXTURE = Path(__file__).resolve().parent / 'fixtures' / 'runtime-site'
REVIEW_TABLES = ('p_review_guest', 'p_review_challenge', 'p_review_session', 'p_review_rate',
                 'p_review_unverified_guest', 'p_review_invitation', 'p_review_thread',
                 'p_review_message', 'p_review_event')
BUSINESS_TABLES = ('p_installation', 'p_project', 'p_project_member', 'p_preview_session',
                   'p_version', 'p_revision', 'p_work_item', 'p_share', *REVIEW_TABLES,
                   'p_review_association', 'p_share_visit', 'p_copy_snapshot', 'p_copy_entry',
                   'p_creation_receipt', 'p_file_cleanup')
TABLES = (*BUSINESS_TABLES, 's_user', 's_dimension', 's_dimension_user',
          's_autz_setting_info', 's_permission', 's_menu', 's_role_menu_grant',
          's_user_detail', 's_system_config')
FRONTEND_GLOBAL_TIMEOUT_MS = 300000
FRONTEND_WAIT_SECONDS = 330
RETENTION_MILLISECONDS = 30 * 24 * 60 * 60 * 1000


class VerificationFailure(RuntimeError):
    """A bounded failure message which never includes an HTTP response body."""


def require(condition, message):
    if not condition:
        raise VerificationFailure(message)


def review_manifest(manifest):
    """A whole-database dump is useful evidence only if every review table has rows."""
    result = {}
    for table in REVIEW_TABLES:
        row = manifest.get(table)
        require(isinstance(row, dict) and type(row.get('count')) is int and row['count'] > 0 and
                re.fullmatch('[0-9a-f]{32}', row.get('contentMd5', '')) is not None,
                'Review backup table is missing, empty or has no checksum: ' + table)
        result[table] = row
    return result


def registered_review_expiry(rows, now):
    """Register exact disposable row identities before starting a cleanup process."""
    return {table: {identity for identity, row in values.items()
                    if type(row['deadline']) is int and row['deadline'] <= now}
            for table, values in rows.items()}


def compare_review_rows(before, after, now, allowed_removed=None):
    """Only registered, already-expired rows may disappear; nothing may be rewritten."""
    removed = {}
    allowed_removed = allowed_removed or {}
    require(set(before) == set(after) == set(REVIEW_TABLES), 'Review row snapshot omitted a table')
    for table in REVIEW_TABLES:
        require(not (after[table].keys() - before[table].keys()),
                'A read or startup inserted review rows: ' + table)
        for identity, row in before[table].items():
            current = after[table].get(identity)
            if current is None:
                require(identity in allowed_removed.get(table, set()) and
                        type(row['deadline']) is int and row['deadline'] <= now,
                        'An unregistered or unexpired review row disappeared: ' + table)
            else:
                require(current == row, 'A read or startup changed a review row: ' + table)
        removed[table] = len(before[table]) - len(after[table])
    return removed


def current_manifest(manifest):
    require({table for table in manifest if table.startswith('p_')} == set(BUSINESS_TABLES),
            'Current business table coverage differs from the required 23 tables')
    for table in BUSINESS_TABLES:
        row = manifest[table]
        require(type(row.get('count')) is int and row['count'] > 0 and
                re.fullmatch('[0-9a-f]{32}', row.get('contentMd5', '')) is not None,
                'Current backup fixture is empty or has no checksum: ' + table)
    return manifest


def compare_database_rows(before, after):
    require(set(before) == set(after) == set(TABLES), 'Restored row snapshot omitted a required table')
    for table in TABLES:
        require(before[table] == after[table], 'Restored row identities or full content differ: ' + table)


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def digest_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def storage_manifest(root):
    """Compare relative file identity and bytes, independently of timestamps."""
    require(root.is_dir() and not root.is_symlink(), 'Disposable storage root is missing or a symlink')
    files = {}
    for path in sorted(root.rglob('*')):
        require(not path.is_symlink(), 'Unexpected symlink in disposable storage')
        if path.is_file():
            files[path.relative_to(root).as_posix()] = digest_file(path)
    return files


def sample_zip(target, replacement=False):
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(FIXTURE.rglob('*')):
            if path.is_file():
                # Stable ZIP metadata makes request replay and sample hashes reproducible.
                info = zipfile.ZipInfo(path.relative_to(FIXTURE).as_posix(), (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                content = path.read_bytes()
                if replacement and path.suffix == '.html':
                    content = content.replace(b'Runtime fixture home', b'Runtime fixture home revision two')
                    content = content.replace(b'Runtime fixture nested page', b'Runtime fixture nested page revision two')
                archive.writestr(info, content)
    return target


def sample_png(color):
    """Build an original valid one-pixel RGBA PNG without external image assets."""
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 6, 0, 0, 0)) +
            chunk(b'IDAT', zlib.compress(b'\x00' + bytes(color))) + chunk(b'IEND', b''))


def file_multipart(filename, content, content_type, fields=()):
    boundary = 'runtime-' + uuid.uuid4().hex
    parts = []
    for name, value in fields:
        parts.append(('--' + boundary + '\r\nContent-Disposition: form-data; name="' +
                      name + '"\r\n\r\n' + value + '\r\n').encode())
    parts.append(('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; '
                  'filename="' + filename + '"\r\nContent-Type: ' + content_type + '\r\n\r\n').encode())
    parts.extend((content, ('\r\n--' + boundary + '--\r\n').encode()))
    return b''.join(parts), 'multipart/form-data; boundary=' + boundary


def review_image_multipart(payload, content):
    """Use untrusted filename/MIME hints so the server must inspect the actual PNG bytes."""
    boundary = 'runtime-review-' + uuid.uuid4().hex
    return (('--' + boundary + '\r\nContent-Disposition: form-data; name="request"\r\n'
             'Content-Type: application/json\r\n\r\n' + json.dumps(payload) + '\r\n' +
             '--' + boundary + '\r\nContent-Disposition: form-data; name="image"; '
             'filename="ignored.bin"\r\nContent-Type: application/octet-stream\r\n\r\n').encode() +
            content + ('\r\n--' + boundary + '--\r\n').encode(),
            'multipart/form-data; boundary=' + boundary)


def multipart(zip_path, expected_revision=None):
    fields = (('versionName', 'Runtime fixture version'),
              ('description', 'Original nested HTML restore sample'))
    if expected_revision is not None:
        fields += (('expectedRevisionId', expected_revision),)
    return file_multipart('runtime-fixture.zip', zip_path.read_bytes(), 'application/zip',
                          fields)


def system_file_url(value):
    # Only read the local static-image capability returned by this deployment, never a remote URL.
    require(isinstance(value, str) and re.fullmatch(
        r'/api/file/static/[0-9]{8}/[a-f0-9-]{36}\.png', value),
        'System upload did not return a local PNG reference')
    return value


class Api:
    """HTTP boundary assertions without printing request credentials or capability URLs."""

    def __init__(self, origin, secrets_seen):
        self.origin = origin
        self.token = None
        self.secrets_seen = secrets_seen

    def request(self, method, path, payload=None, expected=200, headers=None, raw=False):
        request_headers = dict(headers or {})
        if self.token:
            request_headers['X-Access-Token'] = self.token
        if payload is not None and not isinstance(payload, bytes):
            self.remember_secrets(payload)
            payload = json.dumps(payload).encode()
            request_headers['Content-Type'] = 'application/json'
        request = urllib.request.Request(self.origin + path, payload, request_headers, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=45)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status = response.status
            body = response.read()
            response_headers = response.headers
        accepted = expected if isinstance(expected, tuple) else (expected,)
        require(status in accepted, 'HTTP {} expected {}, got {}'.format(method, expected, status))
        if raw:
            return body, response_headers
        result = json.loads(body)
        self.remember_secrets(result)
        if status == 200:
            require(result.get('success') is True, 'API returned an unsuccessful response')
            return result.get('result')
        return result

    def remember_secrets(self, value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key in ('token', 'accessKey', 'previewUrl', 'runtimeBaseUrl', 'pagesUrl', 'csrfToken',
                           'invitationToken', 'email', 'displayName', 'company', 'text', 'summary',
                           'authorName', 'authorCompany', 'visitTicket', 'clientRequestId',
                           'businessTerm', 'note', 'requirementUrl', 'evidenceReference') and item:
                    self.secrets_seen.add(str(item))
                self.remember_secrets(item)
        elif isinstance(value, list):
            for item in value:
                self.remember_secrets(item)

    def login(self, username, password):
        self.token = self.request('POST', '/api/authorize/login',
                                  {'username': username, 'password': password})['token']

    def project(self, name, key=None, expected=200):
        headers = {'Idempotency-Key': key} if key else {}
        return self.request('POST', '/api/prototype-hub/projects', {'name': name}, expected, headers)

    def upload(self, project, zip_path, key, expected=200):
        body, content_type = multipart(zip_path)
        return self.request('POST', '/api/prototype-hub/projects/' + project + '/versions', body,
                            expected=expected, headers={'Content-Type': content_type, 'Idempotency-Key': key})

    def replace(self, version, zip_path):
        body, content_type = multipart(zip_path, version['currentRevisionId'])
        return self.request('PUT', '/api/prototype-hub/versions/' + version['id'], body,
                            headers={'Content-Type': content_type})

    def usage(self):
        return self.request('GET', '/api/prototype-hub/projects/_usage')


class RuntimeCheck:
    """Own every process, container and directory created by a single verification run."""

    def __init__(self, args, workspace):
        self.args = args
        self.workspace = workspace
        self.container = None
        self.container_name = 'prototype-runtime-' + uuid.uuid4().hex
        self.container_owner = uuid.uuid4().hex
        self.container_attempted = False
        self.processes = []
        self.playwright_processes = set()
        self.secrets_seen = set()
        self.password = secrets.token_urlsafe(30)
        self.database_password = secrets.token_urlsafe(30)
        self.share_password = secrets.token_urlsafe(20)
        self.member_password = secrets.token_urlsafe(30)
        self.secrets_seen.update((self.password, self.database_password, self.share_password,
                                  self.member_password))
        self.username = 'runtime-admin'
        self.database_port = None
        self.stage = 'initialization'
        self.external_failure = None
        self.mailbox = None
        self.ui_port = None
        self.report = {'schemaVersion': 1, 'status': 'running', 'checks': [],
                       'jarSha256': digest_file(args.jar), 'postgresImage': args.postgres_image,
                       'sample': {'kind': 'original-html-fixture', 'fileCount': 5}}

    def command(self, args, *, input_data=None, stdout=None, env=None):
        try:
            result = subprocess.run(args, input=input_data, stdout=stdout or subprocess.PIPE,
                                    stderr=subprocess.PIPE, env=env, timeout=120, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise VerificationFailure('External command failed during ' + self.stage) from None
        if result.returncode != 0:
            self.external_failure = result.stderr.decode(errors='replace')[-6000:]
            raise VerificationFailure('External command failed during ' + self.stage)
        return result.stdout

    def postgres(self):
        self.stage = 'disposable-postgresql'
        docker_environment = dict(os.environ, POSTGRES_PASSWORD=self.database_password)
        # Register the name and unique ownership label before the daemon can create anything.
        # A CLI timeout may occur after creation, without returning a container ID.
        self.container_attempted = True
        identifier = self.command(['docker', 'run', '--rm', '-d', '--name', self.container_name,
                                   '--label', 'com.prototype-hub.runtime-owner=' + self.container_owner,
                                   '-p', '127.0.0.1::5432', '-e', 'POSTGRES_PASSWORD',
                                   '-e', 'POSTGRES_DB=runtime_source', self.args.postgres_image],
                                  env=docker_environment).decode().strip()
        require(len(identifier) == 64 and all(c in '0123456789abcdef' for c in identifier),
                'Docker did not return a container identity')
        self.container = identifier
        self.report['postgresImageId'] = self.command(
            ['docker', 'inspect', '--format', '{{.Image}}', identifier]).decode().strip()
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            status = subprocess.run(['docker', 'exec', identifier, 'pg_isready', '-U', 'postgres',
                                     '-d', 'runtime_source'], stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL, timeout=10)
            if status.returncode == 0:
                break
            time.sleep(0.25)
        else:
            raise VerificationFailure('Disposable PostgreSQL did not become ready')
        port = self.command(['docker', 'port', identifier, '5432/tcp']).decode().strip()
        require(port.startswith('127.0.0.1:'), 'Disposable PostgreSQL is not loopback-only')
        self.database_port = int(port.rsplit(':', 1)[1])

    def sql(self, database, sql, *, variables=None):
        bindings = []
        for name, value in (variables or {}).items():
            bindings.extend(('-v', name + '=' + value))
        return self.command(['docker', 'exec', '-i', self.container, 'psql', '-X', '-qAt',
                             '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', database] + bindings,
                            input_data=sql.encode()).decode().strip()

    def database_manifest(self, database):
        actual = set(self.sql(database, "select tablename from pg_tables where schemaname=current_schema() "
                              "and left(tablename,2)='p_' order by tablename;").splitlines())
        require(actual == set(BUSINESS_TABLES), 'Current database business tables are missing or unreviewed')
        result = {}
        for table in TABLES:
            result[table] = json.loads(self.sql(database,
                "select json_build_object('count', count(*), 'contentMd5', "
                "md5(coalesce(jsonb_agg(to_jsonb(row_data) order by id)::text, '[]'))) "
                'from ' + table + ' row_data;'))
        return result

    def database_rows(self, database):
        return {table: json.loads(self.sql(database,
                "select coalesce(jsonb_object_agg(id, md5(to_jsonb(r)::text)), '{}'::jsonb) from " +
                table + ' r;')) for table in TABLES}

    def review_rows(self, database):
        result = {}
        expiring = ('p_review_challenge', 'p_review_session', 'p_review_rate',
                    'p_review_unverified_guest', 'p_review_invitation')
        for table in REVIEW_TABLES:
            deadline = ('expires_at' if table in expiring else
                        'create_time + ' + str(RETENTION_MILLISECONDS) if table == 'p_review_event'
                        else 'null')
            result[table] = json.loads(self.sql(database,
                "select coalesce(jsonb_object_agg(id, jsonb_build_object("
                "'hash', md5(to_jsonb(row_data)::text), 'deadline', " + deadline + ")), '{}') "
                'from ' + table + ' row_data;'))
        return result

    def start(self, database, storage, system_storage, label):
        port = free_port()
        working_directory = self.workspace / label
        working_directory.mkdir(mode=0o700)
        storage.mkdir(parents=True, exist_ok=True)
        system_storage.mkdir(parents=True, exist_ok=True)
        # Do not inherit SPRING_*, database, admin or JVM injection variables from the operator.
        environment = {key: value for key, value in os.environ.items()
                       if key in ('PATH', 'JAVA_HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'TZ')}
        environment.update({
            'SPRING_CONFIG_LOCATION': 'classpath:/application.yml',
            'SERVER_PORT': str(port), 'DB_HOST': '127.0.0.1',
            'DB_PORT': str(self.database_port), 'DB_DATABASE': database,
            'DB_USERNAME': 'postgres', 'DB_PASSWORD': self.database_password,
            'ADMIN_USERNAME': self.username, 'ADMIN_USER_PASSWORD': self.password,
            'LOGIN_ENCRYPTION_ENABLED': 'false', 'CAPTCHA_ENABLED': 'false',
            'REDIS_ENABLED': 'false', 'ES_ENABLED': 'false', 'HSWEB_CACHE_TYPE': 'caffeine',
            'PROTOTYPE_HUB_STORAGE_ROOT': str(storage),
            'SYSTEM_FILE_STORAGE_ROOT': str(system_storage),
            'UPLOAD_TEMP': str(working_directory / 'uploads'),
            'SYSTEM_LOG_ENABLED': 'false', 'ACCESS_LOG_ENABLED': 'false',
            'LOG_FILE': str(working_directory / 'application.log')})
        if self.mailbox:
            environment.update({
                'SPRING_MAIL_HOST': '127.0.0.1', 'SPRING_MAIL_PORT': str(self.mailbox.port),
                'SPRING_MAIL_USERNAME': 'identity@example.invalid',
                'PROTOTYPEHUB_REVIEW_COOKIESECURE': 'false'})
            if self.ui_port:
                environment['PROTOTYPEHUB_REVIEW_TRUSTEDORIGIN'] = 'http://127.0.0.1:' + str(self.ui_port)
        command = [str(self.args.java), '-Xms128m', '-Xmx512m', '-jar', str(self.args.jar),
                   '--server.address=127.0.0.1', '--prototype-hub.cleanup.interval-ms=250']
        if self.mailbox:
            # The disposable loopback receiver has no authentication/TLS. Never weaken deployed defaults.
            command.extend('--spring.mail.properties.mail.smtp.' + key + '=false'
                           for key in ('auth', 'starttls.enable', 'starttls.required', 'ssl.enable'))
        output = (working_directory / 'process.log').open('wb')
        try:
            process = subprocess.Popen(command,
                                       cwd=working_directory, env=environment,
                                       stdout=output, stderr=subprocess.STDOUT,
                                       start_new_session=True)
        finally:
            output.close()
        self.processes.append(process)
        api = Api('http://127.0.0.1:' + str(port), self.secrets_seen)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            require(process.poll() is None, 'Application exited during ' + self.stage)
            try:
                with urllib.request.urlopen(api.origin + '/actuator/health/readiness', timeout=2) as response:
                    if response.status == 200 and json.load(response).get('status') == 'UP':
                        api.login(self.username, self.password)
                        return api, process
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                pass  # Poll actual readiness; a fixed startup delay cannot prove initialization.
            time.sleep(0.25)
        raise VerificationFailure('Application readiness timed out during ' + self.stage)

    def stop(self, process):
        # Playwright owns detached browser/webServer groups. Its public SIGINT cancellation
        # must complete teardown before its session is reaped; killing pnpm cannot do that.
        playwright = process in self.playwright_processes
        cancelled = True
        try:
            if playwright:
                if process.poll() is None:
                    process.send_signal(signal.SIGINT)
            else:
                os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        if process.poll() is None:
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                cancelled = False
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=10)
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        require(process.poll() is not None, 'Owned application process did not stop')
        if process in self.processes:
            self.processes.remove(process)
        self.playwright_processes.discard(process)
        require(not playwright or cancelled,
                'Playwright cancellation timed out; detached resource cleanup is unconfirmed')

    @contextmanager
    def phase(self, name):
        self.stage = name
        started = time.monotonic()
        yield
        self.report['checks'].append({'name': name, 'status': 'passed',
                                      'durationSeconds': round(time.monotonic() - started, 2)})
        print(name + ': passed', flush=True)

    def create_project_member(self, api, project):
        username = 'runtime-viewer-' + uuid.uuid4().hex
        user = api.request('POST', '/api/user/detail/_create',
                           {'name': 'Runtime project viewer', 'username': username,
                            'password': self.member_password})
        user_id = user['id']
        api.request('POST', '/api/role/MEMBER/users/_bind', [user_id])
        api.request('PUT', '/api/prototype-hub/projects/' + project['id'] + '/members/' + user_id,
                    {'role': 'viewer'})
        return {'id': user_id, 'username': username}

    def verify_project_member(self, api, member, project, version, expected_preview):
        viewer = Api(api.origin, self.secrets_seen)
        viewer.login(member['username'], self.member_password)
        visible = viewer.request('GET', '/api/prototype-hub/projects')
        require(len(visible) == 1 and visible[0]['id'] == project['id'] and
                visible[0].get('role') == 'viewer', 'Restored project member scope differs')
        require(not visible[0].get('capabilities', {}).get('edit', True),
                'Restored viewer unexpectedly received editing capability')
        require(self.preview(viewer, version) == expected_preview,
                'Project member could not read the same bounded preview')

    def preview(self, api, version):
        # Internal members exchange their platform identity for a limited capability first.
        # Public-share responses already carry their independent external authorization.
        if version.get('id'):
            require(not version.get('previewUrl') and not version.get('runtimeBaseUrl'),
                    'Version management response disclosed a permanent preview capability')
            path = '/api/prototype-hub/versions/' + version['id']
            if version.get('revisionId'):
                path += '/revisions/' + version['revisionId']
            version = api.request('POST', path + '/preview-session')
            now = int(time.time() * 1000)
            require(now < version.get('expiresAt', 0) <= now + 300000,
                    'Internal preview expiry is not bounded to five minutes')
        # Every HTML, nested page and asset read below must work without a platform token.
        api = Api(api.origin, self.secrets_seen)
        body, headers = api.request('GET', version['previewUrl'], raw=True)
        require(b'Runtime fixture home' in body, 'Preview entry content missing')
        sandbox = headers.get('Content-Security-Policy', '')
        require('sandbox' in sandbox and 'allow-scripts' in sandbox and
                'allow-same-origin' not in sandbox, 'Preview sandbox boundary changed')
        child, _ = api.request('GET', version['runtimeBaseUrl'] + '/nested/child.html', raw=True)
        require(b'Runtime fixture nested page' in child, 'Nested preview content missing')
        css, _ = api.request('GET', version['runtimeBaseUrl'] + '/assets/style.css', raw=True)
        require(css == (FIXTURE / 'assets/style.css').read_bytes(), 'Preview relative asset differs')
        return {'entry': hashlib.sha256(body).hexdigest(), 'child': hashlib.sha256(child).hexdigest(),
                'css': hashlib.sha256(css).hexdigest()}

    def verify_share(self, api, share, expected_preview=None):
        api = Api(api.origin, self.secrets_seen)
        path = '/api/prototype-hub/public/shares/' + share['token']
        locked = api.request('GET', path)
        require(locked['passwordRequired'] and not locked.get('accessKey') and
                not locked.get('runtimeBaseUrl'), 'Password-protected share disclosed access')
        rejected = api.request('POST', path + '/verify', {'password': 'incorrect'}, expected=403)
        require(rejected.get('code') == 'PROTOTYPE_HUB_SHARE_PASSWORD_INVALID',
                'Incorrect share password was not rejected')
        granted = api.request('POST', path + '/verify', {'password': self.share_password})
        require(granted.get('accessKey'), 'Share access was not granted')
        require('/revisions/' + granted['revisionId'] + '/runtime' in granted['runtimeBaseUrl'] and
                '/revisions/' + granted['revisionId'] + '/pages' in granted.get('pagesUrl', ''),
                'Public share did not provide revision-scoped runtime and pages URLs')
        require(api.request('GET', granted['pagesUrl']), 'Public share pages are empty')
        if share.get('revisionId'):
            require(granted.get('revisionId') == share['revisionId'], 'Public share changed its fixed revision')
        current = self.preview(api, granted)
        if expected_preview:
            require(current == expected_preview, 'Share content changed after process/restore')
        return current

    def verify_share_lifecycle(self, api, latest, first_grant, original, current,
                               original_hashes, current_hashes):
        """Exercise management revocation against anonymous requests to the packaged JAR."""
        anonymous = Api(api.origin, self.secrets_seen)

        def info(share, expected=200):
            return anonymous.request('GET', '/api/prototype-hub/public/shares/' + share['token'],
                                     expected=expected)

        def changed(share, suffix, body):
            method = 'POST' if suffix in ('/rotate-link', '/revoke') else 'PUT'
            return api.request(method, '/api/prototype-hub/shares/' + share['id'] + suffix, body)

        def denied(grant, expected):
            for path in (grant['previewUrl'], grant['runtimeBaseUrl'] + '/assets/style.css',
                         grant['pagesUrl']):
                anonymous.request('GET', path, expected=expected)

        current_grant = info(latest)
        require(current_grant['revisionId'] == current['revisionId'],
                'Latest share did not resolve the current revision')
        require(self.preview(api, current_grant) == current_hashes and
                self.preview(api, first_grant) == original_hashes,
                'Previously granted latest-share snapshot drifted after an upload')
        paused = changed(latest, '/enabled', {'expectedVersion': 1, 'enabled': False})
        info(paused, 404)
        denied(first_grant, 404)
        resumed = changed(paused, '/enabled', {'expectedVersion': 2, 'enabled': True})
        denied(first_grant, 403)
        denied(current_grant, 403)
        resumed_grant = info(resumed)
        config = {'expectedVersion': 3, 'description': 'Runtime fixed snapshot', 'expiresAt': None,
                  'revisionMode': 'fixed', 'revisionId': original['revisionId'],
                  'passwordAction': 'replace', 'password': self.share_password}
        protected = changed(resumed, '', config)
        denied(resumed_grant, 403)
        self.verify_share(api, protected, original_hashes)
        protected_grant = anonymous.request('POST', '/api/prototype-hub/public/shares/' +
                                           protected['token'] + '/verify',
                                           {'password': self.share_password})
        config.update(expectedVersion=4, passwordAction='remove')
        config.pop('password')
        unprotected = changed(protected, '', config)
        denied(protected_grant, 403)
        require(not info(unprotected)['passwordRequired'], 'Password removal did not take effect')
        before_rotation = info(unprotected)
        api.request('PUT', '/api/prototype-hub/shares/' + unprotected['id'] + '/enabled',
                    {'expectedVersion': 4, 'enabled': False}, expected=409)
        rotated = changed(unprotected, '/rotate-link', {'expectedVersion': 5})
        require(rotated['id'] == latest['id'] and rotated['token'] != unprotected['token'],
                'Link rotation did not preserve the record and replace its token')
        info(unprotected, 404)
        denied(before_rotation, 404)
        final_grant = info(rotated)
        require(self.preview(api, final_grant) == original_hashes,
                'Link rotation changed the fixed snapshot')
        revoked = changed(rotated, '/revoke', {'expectedVersion': 6})
        require(revoked['revoked'] and revoked['configVersion'] == 7,
                'Terminal revoke or configuration version did not persist')
        info(revoked, 404)
        denied(final_grant, 404)
        api.request('PUT', '/api/prototype-hub/shares/' + revoked['id'] + '/enabled',
                    {'expectedVersion': 7, 'enabled': True}, expected=409)
        self.report['shareLifecycle'] = {
            'fixedAndLatestSnapshots': True, 'oldHtmlAssetsAndPagesRejected': True,
            'pauseResumeNeverRevivesKeys': True, 'passwordReplaceAndRemove': True,
            'staleConfigRejected': True, 'linkRotated': True, 'terminalRevoke': True}
        return revoked, final_grant

    def verify_share_policy(self, api, share, *, grant=None, expected_preview=None):
        rows = api.request('GET', '/api/prototype-hub/versions/' + share['versionId'] + '/shares')
        row = next((item for item in rows if item['id'] == share['id']), None)
        require(row is not None, 'Restored share management record is missing')
        for field in ('revisionMode', 'revisionId', 'revoked', 'configVersion'):
            require(row.get(field) == share.get(field), 'Restored share policy changed: ' + field)
        anonymous = Api(api.origin, self.secrets_seen)
        path = '/api/prototype-hub/public/shares/' + share['token']
        if share['revoked']:
            anonymous.request('GET', path, expected=404)
            for target in (grant['previewUrl'], grant['pagesUrl']):
                anonymous.request('GET', target, expected=404)
        elif expected_preview is not None:
            require(self.preview(api, anonymous.request('GET', path)) == expected_preview,
                    'Restored latest-share content changed')

    def review_call(self, api, grant, operation, payload, *, cookie=None, csrf=None, expected=200,
                    method='POST', image=None):
        headers = {'Origin': ('http://127.0.0.1:' + str(self.ui_port)) if self.ui_port else api.origin}
        if cookie:
            headers['Cookie'] = 'prototype-review=' + cookie
        if csrf:
            headers['X-Review-CSRF'] = csrf
        request_payload = dict(revisionId=grant['revisionId'], accessKey=grant['accessKey'], **payload)
        if image is not None:
            api.remember_secrets(request_payload)
            request_payload, headers['Content-Type'] = review_image_multipart(request_payload, image)
        body, response_headers = Api(api.origin, self.secrets_seen).request(
            method, '/api/prototype-hub/public/shares/' + grant['token'] + '/review/' + operation,
            request_payload, expected=expected, headers=headers, raw=True)
        data = json.loads(body)
        self.secrets_seen.update(value for value in (cookie, csrf) if value)
        Api(api.origin, self.secrets_seen).remember_secrets(data)
        updated = SimpleCookie()
        for header in response_headers.get_all('Set-Cookie', []):
            updated.load(header)
        if 'prototype-review' in updated:
            morsel = updated['prototype-review']
            require(morsel['httponly'] and morsel['samesite'].lower() == 'strict',
                    'Review cookie omitted HttpOnly or SameSite')
            require(morsel['path'] == '/api/prototype-hub/public/shares/' + grant['token'] + '/review',
                    'Review cookie escaped its dedicated API path')
            require(not morsel['domain'], 'Review cookie widened its domain')
            cookie = morsel.value
            self.secrets_seen.add(cookie)
        require(data.get('success') is True if expected == 200 else not data.get('success'),
                'Review API wrapper differs')
        return (data.get('result') if expected == 200 else data), cookie

    def create_review_identity(self, api, version):
        share = api.request('POST', '/api/prototype-hub/versions/' + version['id'] + '/shares', {})
        policy = api.request('PUT', '/api/prototype-hub/shares/' + share['id'] + '/review',
                             {'enabled': True, 'allowUnverified': True, 'allowComment': True,
                              'expectedVersion': share['configVersion']})
        require(policy['enabled'] and policy['allowUnverified'] and policy['allowComment'] and
                policy['mailAvailable'], 'Local review capabilities are unavailable')
        grant = Api(api.origin, self.secrets_seen).request(
            'GET', '/api/prototype-hub/public/shares/' + share['token'])
        context, cookie = self.review_call(api, grant, 'context', {})
        require(context['session'] is None and cookie, 'Review bootstrap unexpectedly has an identity')
        recipient = 'review-' + uuid.uuid4().hex + '@example.invalid'
        self.secrets_seen.add(recipient)
        users_before = self.sql('runtime_source', 'select count(*) from s_user;')
        challenge, cookie = self.review_call(api, grant, 'challenges',
            {'email': recipient, 'displayName': 'Local mailbox reviewer', 'company': 'Original fixture'},
            cookie=cookie, csrf=context['csrfToken'])
        mail = self.mailbox.latest(recipient)
        require(mail is not None, 'SMTP did not deliver the local verification message')
        verified, cookie = self.review_call(api, grant, 'verify',
            {'challengeId': challenge['challengeId'], 'code': mail['code']},
            cookie=cookie, csrf=context['csrfToken'])
        identity = verified['session']
        require(identity and identity['verificationLevel'] == 'email' and
                identity['displayName'] == 'Local mailbox reviewer' and
                identity['maskedEmail'] != recipient, 'Email identity response differs')
        require(self.sql('runtime_source', 'select count(*) from s_user;') == users_before,
                'Guest verification created an internal platform user')
        self.report['reviewIdentity'] = {'localSmtpAccepted': True, 'mailboxOnlyVerification': True,
                                         'noInternalAccountCreated': True,
                                         'cookiePathAndFlagsVerified': True}
        fixture = {'grant': grant, 'cookie': cookie, 'guestId': identity['guestId'],
                   'projectId': version['projectId'], 'versionId': version['id'],
                   'share': share, 'policy': policy}
        self.verify_review_guest_control(api, fixture, recipient)
        self.create_review_fixtures(api, fixture)
        require(self.sql('runtime_source', 'select count(*) from s_user;') == users_before,
                'Unverified or invited review created an internal platform user')
        return fixture

    def verify_review_guest_control(self, api, fixture, recipient):
        """Exercise actual guest revocation before checking restart/replica/restore persistence."""
        grant = fixture['grant']
        path = '/api/prototype-hub/projects/' + fixture['projectId'] + '/review-guests'
        page = api.request('POST', path + '/_query', {'pageIndex': 0, 'pageSize': 20})
        row = next((item for item in page['data'] if item['id'] == fixture['guestId']), None)
        require(row is not None and row['enabled'] and row['maskedEmail'] != recipient and
                recipient not in json.dumps(page) and row['verificationLevel'] == 'email',
                'Managed guest page is missing or discloses a full mailbox')
        reset = "update p_review_rate set next_allowed_at=0 where project_id=:'project';"
        self.sql('runtime_source', reset, variables={'project': fixture['projectId']})
        anonymous, anonymous_cookie = self.review_call(api, grant, 'context', {})
        pending, anonymous_cookie = self.review_call(api, grant, 'challenges',
            {'email': recipient, 'displayName': 'Local mailbox reviewer'},
            cookie=anonymous_cookie, csrf=anonymous['csrfToken'])
        old_mail = self.mailbox.latest(recipient)
        require(old_mail is not None, 'Pending revocation fixture mail was not received')
        endpoint = path + '/' + fixture['guestId'] + '/enabled'
        disabled = api.request('PUT', endpoint, {'enabled': False, 'expectedVersion': row['configVersion']})
        require(not disabled['enabled'] and disabled['configVersion'] == row['configVersion'] + 1,
                'Guest disable did not advance the authoritative state')
        old_cookie = fixture['cookie']
        context, _ = self.review_call(api, grant, 'context', {}, cookie=old_cookie)
        require(context['session'] is None, 'Disabled guest retained its old session')
        self.review_call(api, grant, 'challenges',
            {'email': recipient, 'displayName': 'Local mailbox reviewer'},
            cookie=anonymous_cookie, csrf=anonymous['csrfToken'], expected=403)
        restored = api.request('PUT', endpoint,
                              {'enabled': True, 'expectedVersion': disabled['configVersion']})
        require(restored['enabled'] and restored['configVersion'] == disabled['configVersion'] + 1,
                'Guest restore did not advance the authoritative state')
        context, _ = self.review_call(api, grant, 'context', {}, cookie=old_cookie)
        require(context['session'] is None, 'Restoring a guest resurrected its old cookie')
        self.review_call(api, grant, 'verify',
            {'challengeId': pending['challengeId'], 'code': old_mail['code']},
            cookie=anonymous_cookie, csrf=anonymous['csrfToken'], expected=400)
        self.sql('runtime_source', reset, variables={'project': fixture['projectId']})
        fresh, fresh_cookie = self.review_call(api, grant, 'context', {})
        challenge, fresh_cookie = self.review_call(api, grant, 'challenges',
            {'email': recipient, 'displayName': 'Local mailbox reviewer', 'company': 'Original fixture'},
            cookie=fresh_cookie, csrf=fresh['csrfToken'])
        mail = self.mailbox.latest(recipient)
        require(mail is not None, 'Fresh verification mail was not received after guest restore')
        verified, fresh_cookie = self.review_call(api, grant, 'verify',
            {'challengeId': challenge['challengeId'], 'code': mail['code']},
            cookie=fresh_cookie, csrf=fresh['csrfToken'])
        require(verified['session'] and verified['session']['guestId'] == fixture['guestId'],
                'Fresh verification after restore did not recover the same project guest')
        fixture['cookie'] = fresh_cookie
        fixture['identity'] = verified['session']
        self.report['reviewIdentity']['guestManagement'] = {
            'maskedPage': True, 'disableAndRestore': True, 'oldCookieAndCodeRejected': True,
            'newOtpRequiredForSameIdentity': True}

    def verify_review_identity(self, api, fixture):
        context, _ = self.review_call(api, fixture['grant'], 'context', {}, cookie=fixture['cookie'])
        require(context['session'] and context['session']['guestId'] == fixture['guestId'],
                'Persisted review identity did not survive process or database restore')
        require(context['session'] == fixture['identity'] and
                context['session']['expiresAt'] > int(time.time() * 1000),
                'Persisted review identity profile, level or original expiry changed')
        return context

    def create_review_fixtures(self, api, fixture):
        """Create the new review data through the same authenticated HTTP boundaries as users."""
        grant = fixture['grant']
        context, cookie = self.review_call(api, grant, 'context', {})
        context, cookie = self.review_call(api, grant, 'unverified',
            {'displayName': 'Runtime unverified reviewer', 'company': 'Original review fixture'},
            cookie=cookie, csrf=context['csrfToken'])
        identity = context['session']
        require(identity and identity['verificationLevel'] == 'unverified' and
                identity['maskedEmail'] is None, 'Low assurance fixture was not explicitly unverified')
        fixture['unverified'] = {'grant': grant, 'cookie': cookie, 'guestId': identity['guestId'],
                                 'identity': identity}
        fixture['invitations'] = {}
        endpoint = '/api/prototype-hub/shares/' + fixture['share']['id'] + '/review-invitations'
        for state in ('pending', 'revoked', 'consumed'):
            recipient = 'invite-' + uuid.uuid4().hex + '@example.invalid'
            self.secrets_seen.add(recipient)
            created = api.request('POST', endpoint,
                {'email': recipient, 'expectedShareVersion': fixture['policy']['configVersion']})
            row = created['invitation']
            require(row['status'] == 'pending' and row['consumedAt'] is None and
                    row['expiresAt'] > int(time.time() * 1000), 'New invitation is not pending')
            item = {'row': row, 'token': created['invitationToken'], 'email': recipient}
            fixture['invitations'][state] = item
            if state == 'revoked':
                item['row'] = api.request('PUT', endpoint + '/' + row['id'] + '/revoke',
                                         {'expectedVersion': row['configVersion']})
                require(item['row']['status'] == 'revoked' and
                        item['row']['configVersion'] == row['configVersion'] + 1,
                        'Invitation revoke did not advance its CAS version')
            elif state == 'consumed':
                challenge = self.review_invitation_challenge(api, fixture, item)
                verified, verified_cookie = self.review_call(api, grant, 'verify',
                    {'challengeId': challenge['id'], 'code': challenge['code']},
                    cookie=challenge['cookie'], csrf=challenge['csrf'])
                require(verified['session'] and verified['session']['verificationLevel'] == 'email',
                        'Invitation bypassed mailbox verification')
                item['identity'] = {'grant': grant, 'cookie': verified_cookie,
                                    'guestId': verified['session']['guestId'],
                                    'identity': verified['session']}
                page = api.request('POST', endpoint + '/_query', {'pageIndex': 0, 'pageSize': 20})
                item['row'] = next(value for value in page['data'] if value['id'] == row['id'])
                require(item['row']['status'] == 'consumed' and
                        item['row']['configVersion'] == row['configVersion'] + 1 and
                        item['row']['consumedAt'] is not None,
                        'OTP did not atomically consume the invitation')
        self.verify_review_invitations(api, fixture, 'runtime_source')
        self.create_review_discussion(api, fixture)
        self.verify_review_fixtures(api, fixture, 'runtime_source')

    def review_invitation_challenge(self, api, fixture, invitation):
        context, cookie = self.review_call(api, fixture['grant'], 'context', {})
        challenge, cookie = self.review_call(api, fixture['grant'], 'challenges',
            {'email': invitation['email'], 'displayName': 'Runtime invited reviewer',
             'invitationToken': invitation['token']}, cookie=cookie, csrf=context['csrfToken'])
        mail = self.mailbox.latest(invitation['email'])
        require(mail is not None, 'Invited mailbox did not receive its local OTP')
        return {'id': challenge['challengeId'], 'expiresAt': challenge['expiresAt'],
                'cookie': cookie, 'csrf': context['csrfToken'], 'code': mail['code']}

    def verify_review_invitations(self, api, fixture, database):
        endpoint = '/api/prototype-hub/shares/' + fixture['share']['id'] + '/review-invitations'
        page = api.request('POST', endpoint + '/_query', {'pageIndex': 0, 'pageSize': 20})
        require(page['total'] == 3, 'Restored invitation management page lost a fixture')
        actual = {row['id']: row for row in page['data']}
        for state, item in fixture['invitations'].items():
            row = item['row']
            require(actual.get(row['id']) == row and row['status'] == state and
                    row['expiresAt'] > int(time.time() * 1000),
                    'Invitation status, version or original deadline changed')
            require(item['email'] not in json.dumps(page) and item['token'] not in json.dumps(page),
                    'Invitation management disclosed a mailbox or capability')
            matched = self.sql(database, """
                select count(*) from p_review_invitation i
                join p_share s on s.id=i.share_id
                left join p_review_guest g on g.project_id=i.project_id and g.email=i.email
                where i.id=:'id' and i.email=:'email' and i.share_id=:'share'
                  and i.project_id=:'project' and i.version_id=:'version' and i.revision_id=:'revision'
                  and i.share_config_version=s.config_version and i.share_config_version=:'shareVersion'::bigint
                  and i.share_generation=encode(sha256(convert_to(s.access_key,'UTF8')),'hex')
                  and i.token_hash=:'hash' and i.guest_config_version=0
                  and i.guest_config_version=coalesce(g.config_version,0);
                """, variables={'id': row['id'], 'email': item['email'], 'share': fixture['share']['id'],
                                'project': fixture['projectId'], 'version': fixture['versionId'],
                                'revision': fixture['grant']['revisionId'],
                                'shareVersion': str(fixture['policy']['configVersion']),
                                'hash': hashlib.sha256(('review-invitation-v1:' + item['token']).encode()).hexdigest()})
            require(matched == '1', 'Invitation recipient or immutable authorization binding changed')

    def create_review_discussion(self, api, fixture):
        grant, visitor = fixture['grant'], fixture['unverified']
        pages = Api(api.origin, self.secrets_seen).request('GET', grant['pagesUrl'])
        def leaves(items):
            for item in items:
                if item.get('children'):
                    yield from leaves(item['children'])
                if item.get('pageId') and not item.get('folder'):
                    yield item
        page = next(leaves(pages), None)
        require(page is not None, 'Review fixture has no stable manifest page')
        base = {'revisionId': grant['revisionId'], 'pageId': page['pageId']}
        member_path = '/api/prototype-hub/versions/' + fixture['versionId'] + '/review-threads'
        context = self.verify_review_identity(api, visitor)
        operations = []
        def write(public, method, path, payload, image=None):
            value = dict(payload, clientRequestId=uuid.uuid4().hex)
            if public:
                result, _ = self.review_call(api, grant, path, value, cookie=visitor['cookie'],
                                             csrf=context['csrfToken'], method=method, image=image)
            elif image is not None:
                api.remember_secrets(value)
                content, content_type = review_image_multipart(dict(base, **value), image)
                result = api.request(method, member_path + path, content,
                                     headers={'Content-Type': content_type})
            else:
                result = api.request(method, member_path + path, dict(base, **value))
            operations.append({'public': public, 'method': method, 'path': path,
                               'payload': value, 'id': result['id'], 'image': image})
            return result
        anchor = {'space': 'root-document-css-px-v1', 'shape': 'rect', 'x': 10, 'y': 20,
                  'width': 40, 'height': 30, 'documentWidth': 1200, 'documentHeight': 1600,
                  'viewportWidth': 1000, 'viewportHeight': 800}
        thread = write(True, 'POST', 'threads',
                       {'pageId': page['pageId'], 'text': 'Original runtime visitor discussion',
                        'anchor': anchor})
        require(thread.get('anchor') == anchor, 'Review fixture lost its nonempty anchor')
        require(thread['pageId'] == page['pageId'] and thread['pagePath'] == page['path'] and
                thread['revisionId'] == grant['revisionId'], 'Thread did not bind its immutable page')
        identifier = thread['id']
        public_reply = write(False, 'POST', '/' + identifier + '/messages',
                             {'text': 'Original runtime PM public reply', 'visibility': 'public'})
        before_note = api.request('POST', member_path + '/_query', base)['data']
        note = write(False, 'POST', '/' + identifier + '/messages',
                     {'text': 'Private runtime PM note must never be public', 'visibility': 'internal'})
        require(api.request('POST', member_path + '/_query', base)['data'] == before_note,
                'An internal note changed public thread metadata')
        resolved = write(False, 'PUT', '/' + identifier + '/status',
                         {'state': 'resolved', 'expectedVersion': thread['configVersion']})
        require(resolved['state'] == 'resolved' and resolved['configVersion'] == 1,
                'PM resolve did not apply its status CAS')
        visitor_reply = write(True, 'POST', 'threads/' + identifier + '/messages',
                              {'pageId': page['pageId'], 'text': 'Original runtime visitor follow-up'})
        images = []
        for visibility, color in (('public', (20, 70, 110, 255)), ('internal', (130, 30, 80, 255))):
            content = sample_png(color)
            public = visibility == 'public'
            path = ('threads/' if public else '/') + identifier + '/messages-with-image'
            payload = {'pageId': page['pageId'], 'text': 'Original runtime ' + visibility + ' image'}
            if not public:
                payload['visibility'] = visibility
            image = write(public, 'POST', path, payload, content)
            require(image.get('image') == {'contentType': 'image/png', 'size': len(content),
                                          'width': 1, 'height': 1}, 'Image metadata differs')
            images.append({'id': image['id'], 'visibility': visibility, 'content': content})
        final_thread = api.request('POST', member_path + '/_query', base)['data'][0]
        require(final_thread['state'] == 'open' and final_thread['configVersion'] == 2,
                'Visitor reply did not reopen the resolved thread')
        messages = api.request('POST', member_path + '/' + identifier + '/messages/_query', base)
        require(messages['total'] == 6 and len(messages['data']) == 6,
                'Review fixture did not persist all public and internal messages')
        fixture['discussion'] = {'base': base, 'memberPath': member_path, 'thread': final_thread,
                                 'messages': messages['data'], 'noteId': note['id'],
                                 'operations': operations, 'images': images}
        require(public_reply['visibility'] == visitor_reply['visibility'] == 'public',
                'A public reply was not public')

    def verify_review_discussion(self, api, fixture, database):
        discussion, visitor = fixture['discussion'], fixture['unverified']
        context = self.verify_review_identity(api, visitor)
        base = discussion['base']
        public_base = {'pageId': base['pageId']}
        identifier = discussion['thread']['id']
        messages_path = 'threads/' + identifier + '/messages/_query'
        threads, _ = self.review_call(api, fixture['grant'], 'threads/_query', public_base,
            cookie=visitor['cookie'], csrf=context['csrfToken'])
        expected_thread = dict(discussion['thread'], canReopen=discussion['thread'].get('state') == 'resolved',
                               accessMode='own')
        require(threads['total'] == 1 and threads['data'] == [expected_thread],
                'Restored visitor page threads differ')
        public, _ = self.review_call(api, fixture['grant'], messages_path, public_base,
            cookie=visitor['cookie'], csrf=context['csrfToken'])
        expected_public = [row for row in discussion['messages'] if row['visibility'] == 'public']
        require(public['total'] == len(expected_public) and public['data'] == expected_public and
                all(row['visibility'] == 'public' and row['id'] != discussion['noteId']
                    for row in public['data']), 'Internal note escaped the visitor boundary')
        managed = api.request('POST', discussion['memberPath'] + '/' + identifier + '/messages/_query', base)
        require(managed['total'] == len(discussion['messages']) and managed['data'] == discussion['messages'],
                'Restored PM messages or internal notes differ')
        anonymous, cookie = self.review_call(api, fixture['grant'], 'context', {})
        self.review_call(api, fixture['grant'], 'threads/_query', public_base,
                         cookie=cookie, csrf=anonymous['csrfToken'], expected=403)
        wrong = self.verify_review_identity(api, fixture)
        hidden, _ = self.review_call(api, fixture['grant'], 'threads/_query', public_base,
            cookie=fixture['cookie'], csrf=wrong['csrfToken'])
        require(hidden['total'] == 0 and not hidden['data'], 'Another identity enumerated a private thread')
        self.review_call(api, fixture['grant'], messages_path, public_base,
            cookie=fixture['cookie'], csrf=wrong['csrfToken'], expected=404)
        for operation in discussion['operations']:
            if operation['public']:
                replay, _ = self.review_call(api, fixture['grant'], operation['path'], operation['payload'],
                    cookie=visitor['cookie'], csrf=context['csrfToken'], method=operation['method'],
                    image=operation.get('image'))
            elif operation.get('image') is not None:
                payload, content_type = review_image_multipart(dict(base, **operation['payload']), operation['image'])
                replay = api.request(operation['method'], discussion['memberPath'] + operation['path'],
                                     payload, headers={'Content-Type': content_type})
            else:
                replay = api.request(operation['method'], discussion['memberPath'] + operation['path'],
                                     dict(base, **operation['payload']))
            require(replay['id'] == operation['id'], 'Restored review receipt created a second resource')
        actual = json.loads(self.sql(database, """
            select json_build_object(
              'messages', (select count(*) from p_review_message where thread_id=:'thread'),
              'events', (select json_agg(event_type order by event_type) from p_review_event where thread_id=:'thread'),
              'receipts', (select count(*) from p_creation_receipt where revision_id=:'revision' and
                  (resource_id=:'thread' or resource_id in
                    (select id from p_review_message where thread_id=:'thread'))),
              'receiptHash', (select md5(coalesce(jsonb_agg(to_jsonb(r) order by id)::text,'[]'))
                  from p_creation_receipt r where revision_id=:'revision' and
                  (resource_id=:'thread' or resource_id in
                    (select id from p_review_message where thread_id=:'thread'))),
              'bound', not exists(select 1 from p_review_event e where e.thread_id=:'thread' and
                  (e.project_id<>:'project' or e.version_id<>:'version' or e.revision_id<>:'revision' or
                   (e.message_id is not null and not exists
                      (select 1 from p_review_message m where m.id=e.message_id and m.thread_id=e.thread_id)))));
            """, variables={'thread': identifier, 'revision': base['revisionId'],
                            'project': fixture['projectId'], 'version': fixture['versionId']}))
        require(re.fullmatch('[0-9a-f]{32}', actual.get('receiptHash', '')) is not None,
                'Review receipts have no stable checksum')
        receipt_hash = discussion.setdefault('receiptHash', actual['receiptHash'])
        events = ['thread.created', 'message.created', 'note.created', 'thread.resolved',
                  'message.created', 'thread.reopened']
        if discussion.get('images'):
            events += ['message.created', 'note.created']
            self.verify_review_images(api, fixture, database, context)
        if discussion.get('assignment'):
            events += ['thread.assigned']
        require(actual == {'messages': len(discussion['messages']), 'events': sorted(events),
                'receipts': len(discussion['messages']) + 1 + bool(discussion.get('assignment')),
                'receiptHash': receipt_hash, 'bound': True},
                'Review messages, atomic events or receipts differ')
        return actual

    def verify_review_images(self, api, fixture, database, context):
        """Check original metadata and authorized bytes; restored reads must not mint new images."""
        discussion, visitor = fixture['discussion'], fixture['unverified']
        images, base = discussion['images'], discussion['base']
        require(len(images) == 2 and {image['visibility'] for image in images} == {'public', 'internal'},
                'Image restore fixture must include public and internal images')
        rows = json.loads(self.sql(database, """
          select coalesce(json_agg(json_build_object('id',id,'projectId',project_id,'versionId',version_id,
            'revisionId',revision_id,'threadId',thread_id,'storagePath',image_path,'contentType',image_content_type,
            'size',image_size,'sha256',image_sha256,'width',image_width,'height',image_height) order by id),'[]')
          from p_review_message where id in (:'public',:'internal');
          """, variables={image['visibility']: image['id'] for image in images}))
        require(len(rows) == 2, 'Image database metadata is missing')
        require(rows == discussion.setdefault('imageRows', rows), 'Original image database metadata changed')
        for image in images:
            matching = [row for row in rows if row['id'] == image['id']]
            require(len(matching) == 1, 'Image metadata identity is not unique')
            row = matching[0]
            require(row['projectId'] == fixture['projectId'] and row['versionId'] == fixture['versionId'] and
                    row['revisionId'] == base['revisionId'] and row['threadId'] == discussion['thread']['id'] and
                    row['contentType'] == 'image/png' and row['size'] == len(image['content']) and
                    row['sha256'] == hashlib.sha256(image['content']).hexdigest() and
                    row['width'] == row['height'] == 1 and isinstance(row['storagePath'], str) and
                    re.fullmatch('projects/' + re.escape(fixture['projectId']) +
                                 '/review-images/[A-Za-z0-9_-]{43}/image', row['storagePath']) is not None,
                    'Image database bytes metadata or scope differs')
            route = '/' + discussion['thread']['id'] + '/messages/' + image['id'] + '/image'
            content, headers = api.request('POST', discussion['memberPath'] + route, base, raw=True)
            self.verify_image_content(content, headers, image['content'])
            origin = ('http://127.0.0.1:' + str(self.ui_port)) if self.ui_port else api.origin
            grant = fixture['grant']
            request_headers = {'Origin': origin, 'Cookie': 'prototype-review=' + visitor['cookie'],
                               'X-Review-CSRF': context['csrfToken']}
            content, headers = Api(api.origin, self.secrets_seen).request('POST',
                '/api/prototype-hub/public/shares/' + grant['token'] + '/review/threads' + route,
                dict(base, accessKey=grant['accessKey']), headers=request_headers,
                expected=200 if image['visibility'] == 'public' else 404, raw=True)
            if image['visibility'] == 'public':
                self.verify_image_content(content, headers, image['content'])
        self.report['reviewImages'] = {'count': 2, 'publicAndInternal': True,
                                       'databaseMetadataAndOriginalBytesVerified': True,
                                       'publicInternalImageDenied': True}

    @staticmethod
    def verify_image_content(content, headers, expected):
        require(content == expected and headers.get('Content-Type', '').split(';')[0] == 'image/png' and
                headers.get('X-Content-Type-Options') == 'nosniff' and
                {'private', 'no-store'} <= {value.strip().lower() for value in headers.get('Cache-Control', '').split(',')} and
                re.fullmatch(r'inline; filename="?image\.png"?', headers.get('Content-Disposition', '')) is not None,
                'Private image bytes or security headers differ')

    def verify_review_fixtures(self, api, fixture, database):
        self.verify_review_identity(api, fixture)
        self.verify_review_identity(api, fixture['unverified'])
        self.verify_review_identity(api, fixture['invitations']['consumed']['identity'])
        self.verify_review_invitations(api, fixture, database)
        self.verify_review_discussion(api, fixture, database)

    def verify_restored_invitation(self, api, fixture):
        """Consume the original pending challenge only after exact pre-start restore equality."""
        pending = fixture['invitations']['pending']
        for item, email in ((fixture['invitations']['revoked'], fixture['invitations']['revoked']['email']),
                            (fixture['invitations']['consumed'], fixture['invitations']['consumed']['email']),
                            (pending, 'wrong-' + uuid.uuid4().hex + '@example.invalid')):
            self.secrets_seen.add(email)
            context, cookie = self.review_call(api, fixture['grant'], 'context', {})
            before = len(self.mailbox.messages)
            result, _ = self.review_call(api, fixture['grant'], 'challenges',
                {'email': email, 'displayName': 'Invalid invitation attempt', 'invitationToken': item['token']},
                cookie=cookie, csrf=context['csrfToken'], expected=403)
            require(result.get('code') == 'PROTOTYPE_HUB_REVIEW_INVITATION_INVALID' and
                    len(self.mailbox.messages) == before, 'An invalid invitation sent mail or bypassed its binding')
        challenge = pending['challenge']
        require(challenge['expiresAt'] > int(time.time() * 1000),
                'Original restored pending OTP expired before verification; refusing to renew it')
        context, cookie = self.review_call(api, fixture['grant'], 'context', {}, cookie=challenge['cookie'])
        verified, _ = self.review_call(api, fixture['grant'], 'verify',
            {'challengeId': challenge['id'], 'code': challenge['code']},
            cookie=cookie, csrf=context['csrfToken'])
        require(verified['session'] and verified['session']['verificationLevel'] == 'email' and
                verified['session']['maskedEmail'] == pending['row']['maskedEmail'],
                'Restored pending invitation did not verify its intended mailbox')
        endpoint = '/api/prototype-hub/shares/' + fixture['share']['id'] + '/review-invitations/_query'
        rows = api.request('POST', endpoint, {'pageIndex': 0, 'pageSize': 20})['data']
        current = next(row for row in rows if row['id'] == pending['row']['id'])
        require(current['status'] == 'consumed' and current['configVersion'] == pending['row']['configVersion'] + 1 and
                current['expiresAt'] == pending['row']['expiresAt'] and current['consumedAt'] is not None,
                'Restored pending invitation did not atomically advance exactly once')
        before = len(self.mailbox.messages)
        context, cookie = self.review_call(api, fixture['grant'], 'context', {})
        result, _ = self.review_call(api, fixture['grant'], 'challenges',
            {'email': pending['email'], 'displayName': 'Invitation replay', 'invitationToken': pending['token']},
            cookie=cookie, csrf=context['csrfToken'], expected=403)
        require(result.get('code') == 'PROTOTYPE_HUB_REVIEW_INVITATION_INVALID' and
                len(self.mailbox.messages) == before, 'A consumed restored invitation replay sent mail')
        self.report['restoredReview'] = {'tableCount': len(REVIEW_TABLES),
            'emailAndUnverifiedOriginalIdentityAndExpiry': True, 'invitationBindingsAndTerminalStates': True,
            'originalPendingOtpConsumedOnce': True, 'wrongMailboxRevokedAndReplayRejectedWithoutMail': True,
            'pageThreadPublicAndInternalVisibility': True, 'atomicEventsAndReceipts': True}

    def verify_revisions(self, api, project, original, current, zip_path, key,
                         original_hashes, current_hashes, foreign_revision=None):
        path = '/api/prototype-hub/versions/' + original['id']
        require(original['revisionId'] != current['revisionId'], 'Replacement reused mutable revision identity')
        head = api.request('GET', path + '/current-revision')
        require(head['id'] == current['revisionId'], 'Logical current pointer changed')
        first = api.request('GET', path + '/revisions?limit=1')
        second = api.request('GET', path + '/revisions?limit=1&after=' + head['id'])
        require(len(first) == len(second) == 1 and first[0]['id'] == head['id']
                and second[0]['id'] == original['revisionId'], 'Revision keyset history differs')
        for item in (first[0], second[0]):
            require(re.fullmatch('[0-9a-f]{64}', item.get('manifestSha256', '')) is not None,
                    'Revision has no reproducible manifest digest')
            require(item.get('fileCount', 0) > 0 and item.get('totalBytes', 0) > 0,
                    'Revision lost its immutable file manifest summary')
        replayed = api.upload(project['id'], zip_path, key)
        require(replayed['id'] == original['id']
                and replayed['revisionId'] == original['revisionId']
                and replayed['currentRevisionId'] == current['revisionId']
                and replayed['revisionCount'] == 2, 'Creation receipt did not preserve the original snapshot')
        require(self.preview(api, original) == original_hashes, 'Historical preview bytes changed')
        require(self.preview(api, current) == current_hashes, 'Current revision preview bytes changed')
        occupancy = api.request('GET', '/api/prototype-hub/versions/_usage?projectId=' + project['id'])
        require(occupancy['versions']['used'] == 1, 'Revision replacement consumed a logical version slot')
        self.verify_revision_comparison(api, original, current, original_hashes, current_hashes,
                                        foreign_revision)
        self.verify_revision_quality(api, original, current, foreign_revision)
        return sorted(item['manifestSha256'] for item in (first[0], second[0]))

    def verify_revision_quality(self, api, original, current, foreign_revision=None):
        """Check the original complete static fixture through the actual fixed-revision endpoint."""
        base = '/api/prototype-hub/versions/' + original['id']
        limits = {'maxTextBytes': 2 * 1024 * 1024, 'maxTotalTextBytes': 32 * 1024 * 1024,
                  'maxReferences': 100000, 'maxFindings': 1000}
        for snapshot in (original, current):
            path = base + '/revisions/' + snapshot['revisionId']
            metadata = api.request('GET', path)
            report = api.request('GET', path + '/quality')
            require(report['versionId'] == original['id'] and
                    report['revisionId'] == snapshot['revisionId'] and report['entryFile'] == 'index.html' and
                    report['manifestSha256'] == metadata['manifestSha256'],
                    'Quality report changed its fixed revision or manifest identity')
            summary = report['summary']
            require(report['complete'] is True and report['findings'] == [] and report['limits'] == limits,
                    'Quality report marked the original complete fixture incomplete or changed its bounds')
            require(summary['files'] == 5 and summary['scannedDocuments'] == 4 and
                    0 < summary['scannedBytes'] <= limits['maxTotalTextBytes'] and
                    summary['checkedReferences'] == 9 and
                    all(summary[name] == 0 for name in ('errors', 'warnings', 'findingsTotal',
                                                      'omittedFindings', 'skippedDocuments')) and
                    summary['uncheckedReferences'] == dict(external=0, rootAbsolute=0, fragment=0,
                                                           unsupportedScheme=0),
                    'Quality report omitted static fixture files or references, or reported false findings')
            require(not any(name in report for name in ('storagePath', 'manifestJson', 'previewUrl', 'accessKey')),
                    'Quality report exposed internal storage or authorization details')
            require(api.request('GET', path + '/quality') == report,
                    'Repeated static quality report changed without a fixture change')
        require(api.request('GET', base + '/current-revision')['id'] == current['revisionId'],
                'Read-only quality report changed the current pointer')
        api.request('GET', base + '/revisions/' + uuid.uuid4().hex + '/quality', expected=404)
        if foreign_revision:
            api.request('GET', base + '/revisions/' + foreign_revision + '/quality', expected=404)
        Api(api.origin, self.secrets_seen).request(
            'GET', base + '/revisions/' + original['revisionId'] + '/quality', expected=401)
        self.report['revisionQuality'] = {
            'fixedHistoricalAndCurrentVerified': True, 'repeatableBoundedReportVerified': True,
            'fixtureFiles': 5, 'scannedDocuments': 4, 'checkedReferences': 9,
            'missingRevisionAndAnonymousRejected': True,
            'foreignRevisionRejected': bool(foreign_revision) or
                self.report.get('revisionQuality', {}).get('foreignRevisionRejected', False)}

    def verify_revision_comparison(self, api, original, current, original_hashes, current_hashes,
                                   foreign_revision=None):
        """Compare persisted snapshots against this run's independently read preview bytes."""
        base = '/api/prototype-hub/versions/' + original['id']
        pair = {'leftRevisionId': original['revisionId'], 'rightRevisionId': current['revisionId']}

        def url(**options):
            return base + '/revisions/_compare?' + urllib.parse.urlencode(dict(pair, **options))

        summary = {'pages': {'added': 0, 'removed': 0, 'changed': 2, 'unchanged': 1},
                   'files': {'added': 0, 'removed': 0, 'changed': 2, 'unchanged': 3}}
        complete = api.request('GET', url(includeUnchanged='true'))
        filtered = api.request('GET', url())
        expected_paths = {'pages': ['index.html', 'nested/child.html', 'nested/detail.html'],
                          'files': ['assets/icon.svg', 'assets/style.css', 'index.html',
                                    'nested/child.html', 'nested/detail.html']}
        changed = ['index.html', 'nested/child.html']
        names = {'index.html': 'Runtime fixture home',
                 'nested/child.html': 'Runtime fixture nested page',
                 'nested/detail.html': 'Runtime fixture detail'}
        byte_keys = {'index.html': 'entry', 'nested/child.html': 'child', 'assets/style.css': 'css'}
        for result in (complete, filtered):
            require(result['versionId'] == original['id'] and
                    result['left']['id'] == original['revisionId'] and
                    result['right']['id'] == current['revisionId'], 'Comparison changed its explicit revision pair')
            require(result['summary'] == summary, 'Comparison complete counts differ from the original fixture')
        for kind, paths in expected_paths.items():
            rows = complete[kind]['data']
            require(complete[kind]['total'] == len(paths) and
                    [row['path'] for row in rows] == paths, 'Comparison omitted or reordered fixture ' + kind)
            require(filtered[kind]['total'] == len(changed) and
                    filtered[kind]['data'] == [row for row in rows if row['path'] in changed],
                    'Comparison unchanged filtering changed rows or total')
            for row in rows:
                path = row['path']
                require(row['status'] == ('changed' if path in changed else 'unchanged'),
                        'Comparison misclassified fixture ' + kind)
                for side, hashes in (('left', original_hashes), ('right', current_hashes)):
                    value = row[side]
                    if kind == 'pages':
                        expected_name = names[path] + (' revision two' if side == 'right' and path in changed else '')
                        require(value['path'] == path and value['name'] == expected_name and
                                value['pageId'] == hashlib.sha256(path.encode()).hexdigest(),
                                'Comparison changed saved page identity or primary name')
                    else:
                        require(type(value['size']) is int and value['size'] > 0 and
                                isinstance(value['mediaType'], str) and value['mediaType'] and
                                re.fullmatch('[0-9a-f]{64}', value['sha256']) is not None,
                                'Comparison returned invalid file metadata')
                        if path in byte_keys:
                            require(value['sha256'] == hashes[byte_keys[path]],
                                    'Comparison file SHA differs from independently served bytes')
            for index in (0, 1, len(paths)):
                # Vary only one pager: the other must keep its complete, independent page.
                page = api.request('GET', url(includeUnchanged='true',
                                             **{kind + 'PageIndex': index, kind + 'PageSize': 1}))
                require(page['summary'] == summary and page[kind]['total'] == len(paths) and
                        page[kind]['pageIndex'] == index and page[kind]['pageSize'] == 1 and
                        page[kind]['data'] == rows[index:index + 1], 'Comparison pagination changed rows or counts')
                other = 'files' if kind == 'pages' else 'pages'
                require(page[other] == complete[other], 'Comparison coupled independent page and file pagers')
        same = api.request('GET', url(rightRevisionId=original['revisionId']))
        require(same['left']['id'] == same['right']['id'] == original['revisionId'] and
                same['summary'] == {kind: {'added': 0, 'removed': 0, 'changed': 0, 'unchanged': len(paths)}
                                    for kind, paths in expected_paths.items()} and
                all(same[kind]['total'] == 0 and same[kind]['data'] == [] for kind in expected_paths),
                'Comparison silently replaced an explicit historical revision with current')
        require(api.request('GET', base + '/current-revision')['id'] == current['revisionId'],
                'Read-only comparison changed the current revision pointer')
        api.request('GET', url(rightRevisionId=uuid.uuid4().hex), expected=404)
        if foreign_revision:
            api.request('GET', url(rightRevisionId=foreign_revision), expected=404)
        Api(api.origin, self.secrets_seen).request('GET', url(), expected=401)
        self.report['revisionComparison'] = {
            'summary': summary, 'explicitHistoricalPairVerified': True,
            'sameRevisionDoesNotResolveCurrent': True, 'independentPaginationVerified': True,
            'filteredTotalsVerified': True, 'shaMatchesServedBytes': True,
            'missingRevisionAndAnonymousRejected': True,
            'foreignRevisionRejected': bool(foreign_revision) or
                self.report.get('revisionComparison', {}).get('foreignRevisionRejected', False)}

    def create_deleted_fixture(self, api, member, zip_path, replacement_zip, storage):
        """Exercise deletion through HTTP; keep its graph and files for joint restore."""
        occupied = api.usage()['projects']['used']
        before_files = storage_manifest(storage)
        project_key, version_key = uuid.uuid4().hex, uuid.uuid4().hex
        project_name = 'Runtime retained project'
        project = api.project(project_name, project_key)
        api.request('PUT', '/api/prototype-hub/projects/' + project['id'] + '/members/' + member['id'],
                    {'role': 'viewer'})
        version = api.upload(project['id'], zip_path, version_key)
        path = '/api/prototype-hub/versions/' + version['id']
        anonymous = Api(api.origin, self.secrets_seen)
        fixed = api.request('POST', path + '/shares', {'password': self.share_password})
        latest = api.request('POST', path + '/shares', {'revisionMode': 'latest'})
        fixed_path = '/api/prototype-hub/public/shares/' + fixed['token']
        fixed_grant = anonymous.request('POST', fixed_path + '/verify', {'password': self.share_password})
        latest_path = '/api/prototype-hub/public/shares/' + latest['token']
        latest_first = anonymous.request('GET', latest_path)
        viewer = Api(api.origin, self.secrets_seen)
        viewer.login(member['username'], self.member_password)
        first_session = viewer.request('POST', path + '/preview-session')
        current = api.replace(version, replacement_zip)
        latest_current = anonymous.request('GET', latest_path)
        current_session = api.request('POST', path + '/preview-session')
        require(fixed_grant['revisionId'] == latest_first['revisionId'] == version['revisionId']
                and latest_current['revisionId'] == current['revisionId']
                and current['revisionId'] != version['revisionId'],
                'Deleted fixture did not establish two immutable share snapshots')
        self.preview(api, fixed_grant)
        self.preview(api, latest_current)
        files = storage_manifest(storage)
        retained_files = {name: digest for name, digest in files.items() if name not in before_files}
        require(retained_files, 'Deleted fixture has no files before deletion')
        api.request('DELETE', path)
        files = storage_manifest(storage)
        require(all(files.get(name) == digest for name, digest in retained_files.items()),
                'Soft deletion changed retained fixture files')
        require(api.request('GET', '/api/prototype-hub/versions/_usage?projectId=' +
                            project['id'])['versions']['used'] == 0,
                'Soft-deleted version did not release logical occupancy')
        replacement_key = uuid.uuid4().hex
        replacement = api.upload(project['id'], zip_path, replacement_key)
        require(replacement['id'] != version['id'] and
                api.request('GET', '/api/prototype-hub/versions/_usage?projectId=' +
                            project['id'])['versions']['used'] == 1,
                'Fresh version creation did not reuse released occupancy with a new identity')
        files = storage_manifest(storage)
        replacement_files = {name: digest for name, digest in files.items()
                             if name not in before_files and name not in retained_files}
        require(replacement_files, 'Replacement fixture has no files before deletion')
        retained_files.update(replacement_files)
        api.request('DELETE', '/api/prototype-hub/versions/' + replacement['id'])
        api.request('DELETE', '/api/prototype-hub/projects/' + project['id'])
        require(api.usage()['projects']['used'] == occupied,
                'Project with only deleted versions did not release occupancy')
        empty_key = uuid.uuid4().hex
        empty = api.project(project_name, empty_key)
        require(empty['id'] != project['id'] and api.usage()['projects']['used'] == occupied + 1,
                'Fresh project did not reuse released occupancy with a new identity')
        api.request('DELETE', '/api/prototype-hub/projects/' + empty['id'])
        require(api.usage()['projects']['used'] == occupied,
                'Empty project soft deletion did not release occupancy')
        files = storage_manifest(storage)
        require(all(files.get(name) == digest for name, digest in retained_files.items()),
                'Soft deletion changed retained fixture files')
        fixture = {'project': project, 'projectName': project_name, 'projectKey': project_key,
                   'emptyProject': empty, 'emptyKey': empty_key, 'original': version,
                   'current': current, 'versionKey': version_key, 'replacement': replacement,
                   'replacementKey': replacement_key, 'member': member,
                   'shares': (fixed, latest), 'publicGrants': (fixed_grant, latest_first, latest_current),
                   'internalGrants': (first_session, current_session), 'files': retained_files}
        fixture['state'] = self.deleted_fixture_state('runtime_source', fixture)
        return fixture

    def deleted_fixture_state(self, database, fixture):
        # psql quoted variables bind API-generated IDs; these read-only queries only
        # inspect this run's disposable database. Reports receive hashes, never rows.
        statement = """
SELECT json_build_object(
 'projects',(SELECT json_build_object('count',count(*),'contentMd5',
  md5(coalesce(jsonb_agg(to_jsonb(p) ORDER BY id)::text,'[]'))) FROM p_project p
  WHERE id IN (:'project',:'empty_project') AND deleted_time IS NOT NULL),
 'versions',(SELECT json_build_object('count',count(*),'contentMd5',
  md5(coalesce(jsonb_agg(to_jsonb(v) ORDER BY id)::text,'[]'))) FROM p_version v
  WHERE id IN (:'version',:'replacement') AND project_id=:'project' AND deleted_time IS NOT NULL),
 'revisions',(SELECT json_build_object('count',count(*),'contentMd5',
  md5(coalesce(jsonb_agg(to_jsonb(r) ORDER BY id)::text,'[]'))) FROM p_revision r
  WHERE version_id IN (:'version',:'replacement') AND project_id=:'project'),
 'shares',(SELECT json_build_object('count',count(*),'contentMd5',
  md5(coalesce(jsonb_agg(to_jsonb(s) ORDER BY id)::text,'[]'))) FROM p_share s
  WHERE version_id=:'version' AND project_id=:'project' AND revoked=true AND enabled=false),
 'receipts',(SELECT json_build_object('count',count(*),'contentMd5',
  md5(coalesce(jsonb_agg(to_jsonb(c) ORDER BY id)::text,'[]'))) FROM p_creation_receipt c
  WHERE resource_id IN (:'project',:'empty_project',:'version',:'replacement')),
 'members',(SELECT json_build_object('count',count(*),'contentMd5',
  md5(coalesce(jsonb_agg(to_jsonb(m) ORDER BY id)::text,'[]'))) FROM p_project_member m
  WHERE project_id=:'project'),
 'sessions',(SELECT count(*) FROM p_preview_session WHERE project_id=:'project'),
 'invalidReferences',(SELECT count(*) FROM p_creation_receipt c
  LEFT JOIN p_revision r ON r.id=c.revision_id
  WHERE c.resource_id IN (:'version',:'replacement')
  AND (r.id IS NULL OR r.version_id<>c.resource_id OR r.project_id<>:'project')));
"""
        state = json.loads(self.sql(database, statement, variables={
            'project': fixture['project']['id'], 'empty_project': fixture['emptyProject']['id'],
            'version': fixture['original']['id'], 'replacement': fixture['replacement']['id']}))
        for table, count in (('projects', 2), ('versions', 2), ('revisions', 3),
                             ('shares', 2), ('receipts', 4), ('members', 1)):
            require(state[table]['count'] == count, 'Deleted fixture lost retained ' + table)
        require(state['sessions'] == state['invalidReferences'] == 0,
                'Deleted fixture kept an internal grant or broke a receipt revision binding')
        return state

    def verify_deleted_fixture(self, api, fixture, zip_path, storage, database):
        project = fixture['project']['id']
        version = fixture['original']['id']
        path = '/api/prototype-hub/versions/' + version
        viewer = Api(api.origin, self.secrets_seen)
        viewer.login(fixture['member']['username'], self.member_password)
        for identity in (api, viewer):
            require(all(row['id'] not in (project, fixture['emptyProject']['id'])
                        for row in identity.request('GET', '/api/prototype-hub/projects')),
                    'Deleted project remained in an active list')
            require(all(row['id'] not in (version, fixture['replacement']['id'])
                        for row in identity.request('GET', '/api/prototype-hub/versions')),
                    'Deleted version remained in an active list')
            for target in ('/api/prototype-hub/projects/' + project,
                           '/api/prototype-hub/projects/' + fixture['emptyProject']['id'],
                           '/api/prototype-hub/projects/' + project + '/members', path,
                           path + '/pages', path + '/current-revision', path + '/revisions',
                           path + '/shares', path + '/revisions/_compare?' + urllib.parse.urlencode({
                               'leftRevisionId': fixture['original']['revisionId'],
                               'rightRevisionId': fixture['current']['revisionId']})):
                identity.request('GET', target, expected=404)
            identity.request('POST', path + '/preview-session', expected=404)
            for revision in (fixture['original']['revisionId'], fixture['current']['revisionId']):
                revision_path = path + '/revisions/' + revision
                identity.request('GET', revision_path, expected=404)
                identity.request('GET', revision_path + '/pages', expected=404)
                identity.request('GET', revision_path + '/quality', expected=404)
                identity.request('POST', revision_path + '/preview-session', expected=404)
        anonymous = Api(api.origin, self.secrets_seen)
        for share in fixture['shares']:
            public_path = '/api/prototype-hub/public/shares/' + share['token']
            anonymous.request('GET', public_path, expected=404)
            anonymous.request('POST', public_path + '/verify',
                              {'password': self.share_password}, expected=404)
        for grant in fixture['publicGrants']:
            for target in (grant['previewUrl'], grant['pagesUrl'],
                           grant['runtimeBaseUrl'] + '/assets/style.css'):
                anonymous.request('GET', target, expected=404)
        for grant in fixture['internalGrants']:
            for target in (grant['previewUrl'], grant['runtimeBaseUrl'] + '/assets/style.css'):
                anonymous.request('GET', target, expected=403)
        for key in (fixture['projectKey'], fixture['emptyKey']):
            result = api.project(fixture['projectName'], key, expected=409)
            require(result.get('code') == 'PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT',
                    'Deleted project creation key did not remain a conflict')
        for key in (fixture['versionKey'], fixture['replacementKey']):
            result = api.upload(project, zip_path, key, expected=409)
            require(result.get('code') == 'PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT',
                    'Deleted version creation key did not remain a conflict')
        require(self.deleted_fixture_state(database, fixture) == fixture['state'],
                'Deleted IDs, status, relationships or immutable content changed')
        files = storage_manifest(storage)
        require(all(files.get(path) == digest for path, digest in fixture['files'].items()),
                'Deleted fixture file identities or bytes changed')
        self.report['retainedDeletion'] = {
            'projectCount': 2, 'versionCount': 2, 'revisionCount': 3,
            'retainedFileCount': len(fixture['files']), 'stateAndReferenceHashesVerified': True,
            'quotaReleasedAndFreshIdsVerified': True, 'oldInternalAndPublicGrantsRejected': True,
            'freshManagementAndPublicEntrypointsRejected': True, 'creationReplaysConflict': True,
            'membershipLineageRetained': True, 'internalSessionsRemoved': True,
            'fileHashesVerified': True}

    def create_restored_fixture(self, api, zip_path, replacement_zip, storage):
        """Restore a separate graph; the deleted fixture remains a backup tombstone sample."""
        occupied = api.usage()['projects']['used']
        before_files = storage_manifest(storage)
        project_key, version_key = uuid.uuid4().hex, uuid.uuid4().hex
        name = 'Runtime recovered project'
        project = api.project(name, project_key)
        original = api.upload(project['id'], zip_path, version_key)
        current = api.replace(original, replacement_zip)
        original_hashes, current_hashes = self.preview(api, original), self.preview(api, current)
        path = '/api/prototype-hub/versions/' + original['id']
        old_internal = api.request('POST', path + '/preview-session')
        old_share = api.request('POST', path + '/shares', {'password': self.share_password})
        anonymous = Api(api.origin, self.secrets_seen)
        old_public = anonymous.request('POST', '/api/prototype-hub/public/shares/' +
                                       old_share['token'] + '/verify', {'password': self.share_password})
        files = {name: digest for name, digest in storage_manifest(storage).items()
                 if name not in before_files}
        require(files, 'Recovered fixture has no files before deletion')
        api.request('DELETE', path)
        api.request('DELETE', '/api/prototype-hub/projects/' + project['id'])
        require(api.usage()['projects']['used'] == occupied,
                'Recovered fixture deletion did not release project capacity')
        api.request('POST', path + '/_restore', expected=404)
        require(api.project(name, project_key, expected=409).get('code') ==
                'PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT', 'Deleted recovery project replay was accepted')
        require(api.upload(project['id'], zip_path, version_key, expected=409).get('code') ==
                'PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT', 'Deleted recovery version replay was accepted')
        restored = api.request('POST', '/api/prototype-hub/projects/' + project['id'] + '/_restore')
        require(restored['id'] == project['id'] and restored.get('deletedTime') is None,
                'Project recovery replaced the identity or kept its tombstone')
        api.request('GET', path, expected=404)
        deleted = api.request('POST', '/api/prototype-hub/versions/_deleted/_query?projectId=' +
                              project['id'], {'pageSize': 100})
        require(any(row['id'] == original['id'] and row.get('deletedTime') is not None
                    for row in deleted['data']), 'Parent recovery also restored a deleted child')
        restored_version = api.request('POST', path + '/_restore')
        require(restored_version['id'] == original['id'] and restored_version.get('deletedTime') is None
                and restored_version['currentRevisionId'] == current['revisionId'],
                'Version recovery replaced its identity or immutable current pointer')
        require(api.usage()['projects']['used'] == occupied + 1,
                'Recovery did not consume exactly one project slot')
        api.request('POST', '/api/prototype-hub/projects/' + project['id'] + '/_restore', expected=409)
        api.request('POST', path + '/_restore', expected=409)
        new_share = api.request('POST', path + '/shares', {'password': self.share_password})
        fixture = {'project': project, 'projectName': name, 'projectKey': project_key,
                   'original': original, 'current': current, 'versionKey': version_key,
                   'originalHashes': original_hashes, 'currentHashes': current_hashes,
                   'oldShare': old_share, 'oldPublic': old_public, 'oldInternal': old_internal,
                   'newShare': new_share, 'files': files}
        fixture['state'] = self.restored_fixture_state('runtime_source', fixture)
        return fixture

    def restored_fixture_state(self, database, fixture):
        # The snapshot excludes short-lived sessions: new previews may legitimately add them.
        statement = """
SELECT json_build_object(
 'projects',(SELECT json_build_object('count',count(*),'contentMd5',
  md5(coalesce(jsonb_agg(to_jsonb(p) ORDER BY id)::text,'[]'))) FROM p_project p
  WHERE id=:'project' AND deleted_time IS NULL),
 'versions',(SELECT json_build_object('count',count(*),'contentMd5',
  md5(coalesce(jsonb_agg(to_jsonb(v) ORDER BY id)::text,'[]'))) FROM p_version v
  WHERE id=:'version' AND project_id=:'project' AND deleted_time IS NULL),
 'revisions',(SELECT json_build_object('count',count(*),'contentMd5',
  md5(coalesce(jsonb_agg(to_jsonb(r) ORDER BY id)::text,'[]'))) FROM p_revision r
  WHERE version_id=:'version' AND project_id=:'project'),
 'oldShares',(SELECT json_build_object('count',count(*),'contentMd5',
  md5(coalesce(jsonb_agg(to_jsonb(s) ORDER BY id)::text,'[]'))) FROM p_share s
  WHERE id=:'old_share' AND project_id=:'project' AND version_id=:'version'
  AND revoked=true AND enabled=false),
 'newShares',(SELECT json_build_object('count',count(*),'contentMd5',
  md5(coalesce(jsonb_agg(to_jsonb(s) ORDER BY id)::text,'[]'))) FROM p_share s
  WHERE id=:'new_share' AND project_id=:'project' AND version_id=:'version'
  AND revoked=false AND enabled=true),
 'receipts',(SELECT json_build_object('count',count(*),'contentMd5',
  md5(coalesce(jsonb_agg(to_jsonb(c) ORDER BY id)::text,'[]'))) FROM p_creation_receipt c
  WHERE resource_id IN (:'project',:'version')),
 'invalidReferences',(SELECT count(*) FROM p_creation_receipt c
  LEFT JOIN p_revision r ON r.id=c.revision_id WHERE c.resource_id=:'version'
  AND (r.id IS NULL OR r.version_id<>c.resource_id OR r.project_id<>:'project')));
"""
        state = json.loads(self.sql(database, statement, variables={
            'project': fixture['project']['id'], 'version': fixture['original']['id'],
            'old_share': fixture['oldShare']['id'], 'new_share': fixture['newShare']['id']}))
        for table, count in (('projects', 1), ('versions', 1), ('revisions', 2),
                             ('oldShares', 1), ('newShares', 1), ('receipts', 2)):
            require(state[table]['count'] == count, 'Recovered fixture changed retained ' + table)
        require(state['invalidReferences'] == 0, 'Recovered fixture broke a receipt revision binding')
        return state

    def verify_restored_fixture(self, api, fixture, zip_path, storage, database, foreign_revision=None):
        project, original, current = fixture['project'], fixture['original'], fixture['current']
        path = '/api/prototype-hub/versions/' + original['id']
        project_usage = api.usage()['projects']['used']
        require(api.project(fixture['projectName'], fixture['projectKey'])['id'] == project['id'],
                'Recovered project creation replay changed its identity')
        self.verify_revisions(api, project, original, current, zip_path, fixture['versionKey'],
                              fixture['originalHashes'], fixture['currentHashes'], foreign_revision)
        require(api.usage()['projects']['used'] == project_usage,
                'Recovered creation replay consumed project capacity')
        anonymous = Api(api.origin, self.secrets_seen)
        old_share = '/api/prototype-hub/public/shares/' + fixture['oldShare']['token']
        anonymous.request('GET', old_share, expected=404)
        anonymous.request('POST', old_share + '/verify', {'password': self.share_password}, expected=404)
        for target in (fixture['oldPublic']['previewUrl'], fixture['oldPublic']['pagesUrl'],
                       fixture['oldPublic']['runtimeBaseUrl'] + '/assets/style.css'):
            anonymous.request('GET', target, expected=404)
        for target in (fixture['oldInternal']['previewUrl'],
                       fixture['oldInternal']['runtimeBaseUrl'] + '/assets/style.css'):
            anonymous.request('GET', target, expected=403)
        shares = api.request('GET', path + '/shares')
        old = next((share for share in shares if share['id'] == fixture['oldShare']['id']), {})
        require(old.get('revoked') is True and old.get('enabled') is False,
                'Recovery re-enabled a retained share')
        self.verify_share(api, fixture['newShare'], fixture['currentHashes'])
        require(self.restored_fixture_state(database, fixture) == fixture['state'],
                'Recovered identities, references or share policies changed')
        files = storage_manifest(storage)
        require(all(files.get(name) == digest for name, digest in fixture['files'].items()),
                'Recovered fixture file identities or bytes changed')
        self.report['recoveredDeletion'] = {
            'projectCount': 1, 'versionCount': 1, 'revisionCount': 2,
            'sameIdsAndCurrentPointerVerified': True, 'parentAndChildRestoredSeparately': True,
            'oldInternalAndPublicGrantsRejected': True, 'newShareAndInternalPreviewVerified': True,
            'originalCreationReplaysVerified': True, 'quotaReappliedWithoutReplayIncrease': True,
            'stateAndReferenceHashesVerified': True, 'fileHashesVerified': True,
            'retainedFileCount': len(fixture['files'])}

    def expire_fixture(self, database, fixture):
        # Only this run's stopped, disposable database is aged; no production clock override.
        require(not self.processes, 'Cannot age a fixture while an owned writer is running')
        self.sql(database, """
BEGIN;
UPDATE p_version SET deleted_time=:'deleted'::bigint
 WHERE id IN (:'version',:'replacement') AND deleted_time IS NOT NULL;
UPDATE p_project SET deleted_time=:'deleted'::bigint
 WHERE id IN (:'project',:'empty_project') AND deleted_time IS NOT NULL;
COMMIT;
""", variables={'deleted': str(int(time.time() * 1000) - RETENTION_MILLISECONDS - 60000),
                'project': fixture['project']['id'], 'empty_project': fixture['emptyProject']['id'],
                'version': fixture['original']['id'], 'replacement': fixture['replacement']['id']})

    def expired_fixture_counts(self, database, fixture):
        statement = """
SELECT json_build_object(
 'projects',(SELECT count(*) FROM p_project WHERE id IN (:'project',:'empty_project')),
 'versions',(SELECT count(*) FROM p_version WHERE project_id IN (:'project',:'empty_project')
  OR id IN (:'version',:'replacement')),
 'revisions',(SELECT count(*) FROM p_revision WHERE project_id IN (:'project',:'empty_project')
  OR version_id IN (:'version',:'replacement')),
 'shares',(SELECT count(*) FROM p_share WHERE project_id IN (:'project',:'empty_project')
  OR version_id IN (:'version',:'replacement')),
 'receipts',(SELECT count(*) FROM p_creation_receipt
  WHERE resource_id IN (:'project',:'empty_project',:'version',:'replacement')),
 'members',(SELECT count(*) FROM p_project_member WHERE project_id IN (:'project',:'empty_project')),
 'sessions',(SELECT count(*) FROM p_preview_session WHERE project_id IN (:'project',:'empty_project')
  OR version_id IN (:'version',:'replacement')),
 'cleanup',(SELECT count(*) FROM p_file_cleanup WHERE project_id IN (:'project',:'empty_project')));
"""
        counts = json.loads(self.sql(database, statement, variables={
            'project': fixture['project']['id'], 'empty_project': fixture['emptyProject']['id'],
            'version': fixture['original']['id'], 'replacement': fixture['replacement']['id']}))
        require(set(counts) == {'projects', 'versions', 'revisions', 'shares', 'receipts',
                                'members', 'sessions', 'cleanup'} and
                all(type(value) is int and value >= 0 for value in counts.values()),
                'Expired fixture graph counts are incomplete or invalid')
        return counts

    def seed_pending_cleanup(self, database, storage, fixture):
        # A maintenance fixture models an already committed outbox task, without inducing
        # filesystem permission failures that differ between root and ordinary users.
        require(not self.processes, 'Cannot seed pending cleanup while an owned writer is running')
        project, storage_id, task_id = fixture['project']['id'], uuid.uuid4().hex, uuid.uuid4().hex
        relative = 'projects/' + project + '/versions/' + storage_id + '/site'
        directory = storage / relative
        require(not directory.parent.exists(), 'Pending cleanup would overwrite existing storage')
        directory.mkdir(parents=True)
        (directory / 'index.html').write_bytes(b'Owned pending cleanup fixture')
        (directory.parent / 'original.zip').write_bytes(b'Owned pending cleanup source')
        files = {path.relative_to(storage).as_posix(): digest_file(path)
                 for path in (directory / 'index.html', directory.parent / 'original.zip')}
        self.sql(database, """
INSERT INTO p_file_cleanup(id,resource_type,project_id,version_id,storage_path,create_time,next_attempt,attempts)
 VALUES(:'task','expired-snapshot',:'project',:'version',:'path',:'created'::bigint,0,0);
""", variables={'task': task_id, 'project': project, 'version': fixture['original']['id'],
                'path': relative, 'created': str(int(time.time() * 1000))})
        return {'id': task_id, 'files': files}

    def verify_cleanup(self, database, storage, before, pending, *, expired=None):
        removed = dict(pending['files'])
        if expired:
            removed.update(expired['files'])
        require(removed and all(before.get(name) == digest for name, digest in removed.items()),
                'Cleanup fixture is not present unchanged in the source snapshot')
        expected = {name: digest for name, digest in before.items() if name not in removed}
        deadline = time.monotonic() + 60
        while True:
            pending_count = int(self.sql(database, "SELECT count(*) FROM p_file_cleanup WHERE id=:'task';",
                                         variables={'task': pending['id']}))
            graph = self.expired_fixture_counts(database, expired) if expired else {}
            files = storage_manifest(storage)
            if pending_count == 0 and not any(graph.values()) and not any(name in files for name in removed):
                break
            require(time.monotonic() < deadline, 'Expired graph or durable file cleanup did not drain')
            time.sleep(0.25)
        require(files == expected, 'Cleanup changed files outside the exact owned fixture')
        return expected

    def verify_expired_access(self, api, fixture):
        for project in (fixture['project'], fixture['emptyProject']):
            api.request('POST', '/api/prototype-hub/projects/' + project['id'] + '/_restore', expected=404)
        for version in (fixture['original'], fixture['replacement']):
            api.request('POST', '/api/prototype-hub/versions/' + version['id'] + '/_restore', expected=404)
        for path, ids in (('/projects', {fixture['project']['id'], fixture['emptyProject']['id']}),
                          ('/versions', {fixture['original']['id'], fixture['replacement']['id']})):
            page = api.request('POST', '/api/prototype-hub' + path + '/_deleted/_query', {'pageSize': 100})
            require(not any(row['id'] in ids for row in page['data']),
                    'Expired resource remained in the recycle bin')
        anonymous = Api(api.origin, self.secrets_seen)
        for share in fixture['shares']:
            anonymous.request('GET', '/api/prototype-hub/public/shares/' + share['token'], expected=404)
        for grant in fixture['publicGrants']:
            anonymous.request('GET', grant['previewUrl'], expected=404, raw=True)
        for grant in fixture['internalGrants']:
            anonymous.request('GET', grant['previewUrl'], expected=403, raw=True)
        # The old receipt is gone, so this is a new authorized creation, never restoration.
        recreated = api.project(fixture['projectName'], fixture['projectKey'])
        require(recreated['id'] != fixture['project']['id'], 'Permanent purge revived the old project ID')
        api.request('DELETE', '/api/prototype-hub/projects/' + recreated['id'])

    def create_system_files(self, api):
        profile = api.request('GET', '/api/user/detail')
        expected = {'userId': profile['id']}
        for label, path, color in (
                ('branding', '/api/file/upload', (35, 88, 140, 255)),
                ('avatar', '/api/user/me/avatar', (132, 60, 91, 255))):
            content = sample_png(color)
            body, content_type = file_multipart('runtime-' + label + '.png', content, 'image/png')
            uploaded = api.request('POST', path, body, headers={'Content-Type': content_type})
            expected[label] = {'url': system_file_url(uploaded['url']), 'content': content}
        api.request('POST', '/api/system/config/front', {'logo': expected['branding']['url']})
        saved = api.request('PUT', '/api/user/detail',
                            {'name': profile['name'], 'avatar': expected['avatar']['url']})
        require(saved is True, 'Current-user avatar was not saved')
        # Upload success alone cannot prove the database still points at the recovered bytes.
        self.verify_system_files(api, expected)
        return expected

    def verify_system_files(self, api, expected):
        profile = api.request('GET', '/api/user/detail')
        branding = api.request('GET', '/api/system/config/front')
        require(profile['id'] == expected['userId'] and
                profile.get('avatar') == expected['avatar']['url'],
                'Current-user avatar reference changed')
        require(branding.get('logo') == expected['branding']['url'],
                'Branding image reference changed')
        anonymous = Api(api.origin, self.secrets_seen)
        hashes = {}
        for label in ('branding', 'avatar'):
            image = expected[label]
            content, headers = anonymous.request('GET', system_file_url(image['url']), raw=True)
            require(content == image['content'], 'System image bytes differ from the uploaded sample')
            require(headers.get('Content-Type', '').split(';')[0] == 'image/png' and
                    headers.get('X-Content-Type-Options') == 'nosniff',
                    'System image response headers changed')
            hashes[label] = hashlib.sha256(content).hexdigest()
        return hashes

    def create_current_graph(self, api, project, version, current, member, review):
        """Establish durable current-version rows through the existing product APIs."""
        fixture = {'project': project, 'version': version, 'current': current,
                   'member': member, 'review': review}
        discussion = review['discussion']
        base = discussion['base']
        path = '/api/prototype-hub/versions/' + version['id'] + '/work-items'
        payload = dict(base, url='https://example.invalid/restore-work-item',
                       note='Original private work item', clientRequestId=uuid.uuid4().hex)
        fixture['workItem'] = {'path': path, 'payload': payload,
                               'row': api.request('POST', path, payload)}
        owner = self.sql('runtime_source', "select owner_id from p_project where id=:'project';",
                         variables={'project': project['id']})
        path = discussion['memberPath'] + '/' + discussion['thread']['id'] + '/assignment'
        payload = dict(base, assigneeId=owner, expectedVersion=0, clientRequestId=uuid.uuid4().hex)
        fixture['assignment'] = {'path': path, 'payload': payload,
                                 'row': api.request('PUT', path, payload)}
        require(fixture['assignment']['row']['assignee']['userId'] == owner,
                'Current fixture has no actual assignment')
        discussion['assignment'] = fixture['assignment']
        discussion.pop('receiptHash', None)

        path = '/api/prototype-hub/projects/' + project['id'] + '/review-associations'
        target = review['invitations']['consumed']['identity']
        context, cookie = self.review_call(api, review['grant'], 'context', {})
        context, cookie = self.review_call(api, review['grant'], 'unverified',
            {'displayName': 'Expiring association source'}, cookie=cookie, csrf=context['csrfToken'])
        source_guest = context['session']['guestId']
        source, _ = self.review_call(api, review['grant'], 'threads',
            {'pageId': base['pageId'], 'text': 'Original revoked association source',
             'clientRequestId': uuid.uuid4().hex}, cookie=cookie, csrf=context['csrfToken'])
        operations = []
        def associate(thread):
            context = api.request('POST', path + '/_detail', {'sourceThreadId': thread['id']})
            payload = {'sourceThreadId': thread['id'], 'targetGuestId': target['guestId'],
                       'expectedShareVersion': context['shareConfigVersion'],
                       'expectedTargetVersion': 0, 'evidenceReference': 'Original local verification record',
                       'clientRequestId': uuid.uuid4().hex}
            row = api.request('POST', path, payload)
            operations.append(('POST', path, payload, row['id']))
            return row
        revoked = associate(source)
        payload = {'expectedVersion': revoked['configVersion'], 'reason': 'Original explicit revocation',
                   'clientRequestId': uuid.uuid4().hex}
        revoked = api.request('PUT', path + '/' + revoked['id'] + '/revoke', payload)
        operations.append(('PUT', path + '/' + revoked['id'] + '/revoke', payload, revoked['id']))
        active = associate(discussion['thread'])
        require(active['status'] == 'active' and revoked['status'] == 'revoked',
                'Current fixture did not establish both association states')
        fixture['associations'] = {'path': path, 'target': target, 'active': active, 'revoked': revoked,
                                   'operations': operations, 'expiredSourceGuestId': source_guest,
                                   'expiredSourceThread': source}

        copy_project = api.project('Runtime copy restore project')
        archive = self.workspace / 'current-copy.zip'
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as package:
            package.writestr('index.html', '<!doctype html><html><body><p>Original copy fixture</p></body></html>')
            package.writestr('empty.html', '<!doctype html><html><body><script>dynamicOnly()</script></body></html>')
            package.writestr('limit.html', '<!doctype html><html><body>' +
                             ''.join('<p>Budget text ' + str(i) + '</p>' for i in range(2001)) + '</body></html>')
        copy_version = api.upload(copy_project['id'], archive, uuid.uuid4().hex)
        fixture.update(copyProject=copy_project, copyVersion=copy_version)
        self.report['sample']['copyZipSha256'] = digest_file(archive)
        fixture['copy'] = []
        for item, page_path, state in ((version, 'index.html', 'READY'),
                                       (copy_version, 'empty.html', 'EMPTY'),
                                       (copy_version, 'limit.html', 'LIMIT_EXCEEDED')):
            page_id = hashlib.sha256(page_path.encode()).hexdigest()
            path = ('/api/prototype-hub/versions/' + item['id'] + '/revisions/' + item['revisionId'] +
                    '/pages/' + page_id + '/copy-inventory')
            extract = {'clientRequestId': uuid.uuid4().hex}
            api.request('POST', path + '/_extract', extract, expected=422 if state == 'LIMIT_EXCEEDED' else 200)
            context = api.request('GET', path)
            require(context['extractionState'] == state, 'Current copy extraction state differs')
            payload = {'text': '=Original manual copy\nSecond line', 'businessTerm': 'Original business term',
                       'note': 'Original manual note', 'requirementUrl': 'https://example.invalid/requirement',
                       'clientRequestId': uuid.uuid4().hex}
            saved = api.request('POST', path + '/entries', payload)
            operations = [('POST', path + '/entries', payload, saved['entry']['id'])]
            page = api.request('POST', path + '/entries/_query', {'pageSize': 100})
            static = next((row for row in page['data'] if row['kind'] == 'STATIC'), None)
            if state == 'READY':
                require(static is not None, 'Current copy fixture has no static text')
                update = {'businessTerm': 'Literal %_ term', 'note': 'Original static annotation',
                          'expectedVersion': static['configVersion'], 'clientRequestId': uuid.uuid4().hex}
                saved = api.request('PUT', path + '/entries/' + static['id'], update)
                operations.append(('PUT', path + '/entries/' + static['id'], update, saved['entry']['id']))
            else:
                require(static is None and context['staticCount'] == 0,
                        'Empty or limited extraction persisted a partial static set')
            fixture['copy'].append({'path': path, 'extract': extract, 'state': state,
                                     'context': api.request('GET', path),
                                     'entries': api.request('POST', path + '/entries/_query', {'pageSize': 100}),
                                     'operations': operations})

        grant = review['grant']
        tickets = []
        for _ in range(3):
            fresh = Api(api.origin, self.secrets_seen).request(
                'GET', '/api/prototype-hub/public/shares/' + grant['token'])
            tickets.append(fresh['visitTicket'])
        visit_path = ('/api/prototype-hub/public/shares/' + grant['token'] + '/' + grant['accessKey'] +
                      '/revisions/' + grant['revisionId'] + '/visits')
        headers = {'Origin': ('http://127.0.0.1:' + str(self.ui_port)) if self.ui_port else api.origin}
        anonymous = Api(api.origin, self.secrets_seen)
        for ticket in tickets:
            anonymous.request('POST', visit_path, {'ticket': ticket}, headers=headers)
        rows = self.sql('runtime_source', 'select id from p_share_visit order by received_at,id;').splitlines()
        require(len(rows) == 3, 'Current fixture did not record exactly three initial visits')
        fixture['visits'] = {'path': visit_path, 'grant': grant, 'recentTicket': tickets[2],
                              'oldId': rows[0], 'oldRestoreId': rows[1], 'recentId': rows[2]}
        self.current_restore_fixture = fixture
        return fixture

    def verify_current_graph(self, api, fixture, database):
        """Recheck the same persisted fixture on source, restart, replica and restored roots."""
        work = fixture['workItem']
        require(api.request('POST', work['path'], work['payload']) == work['row'],
                'Work item receipt replay changed identity or metadata')
        require(api.request('POST', work['path'] + '/_query', fixture['review']['discussion']['base'])['data'] ==
                [work['row']], 'Restored work item lost its fixed page scope')
        assigned = fixture['assignment']
        require(api.request('PUT', assigned['path'], assigned['payload']) == assigned['row'],
                'Assignment receipt replay changed its independent version or owner')
        association = fixture['associations']
        for method, path, payload, identity in association['operations']:
            row = api.request(method, path, payload)
            original = association['active'] if identity == association['active']['id'] else association['revoked']
            require(row == original, 'Association receipt replay rewrote an active or revoked audit')
        target = association['target']
        context = self.verify_review_identity(api, target)
        base = {'pageId': fixture['review']['discussion']['base']['pageId']}
        visible, _ = self.review_call(api, target['grant'], 'threads/_query', base,
                                     cookie=target['cookie'], csrf=context['csrfToken'])
        ids = {row['id'] for row in visible['data']}
        require(association['active']['sourceThreadId'] in ids and
                association['revoked']['sourceThreadId'] not in ids,
                'Restored association granted a revoked source or hid the active source')
        self.review_call(api, target['grant'], 'threads/' + association['revoked']['sourceThreadId'] +
                         '/messages/_query', base, cookie=target['cookie'], csrf=context['csrfToken'], expected=404)
        for item in fixture['copy']:
            path = item['path']
            require(api.request('GET', path) == item['context'] and
                    api.request('POST', path + '/entries/_query', {'pageSize': 100}) == item['entries'],
                    'Restored copy snapshot, manual data, occurrence set or CAS differs')
            api.request('POST', path + '/_extract', item['extract'],
                        expected=422 if item['state'] == 'LIMIT_EXCEEDED' else 200)
            for method, endpoint, payload, identity in item['operations']:
                saved = api.request(method, endpoint, payload)
                require(saved['entry']['id'] == identity, 'Copy receipt replay created a new entry')
            require(api.request('GET', path) == item['context'], 'Copy replay advanced its inventory version')
            exported, _ = api.request('POST', path + '/_export',
                {'format': 'json', 'expectedVersion': item['context']['configVersion']}, raw=True)
            require(json.loads(exported)['entries'] == item['entries']['data'],
                    'Restored full copy export changed its stored text or source set')
        viewer = Api(api.origin, self.secrets_seen)
        viewer.login(fixture['member']['username'], self.member_password)
        copy = fixture['copy'][0]
        require(viewer.request('GET', copy['path']) == copy['context'], 'Restored READ member cannot read copy')
        viewer.request('POST', copy['path'] + '/_extract', copy['extract'], expected=403)
        Api(api.origin, self.secrets_seen).request('GET', copy['path'], expected=401)
        # A valid but foreign revision must never fall back to the logical current revision.
        foreign = copy['path'].replace('/revisions/' + fixture['version']['revisionId'] + '/',
                                       '/revisions/' + fixture['copyVersion']['revisionId'] + '/')
        api.request('GET', foreign, expected=404)
        visits = fixture['visits']
        before = self.sql(database, "select md5(coalesce(jsonb_agg(to_jsonb(v) order by id)::text,'[]')) "
                          'from p_share_visit v;')
        anonymous = Api(api.origin, self.secrets_seen)
        headers = {'Origin': ('http://127.0.0.1:' + str(self.ui_port)) if self.ui_port else api.origin}
        anonymous.request('POST', visits['path'], {'ticket': visits['recentTicket']}, headers=headers)
        require(self.sql(database, "select md5(coalesce(jsonb_agg(to_jsonb(v) order by id)::text,'[]')) "
                         'from p_share_visit v;') == before, 'A restored visit ticket was counted twice')
        stats = api.request('GET', '/api/prototype-hub/statistics?days=30')
        expected = int(self.sql(database, "select count(*) from p_share_visit v join p_project p on p.id=v.project_id "
                                "join p_version n on n.id=v.version_id where p.deleted_time is null "
                                "and n.deleted_time is null and v.visit_day between :'first' and :'last';",
                                variables={'first': stats['fromDate'], 'last': stats['toDate']}))
        require(stats['visits'] == expected and sum(row['visits'] or 0 for row in stats['trend']) == expected,
                'Restored statistics differ from current database events')
        self.report['currentGraph'] = {'businessTables': len(BUSINESS_TABLES),
            'nonemptyWorkItemAnchorAssignment': True, 'activeAndRevokedAssociationsPreserved': True,
            'fixedCopyStates': [item['state'] for item in fixture['copy']],
            'staticManualOccurrencesAndReceiptsPreserved': True, 'readWriteAndForeignScopeChecked': True,
            'recentVisitReplayDoesNotInsert': True, 'statisticsMatchDatabase': True}

    def expire_current_rows(self, database, fixture, *, restoring=False):
        require(not self.processes, 'Cannot age current fixture while an owned writer is running')
        cutoff = int(time.time() * 1000) - 60000
        old_day = (datetime.now(timezone(timedelta(hours=8))).date() - timedelta(days=35)).isoformat()
        retained_old_day = (datetime.now(timezone(timedelta(hours=8))).date() - timedelta(days=31)).isoformat()
        self.sql(database, """
UPDATE p_share_visit SET visit_day=:'old_day' WHERE id=:'visit';
UPDATE p_review_unverified_guest SET expires_at=:'cutoff'::bigint WHERE id=:'guest';
UPDATE p_review_session SET expires_at=:'cutoff'::bigint WHERE guest_id=:'guest';
UPDATE p_review_event SET create_time=:'event_time'::bigint WHERE thread_id=:'thread';
""", variables={'old_day': old_day, 'visit': fixture['visits']['oldRestoreId' if restoring else 'oldId'],
                    'cutoff': str(cutoff), 'guest': fixture['associations']['expiredSourceGuestId'],
                    'thread': fixture['associations']['revoked']['sourceThreadId'],
                    'event_time': str(cutoff - RETENTION_MILLISECONDS)})
        if not restoring:
            self.sql(database, "UPDATE p_share_visit SET visit_day=:'day' WHERE id=:'visit';",
                     variables={'day': retained_old_day, 'visit': fixture['visits']['oldRestoreId']})

    def sql_script(self, database, name, mutation='', variables=None):
        require(name in ('preflight-db.sql', 'snapshot-db.sql', 'export-storage-manifest.sql'),
                'Unknown verification SQL script')
        tools = Path(__file__).resolve().parent
        source = (tools / name).read_text().replace('\\ir prototype-hub-table-names.sql',
                                                   (tools / 'prototype-hub-table-names.sql').read_text())
        return self.sql(database, 'BEGIN;\n' + mutation + '\n' + source + '\nROLLBACK;', variables=variables)

    def verify_sql_diagnostics(self, database, fixture):
        require(not self.processes, 'SQL mutation checks require stopped owned writers')
        clean = json.loads(self.sql_script(database, 'export-storage-manifest.sql'))
        require(clean['integrityErrors'] == 0 and clean['referencesComplete'],
                'Valid current receipt or resource scope was rejected by storage diagnostics')
        require('current_scope_errors|0' in self.sql_script(database, 'preflight-db.sql').splitlines(),
                'Valid thread, message or association receipt was rejected by preflight')
        before = self.sql_script(database, 'snapshot-db.sql')
        require(any(line.startswith('business_table_coverage|23|') for line in before.splitlines()),
                'Operational snapshot omitted current business tables')
        variables = {'association': fixture['associations']['active']['id'],
                     'foreign_project': fixture['copyProject']['id'],
                     'foreign_revision': fixture['copyVersion']['revisionId'],
                     'snapshot': fixture['copy'][0]['context']['snapshotId'],
                     'empty_snapshot': fixture['copy'][1]['context']['snapshotId'],
                     'entry': fixture['copy'][0]['entries']['data'][0]['id'],
                     'recent': fixture['visits']['recentId'],
                     'target': fixture['associations']['active']['targetGuestId'],
                     'thread': fixture['review']['discussion']['thread']['id'],
                     'message': next(operation['id'] for operation in fixture['review']['discussion']['operations']
                                     if operation['method'] == 'POST' and operation['path'].endswith('/messages'))}
        valid_invalidation = "UPDATE p_review_guest SET enabled=false,config_version=coalesce(config_version,0)+1 WHERE id=:'target';"
        require(json.loads(self.sql_script(database, 'export-storage-manifest.sql', valid_invalidation, variables))[
                'integrityErrors'] == 0 and 'current_scope_errors|0' in self.sql_script(
                    database, 'preflight-db.sql', valid_invalidation, variables).splitlines(),
                'Diagnostics confused legitimate audit invalidation with broken ownership')
        mutations = (
            "UPDATE p_review_association SET target_guest_id='missing-fixture-guest' WHERE id=:'association';",
            "UPDATE p_review_guest SET project_id=:'foreign_project' WHERE id=:'target';",
            "UPDATE p_creation_receipt SET revision_id=:'foreign_revision' WHERE resource_id=:'thread';",
            "UPDATE p_creation_receipt SET revision_id=:'foreign_revision' WHERE resource_id=:'message';",
            "UPDATE p_review_association SET project_id=:'foreign_project' WHERE id=:'association';",
            "UPDATE p_review_association SET revision_id=:'foreign_revision' WHERE id=:'association';",
            "UPDATE p_creation_receipt SET revision_id=:'foreign_revision' WHERE resource_id=:'association';",
            "UPDATE p_share_visit SET project_id=:'foreign_project' WHERE id=:'recent';",
            "UPDATE p_share_visit SET revision_id=:'foreign_revision' WHERE id=:'recent';",
            "UPDATE p_copy_snapshot SET file_sha256=repeat('f',64) WHERE id=:'snapshot';",
            "UPDATE p_copy_snapshot SET page_path='missing.html',page_id=encode(sha256(convert_to('missing.html','UTF8')),'hex') WHERE id=:'empty_snapshot'; UPDATE p_copy_entry SET page_id=encode(sha256(convert_to('missing.html','UTF8')),'hex') WHERE snapshot_id=:'empty_snapshot';",
            "UPDATE p_copy_snapshot SET revision_id=:'foreign_revision' WHERE id=:'snapshot';",
            "UPDATE p_copy_entry SET page_id=repeat('f',64) WHERE id=:'entry';",
            "UPDATE p_copy_entry SET project_id=:'foreign_project' WHERE id=:'entry';")
        for index, mutation in enumerate(mutations, 1):
            case = 'SQL negative case ' + str(index)
            require(self.sql_script(database, 'snapshot-db.sql', mutation, variables) != before,
                    case + ' did not change a persisted fixture row')
            require(json.loads(self.sql_script(database, 'export-storage-manifest.sql', mutation, variables))[
                    'integrityErrors'] > 0, case + ': storage diagnostics accepted a cross-scope mutation')
            lines = self.sql_script(database, 'preflight-db.sql', mutation, variables).splitlines()
            require(any(line.startswith('current_scope_errors|') and line != 'current_scope_errors|0'
                        for line in lines), case + ': preflight accepted a cross-scope mutation')
        changed = self.sql_script(database, 'snapshot-db.sql',
            "UPDATE p_creation_receipt SET fingerprint=repeat('0',64) WHERE resource_id=:'association';", variables)
        require(changed != before, 'Operational snapshot omitted complete receipt content')
        require(self.sql_script(database, 'snapshot-db.sql') == before,
                'Diagnostic mutation escaped its rollback transaction')
        self.report['operationalSql'] = {'positiveCurrentGraph': True,
            'crossScopeNegativeCases': len(mutations), 'fullReceiptChangeDetected': True,
            'invalidatedAuditStillValidGraph': True, 'rolledBackWithoutChangingSource': True}

    def verify_restored_current_writes(self, api, fixture, database):
        """Exercise new associated writes only after the source/restored UI comparison."""
        association, discussion = fixture['associations'], fixture['review']['discussion']
        target = association['target']
        context = self.verify_review_identity(api, target)
        thread = discussion['thread']
        path = 'threads/' + thread['id'] + '/messages'
        payload = {'pageId': discussion['base']['pageId'], 'text': 'Original restored associated reply',
                   'clientRequestId': uuid.uuid4().hex}
        saved, _ = self.review_call(api, target['grant'], path, payload,
                                   cookie=target['cookie'], csrf=context['csrfToken'])
        require(saved['threadId'] == thread['id'] and saved['authorType'] == 'guest' and
                saved['verificationLevel'] == 'email' and saved['visibility'] == 'public' and
                saved['authorName'] == target['identity']['displayName'],
                'Restored associated reply lost its actual target author or public scope')
        replay, _ = self.review_call(api, target['grant'], path, payload,
                                    cookie=target['cookie'], csrf=context['csrfToken'])
        require(replay == saved, 'Restored associated reply replay created a second message')
        for grant, identity, status in ((target['grant'], association['revoked']['sourceThreadId'], 404),
                (dict(target['grant'], revisionId=fixture['copyVersion']['revisionId']), thread['id'], 403)):
            self.review_call(api, grant, 'threads/' + identity + '/messages',
                             dict(payload, clientRequestId=uuid.uuid4().hex), cookie=target['cookie'],
                             csrf=context['csrfToken'], expected=status)
        public, _ = self.review_call(api, target['grant'], path + '/_query',
            {'pageId': discussion['base']['pageId']}, cookie=target['cookie'], csrf=context['csrfToken'])
        expected = [row for row in discussion['messages'] if row['visibility'] == 'public'] + [saved]
        require(public['total'] == len(expected) and public['data'] == expected and
                all(row['visibility'] == 'public' for row in public['data']),
                'Associated reply disclosed an internal message or changed the original public history')
        rows = api.request('POST', discussion['memberPath'] + '/_query', discussion['base'])['data']
        current = next(row for row in rows if row['id'] == thread['id'])
        require({key: value for key, value in current.items() if key != 'updatedAt'} ==
                {key: value for key, value in thread.items() if key != 'updatedAt'},
                'Associated reply rewrote the original author, anchor or fixed thread identity')
        require(self.sql(database, "select count(*) from p_review_message where thread_id=:'thread';",
                         variables={'thread': thread['id']}) == str(len(discussion['messages']) + 1),
                'Associated write, denied scope or receipt replay inserted unexpected messages')
        self.report['restoredAssociatedWrite'] = {'targetAuthorPreserved': True,
            'originalAuthorAndAnchorPreserved': True, 'replayCreatedOneMessage': True,
            'revokedAndForeignRevisionDenied': True, 'internalMessagesRemainHidden': True}

    def verify_current_retention(self, database, fixture, before, allowed):
        deadline = time.monotonic() + 30
        while True:
            remaining = int(self.sql(database,
                "select (select count(*) from p_share_visit where id in (:'old',:'restore') "
                "and visit_day < :'cutoff') + (select count(*) from p_review_unverified_guest where id=:'guest');",
                variables={'old': fixture['visits']['oldId'], 'restore': fixture['visits']['oldRestoreId'],
                           'cutoff': (datetime.now(timezone(timedelta(hours=8))).date() - timedelta(days=34)).isoformat(),
                           'guest': fixture['associations']['expiredSourceGuestId']}))
            if remaining == 0:
                break
            require(time.monotonic() < deadline, 'Registered current expiry fixtures did not drain')
            time.sleep(0.25)
        compare_review_rows(before, self.review_rows(database), int(time.time() * 1000), allowed)
        require(self.sql(database, "select count(*) from p_share_visit where id=:'recent';",
                         variables={'recent': fixture['visits']['recentId']}) == '1',
                'Retention removed the current recent visit')

    def execute(self):
        self.ui_port = free_port() if self.args.frontend_dir else None
        self.mailbox = LocalMailbox(self.workspace / 'review-mailbox.json', self.secrets_seen)
        self.postgres()
        storage = self.workspace / 'source-storage'
        system_storage = self.workspace / 'source-system-files'
        zip_path = sample_zip(self.workspace / 'runtime-fixture.zip')
        replacement_zip = sample_zip(self.workspace / 'runtime-replacement.zip', replacement=True)
        self.report['sample']['sha256'] = digest_file(zip_path)
        project_key, version_key = uuid.uuid4().hex, uuid.uuid4().hex
        with self.phase('packaged-jar-empty-database'):
            api, process = self.start('runtime_source', storage, system_storage, 'initial')
            system_files = self.create_system_files(api)
            usage = api.usage()
            installation_id = usage['installationId']
            require(usage['projects']['used'] == 0 and usage['projects']['limit'] == 5,
                    'New database does not have default project capacity')
            project = api.project('Runtime restore project', project_key)
            version = api.upload(project['id'], zip_path, version_key)
            member = self.create_project_member(api, project)
            preview_hashes = self.preview(api, version)
            self.verify_project_member(api, member, project, version, preview_hashes)
            share = api.request('POST', '/api/prototype-hub/versions/' + version['id'] + '/shares',
                                {'password': self.share_password,
                                 'expiresAt': int(time.time() * 1000) + 3600000})
            self.verify_share(api, share, preview_hashes)
            latest_share = api.request('POST', '/api/prototype-hub/versions/' + version['id'] +
                                       '/shares', {'revisionMode': 'latest'})
            latest_first_grant = Api(api.origin, self.secrets_seen).request(
                'GET', '/api/prototype-hub/public/shares/' + latest_share['token'])
            require(self.preview(api, latest_first_grant) == preview_hashes,
                    'Initial latest share did not resolve the original snapshot')
            require(api.project('Runtime restore project', project_key)['id'] == project['id'],
                    'Project replay created a second resource')
            require(api.upload(project['id'], zip_path, version_key)['id'] == version['id'],
                    'Version replay created a second resource')
            current = api.replace(version, replacement_zip)
            current_hashes = self.preview(api, current)
            require(current_hashes != preview_hashes, 'Replacement fixture did not change the served bytes')
            manifest_hashes = self.verify_revisions(api, project, version, current, zip_path,
                                                   version_key, preview_hashes, current_hashes)
            self.verify_share(api, share, preview_hashes)
            retired_share, retired_grant = self.verify_share_lifecycle(
                api, latest_share, latest_first_grant, version, current, preview_hashes, current_hashes)
            following_share = api.request('POST', '/api/prototype-hub/versions/' + version['id'] +
                                          '/shares', {'revisionMode': 'latest'})
            review = self.create_review_identity(api, version)
            current_graph = self.create_current_graph(api, project, version, current, member, review)
            self.verify_current_graph(api, current_graph, 'runtime_source')
            deleted = self.create_deleted_fixture(api, member, zip_path, replacement_zip, storage)
            self.verify_deleted_fixture(api, deleted, zip_path, storage, 'runtime_source')
            recovered = self.create_restored_fixture(api, zip_path, replacement_zip, storage)
            self.verify_restored_fixture(api, recovered, zip_path, storage, 'runtime_source',
                                         version['revisionId'])
            expired = self.create_deleted_fixture(api, member, zip_path, replacement_zip, storage)
            review_state = self.review_rows('runtime_source')
        with self.phase('full-process-restart'):
            self.stop(process)
            self.expire_fixture('runtime_source', expired)
            self.expire_current_rows('runtime_source', current_graph)
            review_state = self.review_rows('runtime_source')
            allowed_expiry = registered_review_expiry(review_state, int(time.time() * 1000))
            pending_restart = self.seed_pending_cleanup('runtime_source', storage, expired)
            restart_files = storage_manifest(storage)
            api, process = self.start('runtime_source', storage, system_storage, 'restarted')
            self.verify_cleanup('runtime_source', storage, restart_files, pending_restart, expired=expired)
            self.verify_expired_access(api, expired)
            self.report['expiredRetention'] = {
                'retentionDays': 30, 'expiredProjectCount': 2, 'expiredVersionCount': 2,
                'expiredRevisionCount': 3, 'expiredFileCount': len(expired['files']),
                'graphAndFilesRemovedAfterRestart': True, 'expiredRecoveryAndOldGrantsRejected': True,
                'oldKeyCreatesNewIdentityAfterPermanentPurge': True,
                'pendingCleanupSurvivedRestart': True, 'pendingFileCount': len(pending_restart['files']),
                'unrelatedFileHashesUnchanged': True}
            self.verify_system_files(api, system_files)
            self.verify_deleted_fixture(api, deleted, zip_path, storage, 'runtime_source')
            self.verify_restored_fixture(api, recovered, zip_path, storage, 'runtime_source',
                                         version['revisionId'])
            require(api.usage()['installationId'] == installation_id,
                    'Installation identity changed on restart')
            require(api.project('Runtime restore project', project_key)['id'] == project['id'],
                    'Project receipt did not survive restart')
            require(api.upload(project['id'], zip_path, version_key)['id'] == version['id'],
                    'Version receipt did not survive restart')
            require(self.preview(api, version) == preview_hashes, 'Preview bytes changed on restart')
            self.verify_project_member(api, member, project, version, preview_hashes)
            self.verify_share(api, share, preview_hashes)
            self.verify_share_policy(api, retired_share, grant=retired_grant)
            self.verify_share_policy(api, following_share, expected_preview=current_hashes)
            self.verify_review_fixtures(api, review, 'runtime_source')
            current_review = self.review_rows('runtime_source')
            self.verify_current_retention('runtime_source', current_graph, review_state, allowed_expiry)
            self.verify_current_graph(api, current_graph, 'runtime_source')
            review_state = self.review_rows('runtime_source')
            require(self.verify_revisions(api, project, version, current, zip_path, version_key,
                                          preview_hashes, current_hashes) == manifest_hashes,
                    'Revision manifests changed on process restart')
        with self.phase('two-process-final-project-slot'):
            other, second_process = self.start('runtime_source', storage, system_storage, 'second-replica')
            self.verify_system_files(other, system_files)
            self.verify_deleted_fixture(other, deleted, zip_path, storage, 'runtime_source')
            self.verify_restored_fixture(other, recovered, zip_path, storage, 'runtime_source',
                                         version['revisionId'])
            require(other.usage()['installationId'] == installation_id,
                    'Replicas do not share installation identity')
            self.verify_review_fixtures(other, review, 'runtime_source')
            current_review = self.review_rows('runtime_source')
            compare_review_rows(review_state, current_review, int(time.time() * 1000))
            review_state = current_review
            contested = api.project('Runtime contested recovery')
            api.request('DELETE', '/api/prototype-hub/projects/' + contested['id'])
            for index in range(4 - api.usage()['projects']['used']):
                api.project('Runtime capacity filler ' + str(index))
            barrier = Barrier(2)
            def compete(candidate):
                # Two different HTTP requests reach separate JVMs and independent transactions.
                client, restore = candidate
                barrier.wait(timeout=10)
                result = (client.request('POST', '/api/prototype-hub/projects/' + contested['id'] +
                                         '/_restore', expected=(200, 409)) if restore else
                          client.project('Runtime contested ' + uuid.uuid4().hex, expected=(200, 409)))
                if result.get('id'):
                    require(not restore or result['id'] == contested['id'],
                            'Contested recovery replaced the project identity')
                    return 200
                require(result.get('code') == 'PROTOTYPE_HUB_PROJECT_LIMIT_REACHED',
                        'The rejected replica did not report project capacity')
                return 409
            with ThreadPoolExecutor(max_workers=2) as pool:
                outcomes = list(pool.map(compete, ((api, False), (other, True))))
            require(sorted(outcomes) == [200, 409], 'Replicas exceeded the last project slot')
            self.report['projectCreateRestoreCompetition'] = {'replicas': 2, 'outcomes': sorted(outcomes),
                                                               'used': 5, 'limit': 5}
            usage = api.usage()['projects']
            require(usage['used'] == 5 and not usage['canCreate'], 'Final project capacity differs')
        with self.phase('source-current-graph-before-backup'):
            self.verify_current_graph(api, current_graph, 'runtime_source')
            # A genuinely new OTP immediately before quiescence keeps a representative
            # short-lived row in the dump; its original deadline is never renewed.
            pending_invitation = review['invitations']['pending']
            pending_invitation['challenge'] = self.review_invitation_challenge(other, review, pending_invitation)
            self.stop(second_process)
            self.stop(process)
        with self.phase('quiescent-database-and-files-backup'):
            require(not self.processes, 'Backup attempted while an owned writer is still registered')
            self.expire_current_rows('runtime_source', current_graph, restoring=True)
            pending_restore = self.seed_pending_cleanup('runtime_source', storage, expired)
            before_database = current_manifest(self.database_manifest('runtime_source'))
            self.verify_sql_diagnostics('runtime_source', current_graph)
            before_sql_snapshot = self.sql_script('runtime_source', 'snapshot-db.sql')
            before_rows = self.database_rows('runtime_source')
            self.report['reviewTablesInBackup'] = review_manifest(before_database)
            before_review_rows = self.review_rows('runtime_source')
            before_files = storage_manifest(storage)
            before_system_files = storage_manifest(system_storage)
            require(before_files, 'Backup has no prototype files')
            require(len(before_system_files) == 2, 'Backup does not contain both uploaded system images')
            dump = self.workspace / 'database.dump'
            with dump.open('wb') as target:
                self.command(['docker', 'exec', self.container, 'pg_dump', '-U', 'postgres',
                              '-Fc', '--no-owner', '--no-acl', 'runtime_source'], stdout=target)
            backup_files = self.workspace / 'backup-storage'
            shutil.copytree(storage, backup_files)
            require(storage_manifest(backup_files) == before_files, 'File snapshot differs')
            backup_system_files = self.workspace / 'backup-system-files'
            shutil.copytree(system_storage, backup_system_files)
            require(storage_manifest(backup_system_files) == before_system_files,
                    'System file snapshot differs')
        with self.phase('new-database-and-storage-restore'):
            self.command(['docker', 'exec', self.container, 'createdb', '-U', 'postgres',
                          'runtime_restored'])
            with dump.open('rb') as source:
                self.command(['docker', 'exec', '-i', self.container, 'pg_restore', '-U', 'postgres',
                              '--exit-on-error', '--no-owner', '--no-acl', '-d', 'runtime_restored'],
                             input_data=source.read())
            restored_storage = self.workspace / 'restored-storage'
            shutil.copytree(backup_files, restored_storage)
            restored_system_storage = self.workspace / 'restored-system-files'
            shutil.copytree(backup_system_files, restored_system_storage)
            restored_database = self.database_manifest('runtime_restored')
            review_manifest(restored_database)
            require(restored_database == before_database,
                    'Restored database rows or relationships differ')
            compare_database_rows(before_rows, self.database_rows('runtime_restored'))
            require(self.sql_script('runtime_restored', 'snapshot-db.sql') == before_sql_snapshot,
                    'Restored operational SQL row/relationship snapshot differs before startup')
            require(self.review_rows('runtime_restored') == before_review_rows,
                    'Restored review row identities or checksums differ before startup')
            require(storage_manifest(restored_storage) == before_files, 'Restored file hashes differ')
            require(storage_manifest(restored_system_storage) == before_system_files,
                    'Restored system file hashes differ')
            # Remove only this run's retired roots so a successful read cannot fall back to them.
            shutil.rmtree(storage)
            shutil.rmtree(system_storage)
            allowed_expiry = registered_review_expiry(before_review_rows, int(time.time() * 1000))
            restored, restored_process = self.start('runtime_restored', restored_storage,
                                                    restored_system_storage, 'restored')
            expected_restored_files = self.verify_cleanup('runtime_restored', restored_storage,
                                                          before_files, pending_restore)
            system_hashes = self.verify_system_files(restored, system_files)
            self.verify_deleted_fixture(restored, deleted, zip_path, restored_storage, 'runtime_restored')
            self.verify_restored_fixture(restored, recovered, zip_path, restored_storage, 'runtime_restored',
                                         version['revisionId'])
            require(restored.usage()['installationId'] == installation_id,
                    'Installation identity changed after restore')
            require(restored.usage()['projects']['used'] == 5, 'Restored occupancy differs')
            require(restored.project('Runtime restore project', project_key)['id'] == project['id'],
                    'Project receipt did not survive restore')
            require(restored.upload(project['id'], zip_path, version_key)['id'] == version['id'],
                    'Version receipt did not survive restore')
            authorization_before = self.database_manifest('runtime_restored')
            for table in ('p_project', 'p_project_member'):
                require(authorization_before[table] == before_database[table],
                        'Startup or replay changed restored project authorization')
            require(self.preview(restored, version) == preview_hashes, 'Restored preview differs')
            self.verify_share(restored, share, preview_hashes)
            self.verify_share_policy(restored, retired_share, grant=retired_grant)
            self.verify_share_policy(restored, following_share, expected_preview=current_hashes)
            self.verify_review_fixtures(restored, review, 'runtime_restored')
            self.report['reviewStartupExpiredRowsRemoved'] = compare_review_rows(
                before_review_rows, self.review_rows('runtime_restored'), int(time.time() * 1000), allowed_expiry)
            self.verify_current_retention('runtime_restored', current_graph, before_review_rows, allowed_expiry)
            after_rows = self.database_rows('runtime_restored')
            expected_visits = dict(before_rows['p_share_visit'])
            require(expected_visits.pop(current_graph['visits']['oldRestoreId'], None) is not None,
                    'Restored backup omitted the registered expired visit')
            require(after_rows['p_share_visit'] == expected_visits,
                    'Visit retention changed an unregistered row or rewrote a surviving event')
            for table in ('p_review_association', 'p_copy_snapshot', 'p_copy_entry', 'p_work_item',
                          'p_creation_receipt', 'p_review_thread', 'p_review_message'):
                require(after_rows[table] == before_rows[table], 'Startup or replay changed durable rows: ' + table)
            self.report['reviewIdentity']['survivedRestartReplicaAndRestore'] = True
            self.verify_project_member(restored, member, project, version, preview_hashes)
            require(self.verify_revisions(restored, project, version, current, zip_path, version_key,
                                          preview_hashes, current_hashes) == manifest_hashes,
                    'Restored immutable manifests differ')
            authorization_after = self.database_manifest('runtime_restored')
            for table in ('p_project', 'p_project_member'):
                require(authorization_after[table] == before_database[table],
                        'Issuing a preview changed restored project authorization')
            # The dump included the exact short-lived rows. A new preview is an expected
            # session-table mutation, never permission to omit stable ownership tables.
            require(authorization_after['p_preview_session']['count'] <=
                    authorization_before['p_preview_session']['count'] + 4,
                    'Internal preview issued an unbounded number of sessions')
            self.report['restoredProjectAuthorization'] = {
                'tableHashesVerified': ['p_project', 'p_project_member'],
                'shortSessionsIncludedInDump': True,
                'nonEmptyMemberScopeReadVerified': True,
                'newPreviewSessionsBounded': True}
            # Explicit business writes begin only after the exact restore and read/replay checks.
            self.verify_restored_invitation(restored, review)
            require(storage_manifest(restored_storage) == expected_restored_files,
                    'Replay or startup changed restored file ownership')
            require(storage_manifest(restored_system_storage) == before_system_files,
                    'Startup changed restored system file ownership')
            self.report['restoredTables'] = before_database
            self.report['restoredRevisions'] = {
                'count': before_database['p_revision']['count'], 'activeSampleCount': 4,
                'retainedDeletedCount': 3, 'recoveredSampleCount': 2,
                'currentPointerPreserved': True, 'sharePinnedToOriginal': True,
                'creationReceiptPinnedToOriginal': True, 'manifestSha256': manifest_hashes,
                'historicalAndCurrentBytesVerified': True}
            self.report['restoredFileCount'] = len(expected_restored_files)
            self.report['restoredPendingCleanup'] = {
                'queueRowsInBackup': before_database['p_file_cleanup']['count'],
                'pendingFileCount': len(pending_restore['files']),
                'queueAndFilesVerifiedBeforeStartup': True,
                'queueDrainedOnNewDatabaseAndStorageRoot': True,
                'onlyOwnedPendingFilesRemoved': True}
            self.report['restoredSystemFiles'] = {
                'fileCount': len(before_system_files), 'downloadSha256': system_hashes,
                'databaseReferencesVerified': True, 'anonymousReadVerified': True,
                'retiredSourceRootsRemoved': True}
        with self.phase('restored-current-graph'):
            self.verify_current_graph(restored, current_graph, 'runtime_restored')
        with self.phase('restored-associated-write'):
            self.verify_restored_current_writes(restored, current_graph, 'runtime_restored')
        if self.args.frontend_dir:
            with self.phase('live-browser-on-restored-jar'):
                self.clear_business_data(restored)
                self.frontend(restored, zip_path)
        self.stop(restored_process)

    def clear_business_data(self, api):
        # Soft-delete only active resources in this run's random database. Retained
        # physical rows/files remain part of the restore evidence, never user data.
        for version in api.request('GET', '/api/prototype-hub/versions'):
            api.request('DELETE', '/api/prototype-hub/versions/' + version['id'])
        for project in api.request('GET', '/api/prototype-hub/projects'):
            api.request('DELETE', '/api/prototype-hub/projects/' + project['id'])
        require(api.usage()['projects']['used'] == 0, 'Disposable data cleanup failed')

    def frontend(self, api, zip_path):
        environment = dict(os.environ,
                           PROTO_E2E_API_URL=api.origin,
                           PROTO_E2E_USERNAME=self.username,
                           PROTO_E2E_PASSWORD=self.password,
                           PROTO_E2E_ZIP_PATH=str(zip_path))
        if self.mailbox:
            environment['PROTO_E2E_REVIEW_MAILBOX_PATH'] = str(self.mailbox.path)
        if self.ui_port:
            environment['PROTO_E2E_UI_PORT'] = str(self.ui_port)
        output_path = self.workspace / 'browser.log'
        cli = self.args.frontend_dir / 'node_modules/@playwright/test/cli.js'
        require(cli.is_file(), 'Built frontend is missing the public Playwright CLI')
        with output_path.open('wb') as output:
            process = subprocess.Popen(['node', str(cli), 'test', '--config',
                                        'playwright.live.config.ts', '--global-timeout',
                                        str(FRONTEND_GLOBAL_TIMEOUT_MS)], cwd=self.args.frontend_dir,
                                       env=environment, stdout=output, stderr=subprocess.STDOUT,
                                       start_new_session=True)
            self.processes.append(process)
            self.playwright_processes.add(process)
            try:
                result = process.wait(timeout=FRONTEND_WAIT_SECONDS)
            finally:
                self.stop(process)
        require(result == 0, 'Live browser checks failed')

    def cleanup_container(self):
        if not self.container_attempted:
            return
        inspected = subprocess.run(['docker', 'inspect', '--type', 'container',
                                    '--format', '{{json .}}', self.container_name],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   timeout=20, check=False)
        if inspected.returncode:
            if b'No such object:' in inspected.stderr or b'No such container:' in inspected.stderr:
                return
            raise VerificationFailure('Cannot confirm owned PostgreSQL container cleanup')
        identity = json.loads(inspected.stdout)
        labels = identity.get('Config', {}).get('Labels') or {}
        require(labels.get('com.prototype-hub.runtime-owner') == self.container_owner,
                'Refusing to remove a container with different ownership')
        self.command(['docker', 'rm', '-f', identity['Id']])

    def cleanup(self):
        failures = []
        for process in list(reversed(self.processes)):
            try:
                self.stop(process)
            except (OSError, subprocess.TimeoutExpired, VerificationFailure):
                failures.append('application-process')
        try:
            self.cleanup_container()
        except (OSError, subprocess.TimeoutExpired, VerificationFailure, ValueError, KeyError):
            failures.append('postgres-container')
        if self.mailbox:
            try:
                self.mailbox.close()
                self.mailbox = None
            except (OSError, RuntimeError):
                failures.append('local-mailbox')
        require(not failures, 'Cleanup failed for owned ' + ','.join(failures))

    def redact(self, text):
        # Browser-created invitation tokens never pass through this process's Api.
        text = re.sub(r'(?i)(#reviewInvitation=|#review-invitation=)[A-Za-z0-9_-]{43}',
                      r'\1[REDACTED]', text)
        text = re.sub(r'''(?i)(\binvitationToken\b[\\'":\s]{1,16})[A-Za-z0-9_-]{43}''',
                      r'\1[REDACTED]', text)
        for value in sorted(self.secrets_seen, key=len, reverse=True):
            text = text.replace(value, '[REDACTED]')
        return text

    def diagnostics(self):
        logs = []
        if self.external_failure:
            logs.append('--- external command ---\n' + self.external_failure)
        for path in sorted(self.workspace.rglob('process.log')):
            logs.append('--- ' + path.parent.name + ' ---\n' +
                        path.read_text(errors='replace')[-24000:])
        browser = self.workspace / 'browser.log'
        if browser.exists():
            logs.append('--- browser ---\n' + browser.read_text(errors='replace')[-12000:])
        return self.redact('\n'.join(logs))


def write_private(path, text):
    """Replace only the explicitly requested report, with private permissions."""
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent,
                                     prefix='.' + path.name, delete=False) as output:
        output.write(text)
        staged = Path(output.name)
    try:
        os.replace(staged, path)
    finally:
        staged.unlink(missing_ok=True)


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True, type=Path, help='Packaged Spring Boot JAR to run')
    default_java = Path(os.environ['JAVA_HOME']) / 'bin/java' if os.environ.get('JAVA_HOME') else 'java'
    parser.add_argument('--java', type=Path, default=default_java, help='Java 21 executable')
    parser.add_argument('--postgres-image', default='postgres:16-alpine', help='Disposable PostgreSQL image')
    parser.add_argument('--report', type=Path, help='Private JSON result; parent directory must exist')
    parser.add_argument('--frontend-dir', type=Path, help='Optional built frontend with live Playwright config')
    args = parser.parse_args(argv)
    args.jar = args.jar.resolve()
    if not args.jar.is_file():
        parser.error('--jar must be an existing packaged JAR')
    if args.report:
        args.report = args.report.absolute()
        if not args.report.parent.is_dir():
            parser.error('--report parent directory must exist')
    if args.frontend_dir:
        args.frontend_dir = args.frontend_dir.resolve()
        if not (args.frontend_dir / 'playwright.live.config.ts').is_file():
            parser.error('--frontend-dir must contain playwright.live.config.ts')
    return args


def main(argv=None):
    args = arguments(argv)
    exit_code = 0
    check = None
    report = {'schemaVersion': 1, 'status': 'failed'}
    diagnostics = ''
    try:
        with tempfile.TemporaryDirectory(prefix='prototype-runtime-') as temporary:
            workspace = Path(temporary)
            workspace.chmod(0o700)
            check = RuntimeCheck(args, workspace)
            report = check.report
            try:
                check.execute()
                report['status'] = 'passed'
            except KeyboardInterrupt:
                report.update(status='failed', failedStage=check.stage, error='Interrupted')
                exit_code = 130
            except (VerificationFailure, OSError, ValueError, KeyError,
                    subprocess.TimeoutExpired) as error:
                report.update(status='failed', failedStage=check.stage,
                              error=check.redact(str(error)))
                exit_code = 1
            except Exception as error:
                # An unexpected response shape must replace a prior result with failure.
                # Only the type is safe here: a foreign exception may contain credentials.
                report.update(status='failed', failedStage=check.stage,
                              error='Unexpected verifier failure: ' + type(error).__name__)
                exit_code = 1
            finally:
                try:
                    check.cleanup()
                except VerificationFailure as error:
                    report.update(status='failed', cleanupError=check.redact(str(error)))
                    exit_code = 1
                diagnostics = check.diagnostics()
    except OSError:
        report.update(status='failed', cleanupError='Unable to remove private verification workspace')
        if check:
            report['remainingWorkspace'] = str(check.workspace)
        exit_code = 1
    # Only publish success after children, the container and the private directory are gone.
    if args.report:
        write_private(args.report, json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        if exit_code:
            write_private(args.report.with_suffix('.diagnostics.log'), diagnostics)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
