#!/usr/bin/env bash
# Run from a reviewed source tree. Requires Java 21, Python 3, Docker for integration
# tests, and the security-tool versions documented in install-security-tools.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
# CycloneDX requires Maven online mode; skipping tests is never a release gate.
for argument in "$@"; do
  case "$argument" in
    -U|--update-snapshots) ;;
    -o|--offline)
      echo "The release gate requires Maven online mode for CycloneDX. Use ./mvnw -o verify for development." >&2
      exit 2 ;;
    *) echo "Supported flag: --update-snapshots" >&2; exit 2 ;;
  esac
done
# Reject unavailable or incompatible scanners before an expensive Maven build.
# Preflight also revokes a previous run's report; it does not scan dependencies.
python3 tools/check-dependency-vulnerabilities.py --preflight
python3 tools/verify-identifiers.py
python3 tools/test_generate_menu_resource.py
python3 tools/test_collect_third_party_licenses.py
python3 tools/test_export_source.py
python3 tools/test_audit_storage.py
# Always exercise the packaged-runtime verifier's ownership, cleanup and report
# boundaries. Optional real Playwright lifecycle probes require the documented
# PROTO_RUNTIME_PLAYWRIGHT_DIR installation; unittest reports those skips.
python3 tools/test_verify_runtime.py
python3 tools/test_runtime_mailbox.py
python3 tools/test_security_gates.py
./mvnw -B -ntp "$@" clean verify -Prelease-audit
python3 tools/verify-distribution.py
python3 tools/check-coverage.py
python3 tools/security_tools.py --check gitleaks
# Scan exactly the proposed public source, retaining local secrets and runtime data
# in the working tree. The export script also rejects broken public documentation links.
audit_temp=$(mktemp -d "${TMPDIR:-/tmp}/proto-public-check.XXXXXX")
trap 'rm -rf "$audit_temp"' EXIT
python3 tools/export-source.py --output "$audit_temp/source"
gitleaks dir "$audit_temp/source" --redact --no-banner
if [[ -e .git ]]; then
  gitleaks git . --redact --no-banner --log-opts="--all"
else
  echo "No Git metadata: history secret scan was not performed."
fi
bash tools/check-dependency-vulnerabilities.sh
echo "Build, license collection/integrity, coverage and automated security gates passed."
echo "Before binary release, review per-component obligations and contribution rights in NOTICE and legal inventory."
