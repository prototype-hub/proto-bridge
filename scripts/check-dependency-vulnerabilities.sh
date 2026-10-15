#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v trivy >/dev/null || { echo "Install the pinned Trivy version using scripts/install-security-tools.sh" >&2; exit 2; }
[[ -f target/bom.json ]] || { echo "Generate the SBOM with ./mvnw verify -Prelease-audit" >&2; exit 2; }
trivy sbom --db-repository ghcr.io/aquasecurity/trivy-db:2 --cache-dir "${TRIVY_CACHE_DIR:-${TMPDIR:-/tmp}/proto-trivy-cache}" --severity HIGH,CRITICAL --format json --output target/vulnerabilities.json --exit-code 1 target/bom.json
