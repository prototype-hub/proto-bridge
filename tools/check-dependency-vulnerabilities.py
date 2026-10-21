#!/usr/bin/env python3
"""Publish validated Trivy reports bound to one immutable CycloneDX input.

Only a completed scan publishes a private report and a receipt committed last.
A receipt is an integrity marker, not a signature or proof of exploitability.
"""

import argparse
import contextlib
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import tempfile

from security_tools import VERSIONS, checked_tool

ROOT = Path(__file__).resolve().parents[1]
FINDINGS_EXIT = 10  # Keep completed findings distinct from Trivy operational exit 1.


def regular_file(path):
    if not stat.S_ISREG(path.lstat().st_mode):
        raise ValueError(f'Expected a regular file: {path.name}')
    return path


def output_paths(target):
    paths = [target / 'vulnerabilities-receipt.json', target / 'vulnerabilities.json']
    # Check both before unlinking: never follow or replace a caller's symlink.
    for path in paths:
        if path.exists() or path.is_symlink():
            regular_file(path)
    return paths


def revoke_reports(target):
    # Revoke the success marker first, even when a damaged report must be refused.
    # A symlink marker itself is rejected without following or changing its target.
    for name in ('vulnerabilities-receipt.json', 'vulnerabilities.json'):
        path = target / name
        if path.exists() or path.is_symlink():
            regular_file(path)
        path.unlink(missing_ok=True)


@contextlib.contextmanager
def scan_lock(target):
    target.mkdir(exist_ok=True)
    if not stat.S_ISDIR(target.lstat().st_mode):
        raise ValueError('target must be a real directory')
    descriptor = os.open(target / '.vulnerability-scan.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise ValueError('The scan lock must be a regular file')
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise ValueError('Another vulnerability scan owns this target directory') from error
        # Keep the lock inode after release: deleting it permits split ownership.
        yield
    finally:
        os.close(descriptor)


def run_scanner(command):
    # Proxy/CA settings remain available, but inherited Trivy policy flags must
    # not silently turn a release scan into an offline or ignored-only scan.
    environment = {name: value for name, value in os.environ.items() if not name.startswith('TRIVY_')}
    process = None
    pending_signals = []
    previous_handlers = {signum: signal.getsignal(signum) for signum in (signal.SIGINT, signal.SIGTERM)}
    try:
        # Defer Python exceptions until Popen returns an owned child. Signal masks
        # remain unblocked, so the child does not inherit blocked cancellation.
        try:
            for signum in previous_handlers:
                signal.signal(signum, lambda received, _frame: pending_signals.append(received))
            process = subprocess.Popen(command, start_new_session=True, env=environment)
        finally:
            for signum, handler in previous_handlers.items():
                signal.signal(signum, handler)
        if pending_signals:
            if pending_signals[0] == signal.SIGINT:
                raise KeyboardInterrupt()
            raise InterruptedError(pending_signals[0])
        return process.wait()
    except BaseException:
        # Interruptions must not leave an orphan scanner writing a discarded run.
        if process is not None and process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
        raise


def validate_report(path, snapshot):
    report_bytes = regular_file(path).read_bytes()
    report = json.loads(report_bytes)
    if (not isinstance(report, dict) or report.get('SchemaVersion') != 2
            or report.get('ArtifactType') != 'cyclonedx'
            or report.get('ArtifactName') != str(snapshot)
            or report.get('Trivy', {}).get('Version') != VERSIONS['trivy']):
        raise ValueError('Trivy report identity or schema did not match this scan')
    results = report.get('Results', [])  # Official JSON omits empty Results.
    if not isinstance(results, list):
        raise ValueError('Trivy report Results must be an array')
    findings = 0
    for result in results:
        if not isinstance(result, dict) or not isinstance(result.get('Vulnerabilities', []), list):
            raise ValueError('Malformed Trivy vulnerability results')
        for vulnerability in result.get('Vulnerabilities', []):
            if (not isinstance(vulnerability, dict)
                    or vulnerability.get('Severity') not in ('HIGH', 'CRITICAL')
                    or not vulnerability.get('VulnerabilityID')):
                raise ValueError('Malformed or unexpected-severity Trivy vulnerability')
            findings += 1
    return report_bytes, findings


def scan(preflight=False):
    target = ROOT / 'target'
    with scan_lock(target):
        revoke_reports(target)
        executable = checked_tool('trivy')
        if preflight:
            checked_tool('gitleaks')
            return 0
        sbom = target / 'bom.json'
        source = regular_file(sbom).read_bytes()
        document = json.loads(source)
        if (not isinstance(document, dict) or document.get('bomFormat') != 'CycloneDX'
                or not isinstance(document.get('components'), list) or not document['components']):
            raise ValueError('Generate a nonempty CycloneDX SBOM with ./mvnw verify -Prelease-audit')
        source_sha = hashlib.sha256(source).hexdigest()
        started = datetime.now(timezone.utc).isoformat()
        cache = Path(os.environ.get('TRIVY_CACHE_DIR', str(Path(tempfile.gettempdir()) / 'proto-trivy-cache')))
        with tempfile.TemporaryDirectory(prefix='.vulnerability-scan-', dir=target) as temporary:
            run = Path(temporary)
            snapshot = run / 'bom.json'
            snapshot.write_bytes(source)
            snapshot.chmod(0o600)
            output = run / 'report.json'
            configuration = run / 'trivy.yaml'
            configuration.write_text('{}\n')
            # Retain official database selection/update and HIGH/CRITICAL policy.
            code = run_scanner([executable, 'sbom', '--config', str(configuration), '--ignorefile', '',
                                '--cache-dir', str(cache),
                                '--severity', 'HIGH,CRITICAL', '--format', 'json',
                                '--output', str(output), '--exit-code', str(FINDINGS_EXIT), str(snapshot)])
            if code not in (0, FINDINGS_EXIT):
                return code if code > 0 else 128 - code
            report_bytes, findings = validate_report(output, snapshot)
            if (code == FINDINGS_EXIT) != (findings > 0):
                raise ValueError('Trivy exit status and reported findings disagree')
            if hashlib.sha256(regular_file(sbom).read_bytes()).hexdigest() != source_sha:
                raise ValueError('The input SBOM changed during this scan')
            metadata_path = cache / 'db/metadata.json'
            metadata = json.loads(regular_file(metadata_path).read_bytes())
            if not isinstance(metadata, dict) or not all(key in metadata for key in ('Version', 'UpdatedAt', 'NextUpdate')):
                raise ValueError('The completed scan has no valid vulnerability database metadata')
            receipt = {'schemaVersion': 1, 'status': 'failed' if findings else 'passed',
                       'scanner': {'name': 'trivy', 'version': VERSIONS['trivy']},
                       'startedAt': started, 'finishedAt': datetime.now(timezone.utc).isoformat(),
                       'sbom': {'path': 'target/bom.json', 'sha256': source_sha},
                       'report': {'path': 'target/vulnerabilities.json',
                                  'sha256': hashlib.sha256(report_bytes).hexdigest()},
                       'severity': ['HIGH', 'CRITICAL'], 'findingCount': findings,
                       'database': {key: metadata[key] for key in ('Version', 'UpdatedAt', 'NextUpdate', 'DownloadedAt') if key in metadata}}
            receipt_path = run / 'receipt.json'
            receipt_path.write_text(json.dumps(receipt, indent=2) + '\n')
            receipt_path.chmod(0o600)
            output.chmod(0o600)
            # The last rename is the commit marker; consumers require both hashes.
            output_paths(target)
            try:
                os.replace(output, target / 'vulnerabilities.json')
                os.replace(receipt_path, target / 'vulnerabilities-receipt.json')
            except BaseException:
                revoke_reports(target)
                raise
            return 1 if findings else 0


def interrupted(signum, _frame):
    raise InterruptedError(signum)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--preflight', action='store_true', help='Revoke prior reports and check both pinned tools before Maven')
    arguments = parser.parse_args()
    signal.signal(signal.SIGTERM, interrupted)
    try:
        return scan(arguments.preflight)
    except KeyboardInterrupt:
        return 130
    except InterruptedError as error:
        return 128 + error.args[0]
    except (OSError, ValueError, TypeError, AttributeError) as error:
        print('Vulnerability gate failed: ' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
