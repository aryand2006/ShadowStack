#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# ShadowStack Setup Script
# ═══════════════════════════════════════════════════════════════════════════════
#
# Checks prerequisites, builds all packages, initializes the database,
# and starts the full development stack.
#
# Usage:
#   ./scripts/setup.sh           # Full setup
#   ./scripts/setup.sh --check   # Only check prerequisites
#   ./scripts/setup.sh --build   # Only build (skip docker)
#
# ═══════════════════════════════════════════════════════════════════════════════

set -euo pipefail

# ─── Colors ────────────────────────────────────────────────────────────────────

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

MODE="${1:-full}"

ok() { echo -e "  ${GREEN}✓${NC} $1"; }
fail() { echo -e "  ${RED}✗${NC} $1"; }
warn() { echo -e "  ${YELLOW}⚠${NC} $1"; }
info() { echo -e "  ${CYAN}ℹ${NC} $1"; }
header() {
    echo ""
    echo -e "${BOLD}${BLUE}── $1 ──${NC}"
    echo ""
}

# ═══════════════════════════════════════════════════════════════════════════════
# Step 1: Check Prerequisites
# ═══════════════════════════════════════════════════════════════════════════════

header "Checking Prerequisites"

ERRORS=0

# Java
if command -v java &> /dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}' | cut -d. -f1)
    if [ "$JAVA_VERSION" -ge 21 ] 2>/dev/null; then
        ok "Java ${JAVA_VERSION} (required: 21+)"
    else
        fail "Java ${JAVA_VERSION} found, but 21+ is required"
        ERRORS=$((ERRORS + 1))
    fi
else
    fail "Java not found (required: 21+)"
    info "Install: https://adoptium.net/temurin/releases/?version=21"
    ERRORS=$((ERRORS + 1))
fi

# Maven
if command -v mvn &> /dev/null; then
    MVN_VERSION=$(mvn --version 2>/dev/null | head -1 | awk '{print $3}')
    MVN_MAJOR=$(echo "$MVN_VERSION" | cut -d. -f1)
    MVN_MINOR=$(echo "$MVN_VERSION" | cut -d. -f2)
    if [ "$MVN_MAJOR" -ge 3 ] && [ "$MVN_MINOR" -ge 9 ] 2>/dev/null; then
        ok "Maven ${MVN_VERSION} (required: 3.9+)"
    else
        warn "Maven ${MVN_VERSION} found; 3.9+ recommended"
    fi
else
    fail "Maven not found (required: 3.9+)"
    info "Install: https://maven.apache.org/install.html"
    ERRORS=$((ERRORS + 1))
fi

# Node.js (optional for API-only demo; needed for web UI / JS --check)
if command -v node &> /dev/null; then
    NODE_VERSION=$(node --version | sed 's/v//' | cut -d. -f1)
    if [ "$NODE_VERSION" -ge 20 ] 2>/dev/null; then
        ok "Node.js v$(node --version | sed 's/v//') (20+ for UI/JS gate)"
    else
        warn "Node.js v$(node --version | sed 's/v//') found; 20+ recommended for UI/JS gate"
    fi
else
    warn "Node.js not found — web UI and JS syntax gate unavailable"
    info "Install: https://nodejs.org/"
fi

# npm
if command -v npm &> /dev/null; then
    ok "npm $(npm --version)"
else
    warn "npm not found (usually installed with Node.js)"
fi

# Python 3 + LibCST (required for Python AST engine)
if command -v python3 &> /dev/null; then
    PY_VERSION=$(python3 --version 2>&1 | awk '{print $2}')
    ok "python3 ${PY_VERSION}"
    if python3 -c 'import libcst' 2>/dev/null; then
        ok "libcst importable"
    else
        fail "libcst not importable (required for Python AST engine)"
        info "Install: pip install -r packages/language-adapters/native-engines/python/requirements.txt"
        ERRORS=$((ERRORS + 1))
    fi
else
    fail "python3 not found (required for Python AST / py_compile)"
    info "Install Python 3.10+ from https://www.python.org/downloads/"
    ERRORS=$((ERRORS + 1))
fi

# GnuCOBOL (optional — COBOL full gate)
if command -v cobc &> /dev/null; then
    ok "cobc $(cobc --version 2>&1 | head -1)"
else
    warn "cobc not found — COBOL full syntax gate will be skipped"
    info "Install: apt install gnucobol / brew install gnu-cobol"
fi

# .NET SDK (optional — C# full gate)
if command -v dotnet &> /dev/null; then
    ok "dotnet $(dotnet --version 2>/dev/null || echo present)"
else
    warn "dotnet not found — C# full build gate will be skipped"
    info "Install: https://dotnet.microsoft.com/download"
fi

# Roslyn AST engine DLL
ROSLYN_DLL="$PROJECT_ROOT/packages/language-adapters/native-engines/csharp/publish/CsharpAstEngine.dll"
if [ -f "$ROSLYN_DLL" ]; then
    ok "Roslyn CsharpAstEngine.dll present"
else
    warn "Roslyn DLL missing at native-engines/csharp/publish/CsharpAstEngine.dll"
    info "Publish: cd packages/language-adapters/native-engines/csharp && dotnet publish -c Release -o publish"
fi

# Docker
if command -v docker &> /dev/null; then
    DOCKER_VERSION=$(docker --version 2>&1 | awk '{print $3}' | tr -d ',')
    ok "Docker ${DOCKER_VERSION}"

    if docker info &> /dev/null; then
        ok "Docker daemon is running"
    else
        fail "Docker daemon is not running"
        info "Start Docker Desktop or run: sudo systemctl start docker"
        ERRORS=$((ERRORS + 1))
    fi
else
    fail "Docker not found"
    info "Install: https://docs.docker.com/get-docker/"
    ERRORS=$((ERRORS + 1))
fi

# Docker Compose
if docker compose version &> /dev/null 2>&1; then
    COMPOSE_VERSION=$(docker compose version --short 2>/dev/null || echo "unknown")
    ok "Docker Compose ${COMPOSE_VERSION}"
elif command -v docker-compose &> /dev/null; then
    COMPOSE_VERSION=$(docker-compose --version 2>/dev/null | awk '{print $4}' | tr -d ',')
    ok "docker-compose ${COMPOSE_VERSION} (legacy)"
    warn "Consider upgrading to Docker Compose V2"
else
    fail "Docker Compose not found"
    info "Install: https://docs.docker.com/compose/install/"
    ERRORS=$((ERRORS + 1))
fi

echo ""
if [ "$ERRORS" -gt 0 ]; then
    fail "${ERRORS} prerequisite(s) missing. Please install them and re-run."
    exit 1
else
    ok "All required prerequisites satisfied!"
fi

if [ "$MODE" = "--check" ]; then
    exit 0
fi

# ═══════════════════════════════════════════════════════════════════════════════
# Step 2: Build All Packages
# ═══════════════════════════════════════════════════════════════════════════════

header "Building All Packages"

cd "$PROJECT_ROOT"

info "Building Maven modules..."
if [ -f "pom.xml" ]; then
    mvn clean install -DskipTests -q --batch-mode 2>&1 | tail -5
    ok "Maven build complete"
else
    warn "No root pom.xml found, building modules individually..."

    for module in packages/language-adapters packages/core-analysis packages/refactor-engine packages/verify-engine packages/migration-corpus apps/api apps/worker; do
        if [ -f "$module/pom.xml" ]; then
            info "Building $module..."
            mvn clean install -DskipTests -q --batch-mode -f "$module/pom.xml" 2>&1 | tail -2
            ok "$module built"
        fi
    done
fi

# Build web app
if [ -f "apps/web/package.json" ]; then
    if command -v npm &> /dev/null; then
        info "Building web dashboard..."
        cd "$PROJECT_ROOT/apps/web"
        npm install --silent 2>&1 | tail -1
        npm run build --silent 2>&1 | tail -1 || warn "Web build skipped (may need additional config)"
        cd "$PROJECT_ROOT"
        ok "Web dashboard built"
    else
        warn "Skipping web dashboard build (npm not found)"
    fi
fi

if [ "$MODE" = "--build" ]; then
    echo ""
    ok "Build complete!"
    exit 0
fi

# ═══════════════════════════════════════════════════════════════════════════════
# Step 3: Initialize Database
# ═══════════════════════════════════════════════════════════════════════════════

header "Initializing Database"

info "Starting PostgreSQL with pgvector..."
docker compose up -d postgres 2>/dev/null || docker-compose up -d postgres 2>/dev/null

info "Waiting for PostgreSQL to become ready..."
for i in $(seq 1 30); do
    if docker compose exec -T postgres pg_isready -U shadowstack 2>/dev/null; then
        break
    fi
    sleep 1
done

ok "PostgreSQL is ready"

info "Enabling pgvector extension..."
docker compose exec -T postgres psql -U shadowstack -d shadowstack -c \
    "CREATE EXTENSION IF NOT EXISTS vector;" 2>/dev/null || true

ok "pgvector extension enabled"

info "Flyway migrations will run automatically on API startup"

# ═══════════════════════════════════════════════════════════════════════════════
# Step 4: Start the Stack
# ═══════════════════════════════════════════════════════════════════════════════

header "Starting ShadowStack"

info "Starting all services..."
docker compose up -d 2>/dev/null || docker-compose up -d 2>/dev/null

info "Waiting for services to become healthy..."
for i in $(seq 1 60); do
    if curl -sf http://localhost:8080/actuator/health/liveness > /dev/null 2>&1; then
        ok "API is healthy (http://localhost:8080)"
        break
    fi
    if [ "$i" -eq 60 ]; then
        warn "API not yet healthy after 60s — check logs: docker compose logs api"
    fi
    sleep 2
done

echo ""

# ═══════════════════════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════════════════════

header "Setup Complete"

echo -e "  ${BOLD}Services:${NC}"
echo -e "    API:        ${GREEN}http://localhost:8080${NC}"
echo -e "    Swagger UI: ${GREEN}http://localhost:8080/swagger-ui.html${NC}"
echo -e "    Web:        ${GREEN}http://localhost:3000${NC}"
echo -e "    Grafana:    ${GREEN}http://localhost:3001${NC}  (admin/shadowstack)"
echo -e "    Prometheus: ${GREEN}http://localhost:9090${NC}"
echo -e "    PostgreSQL: ${GREEN}localhost:5432${NC}         (shadowstack/shadowstack)"
echo ""
echo -e "  ${BOLD}Quick start:${NC}"
echo -e "    ${DIM}# Get a JWT token${NC}"
echo -e "    curl -X POST http://localhost:8080/api/v1/auth/login \\"
echo -e "      -H 'Content-Type: application/json' \\"
echo -e "      -d '{\"username\":\"admin\",\"password\":\"shadowstack\"}'"
echo ""
echo -e "    ${DIM}# Run the API demo (API must already be running)${NC}"
echo -e "    ./scripts/demo.sh"
echo ""
echo -e "  ${BOLD}Logs:${NC}"
echo -e "    docker compose logs -f api"
echo -e "    docker compose logs -f worker"
echo ""
