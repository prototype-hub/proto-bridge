#!/usr/bin/env bash
set -euo pipefail
out=${1:?Usage: install-security-tools.sh ABSOLUTE_OUTPUT_DIRECTORY}
[[ "$out" == /* ]] || { echo "Absolute output directory required" >&2; exit 2; }
mkdir -p "$out"
case "$(uname -s)/$(uname -m)" in
 Linux/x86_64) platform=Linux; arch=64bit; gitleaks_platform=linux_x64;;
 Darwin/x86_64) platform=macOS; arch=64bit; gitleaks_platform=darwin_x64;;
 Darwin/arm64) platform=macOS; arch=ARM64; gitleaks_platform=darwin_arm64;;
 *) echo "Unsupported platform; install verified release binaries manually" >&2; exit 2;;
esac
fetch() {
 local base=$1 archive=$2 checksums=$3
 curl --fail --location --retry 3 "$base/$archive" --output "$out/$archive"
 curl --fail --location --retry 3 "$base/$checksums" --output "$out/$checksums"
 python3 - "$out/$archive" "$out/$checksums" <<'HASH'
import hashlib,sys
from pathlib import Path
archive,checksums=map(Path,sys.argv[1:]);matches=[line.split()[0] for line in checksums.read_text().splitlines() if line.split()[-1].lstrip('*')==archive.name]
if len(matches)!=1 or hashlib.sha256(archive.read_bytes()).hexdigest()!=matches[0]:raise SystemExit('Release checksum mismatch')
HASH
 tar -xzf "$out/$archive" -C "$out"
}
fetch https://github.com/gitleaks/gitleaks/releases/download/v8.30.1 "gitleaks_8.30.1_${gitleaks_platform}.tar.gz" gitleaks_8.30.1_checksums.txt
fetch https://github.com/aquasecurity/trivy/releases/download/v0.75.0 "trivy_0.75.0_${platform}-${arch}.tar.gz" trivy_0.75.0_checksums.txt
printf 'Tools installed in %s; add this directory to PATH.\n' "$out"
