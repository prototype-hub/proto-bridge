#!/usr/bin/env python3
"""Pinned release-tool versions shared by installation and execution gates.

Version checks establish the selected CLI contract. Archive integrity remains the
installer's checksum check; this does not authenticate an arbitrary PATH binary.
"""

import argparse
import re
import shutil
import subprocess
import sys

VERSIONS = {'gitleaks': '8.30.1', 'trivy': '0.75.0'}


def checked_tool(name):
    executable = shutil.which(name)
    if not executable:
        raise ValueError(f'Install pinned {name} {VERSIONS[name]} using tools/install-security-tools.sh')
    arguments = [executable, 'version' if name == 'gitleaks' else '--version']
    try:
        result = subprocess.run(arguments, capture_output=True, text=True, timeout=15, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError(f'Unable to check {name} version') from error
    pattern = r'(?m)^Version:\s*([0-9]+\.[0-9]+\.[0-9]+)\s*$' if name == 'trivy' else r'^([0-9]+\.[0-9]+\.[0-9]+)\s*$'
    versions = re.findall(pattern, result.stdout)
    if result.returncode != 0 or versions != [VERSIONS[name]]:
        raise ValueError(f'{name} {VERSIONS[name]} is required; installed CLI did not match')
    return executable


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', choices=VERSIONS)
    parser.add_argument('--check', nargs='+', choices=VERSIONS)
    arguments = parser.parse_args()
    if arguments.version:
        print(VERSIONS[arguments.version])
    elif arguments.check:
        for name in arguments.check:
            checked_tool(name)
    else:
        parser.error('Choose --version or --check')


if __name__ == '__main__':
    try:
        main()
    except ValueError as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)
