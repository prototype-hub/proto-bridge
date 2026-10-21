#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# A failed database update or missing scanner must revoke an earlier report.
rm -f target/vulnerabilities.json
command -v trivy >/dev/null || { echo "Install the pinned Trivy version using tools/install-security-tools.sh" >&2; exit 2; }
[[ -f target/bom.json ]] || { echo "Generate the SBOM with ./mvnw verify -Prelease-audit" >&2; exit 2; }
# Keep Trivy's official repository selection and normal database verification.
trivy sbom --cache-dir "${TRIVY_CACHE_DIR:-${TMPDIR:-/tmp}/proto-trivy-cache}" --severity HIGH,CRITICAL --format json --output target/vulnerabilities.json --exit-code 1 target/bom.json
