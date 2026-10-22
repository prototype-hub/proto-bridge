#!/usr/bin/env python3
"""Verify a packaged JAR using only disposable PostgreSQL and original HTML data.

This is a process/restore check, not an installer or a production backup tool.
No existing database or storage path can be supplied. Credentials stay in child
environments and private temporary files; reports contain only bounded evidence.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
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
import urllib.request
import uuid
import zipfile
import zlib


FIXTURE = Path(__file__).resolve().parent / 'fixtures' / 'runtime-site'
TABLES = ('p_installation', 'p_project', 'p_project_member', 'p_preview_session', 'p_version', 'p_revision', 'p_share',
          'p_creation_receipt', 's_user', 's_dimension', 's_dimension_user',
          's_autz_setting_info', 's_permission', 's_menu', 's_role_menu_grant',
          's_user_detail', 's_system_config')
FRONTEND_GLOBAL_TIMEOUT_MS = 300000
FRONTEND_WAIT_SECONDS = 330


class VerificationFailure(RuntimeError):
    """A bounded failure message which never includes an HTTP response body."""


def require(condition, message):
    if not condition:
        raise VerificationFailure(message)


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
                if key in ('token', 'accessKey', 'previewUrl', 'runtimeBaseUrl') and item:
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

    def upload(self, project, zip_path, key):
        body, content_type = multipart(zip_path)
        return self.request('POST', '/api/prototype-hub/projects/' + project + '/versions', body,
                            headers={'Content-Type': content_type, 'Idempotency-Key': key})

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

    def sql(self, database, sql):
        return self.command(['docker', 'exec', '-i', self.container, 'psql', '-X', '-qAt',
                             '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', database],
                            input_data=sql.encode()).decode().strip()

    def database_manifest(self, database):
        result = {}
        for table in TABLES:
            result[table] = json.loads(self.sql(database,
                "select json_build_object('count', count(*), 'contentMd5', "
                "md5(coalesce(jsonb_agg(to_jsonb(row_data) order by id)::text, '[]'))) "
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
        output = (working_directory / 'process.log').open('wb')
        try:
            process = subprocess.Popen([str(self.args.java), '-Xms128m', '-Xmx512m', '-jar',
                                        str(self.args.jar), '--server.address=127.0.0.1'],
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
        if share.get('revisionId'):
            require(granted.get('revisionId') == share['revisionId'], 'Public share changed its fixed revision')
        current = self.preview(api, granted)
        if expected_preview:
            require(current == expected_preview, 'Share content changed after process/restore')
        return current

    def verify_revisions(self, api, project, original, current, zip_path, key,
                         original_hashes, current_hashes):
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
        return sorted(item['manifestSha256'] for item in (first[0], second[0]))

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

    def execute(self):
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
        with self.phase('full-process-restart'):
            self.stop(process)
            api, process = self.start('runtime_source', storage, system_storage, 'restarted')
            self.verify_system_files(api, system_files)
            require(api.usage()['installationId'] == installation_id,
                    'Installation identity changed on restart')
            require(api.project('Runtime restore project', project_key)['id'] == project['id'],
                    'Project receipt did not survive restart')
            require(api.upload(project['id'], zip_path, version_key)['id'] == version['id'],
                    'Version receipt did not survive restart')
            require(self.preview(api, version) == preview_hashes, 'Preview bytes changed on restart')
            self.verify_project_member(api, member, project, version, preview_hashes)
            self.verify_share(api, share, preview_hashes)
            require(self.verify_revisions(api, project, version, current, zip_path, version_key,
                                          preview_hashes, current_hashes) == manifest_hashes,
                    'Revision manifests changed on process restart')
        with self.phase('two-process-final-project-slot'):
            other, second_process = self.start('runtime_source', storage, system_storage, 'second-replica')
            self.verify_system_files(other, system_files)
            require(other.usage()['installationId'] == installation_id,
                    'Replicas do not share installation identity')
            for index in range(3):
                api.project('Runtime capacity filler ' + str(index))
            barrier = Barrier(2)
            def compete(client):
                # Two different HTTP requests reach separate JVMs and independent transactions.
                barrier.wait(timeout=10)
                result = client.project('Runtime contested ' + uuid.uuid4().hex,
                                        expected=(200, 409))
                if result.get('id'):
                    return 200
                require(result.get('code') == 'PROTOTYPE_HUB_PROJECT_LIMIT_REACHED',
                        'The rejected replica did not report project capacity')
                return 409
            with ThreadPoolExecutor(max_workers=2) as pool:
                outcomes = list(pool.map(compete, (api, other)))
            require(sorted(outcomes) == [200, 409], 'Replicas exceeded the last project slot')
            usage = api.usage()['projects']
            require(usage['used'] == 5 and not usage['canCreate'], 'Final project capacity differs')
            self.stop(second_process)
            self.stop(process)
        with self.phase('quiescent-database-and-files-backup'):
            require(not self.processes, 'Backup attempted while an owned writer is still registered')
            before_database = self.database_manifest('runtime_source')
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
            require(self.database_manifest('runtime_restored') == before_database,
                    'Restored database rows or relationships differ')
            require(storage_manifest(restored_storage) == before_files, 'Restored file hashes differ')
            require(storage_manifest(restored_system_storage) == before_system_files,
                    'Restored system file hashes differ')
            # Remove only this run's retired roots so a successful read cannot fall back to them.
            shutil.rmtree(storage)
            shutil.rmtree(system_storage)
            restored, restored_process = self.start('runtime_restored', restored_storage,
                                                    restored_system_storage, 'restored')
            system_hashes = self.verify_system_files(restored, system_files)
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
            require(storage_manifest(restored_storage) == before_files,
                    'Replay or startup changed restored file ownership')
            require(storage_manifest(restored_system_storage) == before_system_files,
                    'Startup changed restored system file ownership')
            self.report['restoredTables'] = before_database
            self.report['restoredRevisions'] = {
                'count': 2, 'currentPointerPreserved': True, 'sharePinnedToOriginal': True,
                'creationReceiptPinnedToOriginal': True, 'manifestSha256': manifest_hashes,
                'historicalAndCurrentBytesVerified': True}
            self.report['restoredFileCount'] = len(before_files)
            self.report['restoredSystemFiles'] = {
                'fileCount': len(before_system_files), 'downloadSha256': system_hashes,
                'databaseReferencesVerified': True, 'anonymousReadVerified': True,
                'retiredSourceRootsRemoved': True}
        if self.args.frontend_dir:
            with self.phase('live-browser-on-restored-jar'):
                self.clear_business_data(restored)
                self.frontend(restored, zip_path)
        self.stop(restored_process)

    def clear_business_data(self, api):
        # These resources belong to the random database created above, never a user deployment.
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
        require(not failures, 'Cleanup failed for owned ' + ','.join(failures))

    def redact(self, text):
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
                    report.update(status='failed', cleanupError=str(error))
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
