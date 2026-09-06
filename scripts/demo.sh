#!/usr/bin/env bash
# Fail-closed ShadowStack company demo against the live API.
# Start API first:
#   java -jar apps/api/target/shadowstack-api-*.jar --spring.profiles.active=demo
set -euo pipefail

API_URL="${API_URL:-http://localhost:8080/api/v1}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin}"

RED='\033[0;31m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
step_num=0
step() { step_num=$((step_num+1)); echo -e "\n${BOLD}${BLUE}══ Step ${step_num}: $1${NC}"; }
ok() { echo -e "${GREEN}  ✓ $1${NC}"; }
info() { echo -e "${CYAN}  • $1${NC}"; }
die() { echo -e "${RED}  ✗ $1${NC}" >&2; exit 1; }

command -v curl >/dev/null || die "curl required"
command -v python3 >/dev/null || die "python3 required"

api() {
  local method="$1" path="$2" data="${3:-}"
  local args=(-sS -f -u "${ADMIN_USER}:${ADMIN_PASS}" -H "Content-Type: application/json" -X "$method")
  [[ -n "$data" ]] && args+=(-d "$data")
  curl "${args[@]}" "${API_URL}${path}"
}

echo -e "${BOLD}ShadowStack live demo (fail-closed)${NC}"
echo "API: ${API_URL}  user: ${ADMIN_USER}"

step "Health check"
curl -sS -f "${API_URL%/api/v1}/actuator/health" >/dev/null \
  || die "API not healthy — start with --spring.profiles.active=demo"
ok "API is up"

step "Optional JWT login"
if LOGIN_JSON=$(curl -sS -f -H "Content-Type: application/json" \
  -d "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASS}\"}" \
  "${API_URL}/auth/login" 2>/dev/null); then
  TOKEN=$(python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("accessToken") or d.get("token") or "")' <<<"$LOGIN_JSON")
  if [[ -n "$TOKEN" ]]; then
    ok "JWT login works"
  else
    info "Login returned no token — continuing with HTTP Basic"
  fi
else
  info "No /auth/login endpoint (or it failed) — using HTTP Basic"
fi

step "List projects"
PROJECTS=$(api GET /projects) || die "GET /projects failed"
PROJECT_COUNT=$(python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' <<<"$PROJECTS")
[[ "$PROJECT_COUNT" -gt 0 ]] || die "No projects — DemoBootstrap did not seed examples/legacy-sample"
PROJECT_ID=$(python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["id"])' <<<"$PROJECTS")
ok "${PROJECT_COUNT} project(s); using ${PROJECT_ID}"

step "Review queue"
QUEUE=$(api GET /reviews/queue) || die "GET /reviews/queue failed"
QUEUE_COUNT=$(python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' <<<"$QUEUE")
[[ "$QUEUE_COUNT" -gt 0 ]] || die "Empty review queue — nothing verified into PENDING_REVIEW"
PATCH_ID=$(python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["patchId"])' <<<"$QUEUE")
RULE=$(python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["ruleName"])' <<<"$QUEUE")
ok "${QUEUE_COUNT} pending patches (showing ${RULE})"

step "Patch detail + real diff"
DETAIL=$(api GET "/reviews/${PATCH_ID}") || die "GET /reviews/${PATCH_ID} failed"
python3 - "$DETAIL" <<'PY'
import json,sys
d=json.loads(sys.argv[1])
diff=d.get("unifiedDiff") or ""
if not diff.strip():
    raise SystemExit("unifiedDiff empty — refusing fake demo data")
print(f"  rule={d['ruleName']} status={d['status']} diff_bytes={len(diff)}")
print("  --- diff preview ---")
print("\n".join(diff.splitlines()[:18]))
PY
ok "Live unifiedDiff returned"

step "Accept patch"
ACCEPTED=$(api POST "/reviews/${PATCH_ID}/accept") || die "Accept failed"
python3 -c 'import json,sys; d=json.load(sys.stdin); assert d.get("status")=="ACCEPTED", d; print("  status=ACCEPTED")' <<<"$ACCEPTED"
ok "Accept persisted"

step "Queue shrinks"
QUEUE2=$(api GET /reviews/queue) || die "Queue reload failed"
COUNT2=$(python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' <<<"$QUEUE2")
info "Pending now: ${COUNT2} (was ${QUEUE_COUNT})"
[[ "$COUNT2" -lt "$QUEUE_COUNT" ]] || die "Accepted patch still in queue"
ok "Demo passed — every step used the real API"

echo -e "\n${BOLD}${GREEN}Pitch-ready:${NC} UI at http://localhost:3000/queue  |  API :8080 demo profile"
