"""Exercise release gate ownership and report contracts with isolated fake CLIs.

These tests never establish that an actual dependency scan or database update
passed. They model CLI output and failures to prevent false release evidence.
"""

import hashlib
import json
import os
from pathlib import Path
import runpy
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock


FAKE_TRIVY = r'''
import json, os, pathlib, sys, time
if sys.argv[1:] == ['--version']:
    print('Version: ' + os.environ.get('FAKE_TRIVY_VERSION', '0.75.0'))
    raise SystemExit(int(os.environ.get('FAKE_VERSION_EXIT', '0')))
args = sys.argv[1:]
output = pathlib.Path(args[args.index('--output') + 1])
snapshot = pathlib.Path(args[-1])
mode = os.environ.get('FAKE_MODE', 'clean')
pathlib.Path(os.environ['FAKE_CALLED']).write_text(json.dumps(args))
if mode == 'check-policy':
    assert not any(name.startswith('TRIVY_') for name in os.environ)
    assert os.environ['HTTPS_PROXY'] == 'http://proxy.example.invalid:8080'
    assert args[args.index('--ignorefile') + 1] == ''
    assert pathlib.Path(args[args.index('--config') + 1]).read_text().strip() == '{}'
if mode == 'wait':
    output.write_text('{partial')
    pathlib.Path(os.environ['FAKE_READY']).write_text(str(os.getpid()))
    while True: time.sleep(.1)
if mode == 'error':
    output.write_text('{partial')
    raise SystemExit(23)
if mode in ('malformed', 'empty', 'wrong-shape'):
    output.write_text({'malformed': '{partial', 'empty': '', 'wrong-shape': '[]'}[mode])
    raise SystemExit(0)
report = {'SchemaVersion': 2, 'Trivy': {'Version': '0.75.0'},
          'ArtifactName': str(snapshot), 'ArtifactType': 'cyclonedx'}
if mode in ('findings', 'inconsistent-clean-exit', 'bad-severity'):
    report['Results'] = [{'Target': 'fixture.jar', 'Vulnerabilities': [
        {'VulnerabilityID': 'CVE-FIXTURE-ONLY', 'Severity': 'LOW' if mode == 'bad-severity' else 'HIGH'}]}]
if mode == 'wrong-input': report['ArtifactName'] = 'different.json'
if mode == 'wrong-schema': report['SchemaVersion'] = 1
if mode == 'wrong-tool': report['Trivy']['Version'] = '0.1.0'
if mode == 'input-drift': pathlib.Path(os.environ['FAKE_SOURCE']).write_text('{}')
if mode == 'late-output-link': pathlib.Path(os.environ['FAKE_FINAL']).symlink_to(os.environ['FAKE_SOURCE'])
output.write_text(json.dumps(report))
raise SystemExit(10 if mode in ('findings', 'inconsistent-finding-exit') else 0)
'''


class SecurityGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='security-gate-test-')
        self.base = Path(self.temporary.name).resolve()
        self.root = self.base / 'source'
        self.target = self.root / 'target'
        self.tools = self.root / 'tools'
        self.target.mkdir(parents=True)
        self.tools.mkdir()
        source = Path(__file__).parent
        for name in ('check-dependency-vulnerabilities.py', 'check-dependency-vulnerabilities.sh', 'security_tools.py'):
            shutil.copyfile(source / name, self.tools / name)
        self.binaries = self.base / 'bin'
        self.binaries.mkdir()
        fake = self.binaries / 'trivy'
        fake.write_text('#!' + sys.executable + '\n' + FAKE_TRIVY)
        fake.chmod(0o700)
        leaks = self.binaries / 'gitleaks'
        leaks.write_text('#!' + sys.executable + '\nimport os\nprint(os.environ.get("FAKE_GITLEAKS_VERSION", "8.30.1"))\n')
        leaks.chmod(0o700)
        self.cache = self.base / 'cache'
        (self.cache / 'db').mkdir(parents=True)
        self.metadata = {'Version': 2, 'UpdatedAt': '2026-10-21T01:00:00Z', 'NextUpdate': '2026-10-21T07:00:00Z'}
        (self.cache / 'db/metadata.json').write_text(json.dumps(self.metadata))
        self.sbom = self.target / 'bom.json'
        self.sbom.write_text(json.dumps({'bomFormat': 'CycloneDX', 'specVersion': '1.6',
                                       'components': [{'type': 'library', 'name': 'fixture', 'version': '1'}]}))
        self.report = self.target / 'vulnerabilities.json'
        self.receipt = self.target / 'vulnerabilities-receipt.json'
        self.called = self.base / 'called.json'
        self.ready = self.base / 'ready'
        self.environment = {**os.environ, 'PATH': str(self.binaries),
                            'TRIVY_CACHE_DIR': str(self.cache), 'FAKE_CALLED': str(self.called),
                            'FAKE_READY': str(self.ready), 'FAKE_SOURCE': str(self.sbom),
                            'FAKE_FINAL': str(self.report)}
        self.command = [sys.executable, str(self.tools / 'check-dependency-vulnerabilities.py')]

    def tearDown(self):
        self.temporary.cleanup()

    def run_gate(self, mode='clean', extra=None, arguments=None):
        return subprocess.run(self.command + (arguments or []), env={**self.environment, 'FAKE_MODE': mode, **(extra or {})},
                              text=True, capture_output=True, timeout=15)

    def seed_old_reports(self):
        self.report.write_text('{"old":true}')
        self.receipt.write_text('{"status":"passed","old":true}')

    def assert_no_reports(self):
        self.assertFalse(self.report.exists())
        self.assertFalse(self.receipt.exists())
        self.assertEqual([], list(self.target.glob('.vulnerability-scan-*')))

    def test_clean_report_has_private_input_bound_receipt_and_official_policy(self):
        self.seed_old_reports()
        result = self.run_gate()
        self.assertEqual(0, result.returncode, result.stderr)
        receipt = json.loads(self.receipt.read_text())
        self.assertEqual('passed', receipt['status'])
        self.assertEqual(0, receipt['findingCount'])
        self.assertEqual(hashlib.sha256(self.sbom.read_bytes()).hexdigest(), receipt['sbom']['sha256'])
        self.assertEqual(hashlib.sha256(self.report.read_bytes()).hexdigest(), receipt['report']['sha256'])
        self.assertEqual(self.metadata, receipt['database'])
        for path in (self.report, self.receipt):
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
        arguments = json.loads(self.called.read_text())
        self.assertEqual('HIGH,CRITICAL', arguments[arguments.index('--severity') + 1])
        self.assertFalse(any('skip' in item or 'db-repository' in item for item in arguments))
        self.assertFalse(Path(arguments[-1]).exists())

    def test_completed_findings_fail_with_valid_failed_receipt(self):
        result = self.run_gate('findings')
        self.assertEqual(1, result.returncode, result.stderr)
        receipt = json.loads(self.receipt.read_text())
        self.assertEqual('failed', receipt['status'])
        self.assertEqual(1, receipt['findingCount'])

    def test_inherited_scan_policy_cannot_weaken_release_scan(self):
        result = self.run_gate('check-policy', extra={'TRIVY_IGNORE_UNFIXED': 'true',
                                                     'TRIVY_SKIP_DB_UPDATE': 'true',
                                                     'TRIVY_IGNORE_POLICY': '/unsafe/local-policy.rego',
                                                     'HTTPS_PROXY': 'http://proxy.example.invalid:8080'})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_operational_failure_revokes_old_and_partial_reports_preserving_exit(self):
        self.seed_old_reports()
        self.assertEqual(23, self.run_gate('error').returncode)
        self.assert_no_reports()

    def test_missing_tool_revokes_old_results(self):
        self.seed_old_reports()
        (self.binaries / 'trivy').unlink()
        self.assertEqual(2, self.run_gate().returncode)
        self.assert_no_reports()

    def test_wrong_tool_version_does_not_scan_or_reuse_results(self):
        self.seed_old_reports()
        self.assertEqual(2, self.run_gate(extra={'FAKE_TRIVY_VERSION': '0.1.0'}).returncode)
        self.assert_no_reports()
        self.assertFalse(self.called.exists())

    def test_version_command_failure_does_not_scan(self):
        self.assertEqual(2, self.run_gate(extra={'FAKE_VERSION_EXIT': '23'}).returncode)
        self.assertFalse(self.called.exists())

    def test_preflight_checks_gitleaks_and_needs_no_sbom(self):
        self.sbom.unlink()
        self.assertEqual(0, self.run_gate(arguments=['--preflight']).returncode)
        self.assertEqual(2, self.run_gate(extra={'FAKE_GITLEAKS_VERSION': '8.0.0'}, arguments=['--preflight']).returncode)
        self.assertFalse(self.called.exists())

    def test_malformed_empty_and_inconsistent_reports_never_publish(self):
        for mode in ('malformed', 'empty', 'wrong-shape', 'wrong-input', 'wrong-schema',
                     'wrong-tool', 'bad-severity', 'inconsistent-clean-exit', 'inconsistent-finding-exit'):
            with self.subTest(mode=mode):
                self.seed_old_reports()
                self.assertEqual(2, self.run_gate(mode).returncode)
                self.assert_no_reports()

    def test_input_drift_never_publishes_even_valid_report(self):
        self.assertEqual(2, self.run_gate('input-drift').returncode)
        self.assert_no_reports()

    def test_missing_or_empty_sbom_fails_before_scanning(self):
        for contents in ('{}', '{"bomFormat":"CycloneDX","components":[]}'):
            self.sbom.write_text(contents)
            self.assertEqual(2, self.run_gate().returncode)
            self.assertFalse(self.called.exists())
        self.sbom.unlink()
        self.assertEqual(2, self.run_gate().returncode)
        self.assert_no_reports()

    def test_missing_database_metadata_cannot_produce_pass_receipt(self):
        (self.cache / 'db/metadata.json').unlink()
        self.assertEqual(2, self.run_gate().returncode)
        self.assert_no_reports()

    def test_symlink_reports_and_input_are_not_followed_or_overwritten(self):
        outside = self.base / 'untouched.json'
        outside.write_text('unchanged')
        for path in (self.report, self.receipt, self.sbom):
            with self.subTest(path=path.name):
                path.unlink(missing_ok=True)
                path.symlink_to(outside)
                self.assertEqual(2, self.run_gate().returncode)
                self.assertTrue(path.is_symlink())
                self.assertEqual('unchanged', outside.read_text())
                path.unlink()

    def test_directory_outputs_are_not_removed(self):
        self.report.mkdir()
        self.assertEqual(2, self.run_gate().returncode)
        self.assertTrue(self.report.is_dir())

    def test_old_pass_marker_is_revoked_before_refusing_report_symlink(self):
        self.seed_old_reports()
        original = self.sbom.read_bytes()
        self.report.unlink()
        self.report.symlink_to(self.sbom)
        result = self.run_gate()
        self.assertEqual(2, result.returncode)
        self.assertFalse(self.receipt.exists())
        self.assertTrue(self.report.is_symlink())
        self.assertEqual(original, self.sbom.read_bytes())

    def test_output_symlink_created_during_scan_is_not_overwritten(self):
        original = self.sbom.read_bytes()
        result = self.run_gate('late-output-link')
        self.assertEqual(2, result.returncode)
        self.assertTrue(self.report.is_symlink())
        self.assertEqual(original, self.sbom.read_bytes())
        self.assertFalse(self.receipt.exists())

    def wait_ready(self, process):
        deadline = time.monotonic() + 5
        while not self.ready.exists() and process.poll() is None and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertTrue(self.ready.exists(), 'fake scanner did not start')

    def test_concurrent_run_cannot_revoke_owner_reports_or_scanner(self):
        process = subprocess.Popen(self.command, env={**self.environment, 'FAKE_MODE': 'wait'},
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.wait_ready(process)
            result = self.run_gate()
            self.assertEqual(2, result.returncode)
            self.assertIn('Another vulnerability scan', result.stderr)
            self.assertIsNone(process.poll())
        finally:
            process.send_signal(signal.SIGTERM)
            process.communicate(timeout=10)
        self.assertEqual(143, process.returncode)
        self.assert_no_reports()

    def test_interrupt_reaps_child_and_revokes_every_final_output(self):
        self.seed_old_reports()
        process = subprocess.Popen(self.command, env={**self.environment, 'FAKE_MODE': 'wait'},
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.wait_ready(process)
            child_pid = int(self.ready.read_text())
            process.send_signal(signal.SIGINT)
            process.communicate(timeout=10)
            self.assertEqual(130, process.returncode)
            with self.assertRaises(ProcessLookupError):
                os.kill(child_pid, 0)
            self.assert_no_reports()
        finally:
            if process.poll() is None:
                process.terminate()
                process.communicate(timeout=10)

    def test_signal_during_popen_registration_reaps_child_and_restores_handlers(self):
        runner = runpy.run_path(str(Path(__file__).with_name('check-dependency-vulnerabilities.py')))
        popen = subprocess.Popen
        owned = []
        previous = signal.getsignal(signal.SIGTERM)

        def interrupted_start(command, **options):
            child = popen(command, **options)
            owned.append(child)
            signal.raise_signal(signal.SIGTERM)
            return child

        def terminate_handler(signum, _frame):
            raise InterruptedError(signum)

        signal.signal(signal.SIGTERM, terminate_handler)
        try:
            with mock.patch.object(subprocess, 'Popen', side_effect=interrupted_start):
                with self.assertRaises(InterruptedError):
                    runner['run_scanner']([sys.executable, '-c', 'import time; time.sleep(30)'])
            self.assertIsNotNone(owned[0].poll(), 'child escaped during Popen registration')
            self.assertIs(terminate_handler, signal.getsignal(signal.SIGTERM))
        finally:
            signal.signal(signal.SIGTERM, previous)
            for child in owned:
                if child.poll() is None:
                    child.terminate()
                    child.wait(timeout=5)


if __name__ == '__main__':
    unittest.main()
