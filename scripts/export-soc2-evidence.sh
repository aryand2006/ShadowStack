#!/usr/bin/env bash
# Export SOC 2 control-readiness evidence for auditor PBC packages.
#
# Requires ADMIN credentials against a running API (prod/docker preferred;
# demo returns the static catalog with empty registeredEvidence).
#
# Usage:
#   export API_URL=http://localhost:8080/api/v1
#   export ADMIN_USER=admin ADMIN_PASS='…'          # Basic auth
#   # optional: export AUTH_HEADER="Bearer <jwt>"   # overrides Basic
#   # optional: export OUT_DIR=./soc2-evidence-export
#   # optional: export FROM=2026-01-01T00:00:00Z TO=2026-04-01T00:00:00Z
#   ./scripts/export-soc2-evidence.sh
#
# If API_URL / credentials are unset, prints the curl commands and exits 0
# (safe for docs / dry-run in CI without a live API).
set -euo pipefail

OUT_DIR="${OUT_DIR:-./soc2-evidence-export}"
API_URL="${API_URL:-}"
ADMIN_USER="${ADMIN_USER:-}"
ADMIN_PASS="${ADMIN_PASS:-}"
AUTH_HEADER="${AUTH_HEADER:-}"
FROM="${FROM:-}"
TO="${TO:-}"

EVIDENCE_PATH="/compliance/evidence"
AUDIT_EXPORT_PATH="/audit/export"

print_commands() {
  cat <<EOF
# SOC 2 evidence export — documented commands
#
# 1) Control evidence (JSON): catalog + ss_control_evidence
curl -sS -u "\${ADMIN_USER}:\${ADMIN_PASS}" \\
  -H "Accept: application/json" \\
  "\${API_URL}${EVIDENCE_PATH}" \\
  -o evidence.json

# Or with JWT:
curl -sS -H "Authorization: Bearer \${TOKEN}" \\
  -H "Accept: application/json" \\
  "\${API_URL}${EVIDENCE_PATH}" \\
  -o evidence.json

# 2) Audit log CSV export (optional from/to ISO-8601)
curl -sS -u "\${ADMIN_USER}:\${ADMIN_PASS}" \\
  -H "Accept: text/csv" \\
  "\${API_URL}${AUDIT_EXPORT_PATH}?from=\${FROM}&to=\${TO}" \\
  -o audit-log.csv

# See also: docs/soc2-auditor-pack.md , docs/soc2-controls.md
EOF
}

if [[ -z "${API_URL}" ]]; then
  echo "API_URL not set — documenting curl commands (no live export)." >&2
  print_commands
  exit 0
fi

if [[ -z "${AUTH_HEADER}" && ( -z "${ADMIN_USER}" || -z "${ADMIN_PASS}" ) ]]; then
  echo "Credentials not set (ADMIN_USER/ADMIN_PASS or AUTH_HEADER) — documenting curl commands." >&2
  print_commands
  exit 0
fi

command -v curl >/dev/null || { echo "curl required" >&2; exit 1; }

mkdir -p "${OUT_DIR}"

auth_args=()
if [[ -n "${AUTH_HEADER}" ]]; then
  auth_args=(-H "Authorization: ${AUTH_HEADER}")
else
  auth_args=(-u "${ADMIN_USER}:${ADMIN_PASS}")
fi

echo "Exporting compliance evidence → ${OUT_DIR}/evidence.json"
curl -sS -f "${auth_args[@]}" \
  -H "Accept: application/json" \
  "${API_URL}${EVIDENCE_PATH}" \
  -o "${OUT_DIR}/evidence.json"

audit_qs=""
if [[ -n "${FROM}" ]]; then
  audit_qs="from=${FROM}"
fi
if [[ -n "${TO}" ]]; then
  if [[ -n "${audit_qs}" ]]; then
    audit_qs="${audit_qs}&to=${TO}"
  else
    audit_qs="to=${TO}"
  fi
fi
AUDIT_URL="${API_URL}${AUDIT_EXPORT_PATH}"
if [[ -n "${audit_qs}" ]]; then
  AUDIT_URL="${AUDIT_URL}?${audit_qs}"
fi

echo "Exporting audit CSV → ${OUT_DIR}/audit-log.csv"
curl -sS -f "${auth_args[@]}" \
  -H "Accept: text/csv" \
  "${AUDIT_URL}" \
  -o "${OUT_DIR}/audit-log.csv"

# Lightweight manifest for the auditor package
{
  echo "{"
  echo "  \"exportedAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
  echo "  \"apiUrl\": \"${API_URL}\","
  echo "  \"files\": [\"evidence.json\", \"audit-log.csv\"],"
  echo "  \"docs\": [\"docs/soc2-auditor-pack.md\", \"docs/soc2-controls.md\"]"
  echo "}"
} > "${OUT_DIR}/manifest.json"

echo "Done. Package: ${OUT_DIR}/ (evidence.json, audit-log.csv, manifest.json)"
