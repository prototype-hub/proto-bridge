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
python3 tools/verify-identifiers.py
python3 tools/test_generate_menu_resource.py
python3 tools/test_collect_third_party_licenses.py
python3 tools/test_export_source.py
./mvnw -B -ntp "$@" clean verify -Prelease-audit
python3 tools/verify-distribution.py
python3 tools/check-coverage.py
command -v gitleaks >/dev/null || { echo "Install pinned gitleaks using tools/install-security-tools.sh" >&2; exit 2; }
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
