#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# ShadowStack Build Script
# ═══════════════════════════════════════════════════════════════════════════════
#
# Builds all Maven modules and the web dashboard.
#
# Usage:
#   ./scripts/build.sh              # Build all (skip tests)
#   ./scripts/build.sh --test       # Build all with tests
#   ./scripts/build.sh --web-only   # Build only the web dashboard
#   ./scripts/build.sh --java-only  # Build only Java modules
#   ./scripts/build.sh --clean      # Clean + build
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

MODE="${1:-all}"
START_TIME=$(date +%s)

ok() { echo -e "  ${GREEN}✓${NC} $1"; }
fail() { echo -e "  ${RED}✗${NC} $1"; }
info() { echo -e "  ${CYAN}ℹ${NC} $1"; }
header() {
    echo ""
    echo -e "${BOLD}${BLUE}── $1 ──${NC}"
    echo ""
}

elapsed() {
    local end=$(date +%s)
    local diff=$((end - START_TIME))
    echo "${diff}s"
}

# ═══════════════════════════════════════════════════════════════════════════════
# Maven Build
# ═══════════════════════════════════════════════════════════════════════════════

build_java() {
    header "Building Java Modules"

    cd "$PROJECT_ROOT"

    # Determine Maven flags
    local mvn_flags="--batch-mode"

    if [ "$MODE" = "--test" ]; then
        info "Running with tests enabled"
    else
        mvn_flags="$mvn_flags -DskipTests"
        info "Tests skipped (use --test to enable)"
    fi

    if [ "$MODE" = "--clean" ]; then
        mvn_flags="clean install $mvn_flags"
    else
        mvn_flags="install $mvn_flags"
    fi

    # Build order: parent POM first, then modules
    if [ -f "pom.xml" ]; then
        info "Building from parent POM (reactor build)..."
        echo ""
        mvn $mvn_flags 2>&1 | while IFS= read -r line; do
            case "$line" in
                *"BUILD SUCCESS"*)
                    echo -e "  ${GREEN}$line${NC}"
                    ;;
                *"BUILD FAILURE"*)
                    echo -e "  ${RED}$line${NC}"
                    ;;
                *"Building "*)
                    echo -e "  ${DIM}$line${NC}"
                    ;;
                *"ERROR"*|*"FAILURE"*)
                    echo -e "  ${RED}$line${NC}"
                    ;;
            esac
        done

        if [ "${PIPESTATUS[0]}" -eq 0 ]; then
            ok "All Maven modules built successfully"
        else
            fail "Maven build failed"
            exit 1
        fi
    else
        info "No parent POM found — building modules individually..."
        echo ""

        local MODULES=(
            "packages/language-adapters"
            "packages/core-analysis"
            "packages/refactor-engine"
            "packages/verify-engine"
            "packages/migration-corpus"
            "apps/api"
            "apps/worker"
        )

        local success=0
        local failed=0

        for module in "${MODULES[@]}"; do
            if [ -f "$PROJECT_ROOT/$module/pom.xml" ]; then
                echo -ne "  Building ${BOLD}$module${NC}..."
                if mvn $mvn_flags -f "$PROJECT_ROOT/$module/pom.xml" -q 2>&1; then
                    echo -e " ${GREEN}✓${NC}"
                    success=$((success + 1))
                else
                    echo -e " ${RED}✗${NC}"
                    failed=$((failed + 1))
                fi
            else
                echo -e "  ${DIM}Skipping $module (no pom.xml)${NC}"
            fi
        done

        echo ""
        if [ "$failed" -gt 0 ]; then
            fail "${failed} module(s) failed to build"
            exit 1
        fi
        ok "${success} module(s) built successfully"
    fi

    # Build example project
    if [ -f "$PROJECT_ROOT/examples/legacy-sample/pom.xml" ]; then
        echo ""
        info "Building example project..."
        if mvn install -DskipTests --batch-mode -q -f "$PROJECT_ROOT/examples/legacy-sample/pom.xml" 2>&1; then
            ok "Example project built"
        else
            warn "Example project build failed (non-critical)"
        fi
    fi
}

# ═══════════════════════════════════════════════════════════════════════════════
# Web Dashboard Build
# ═══════════════════════════════════════════════════════════════════════════════

build_web() {
    header "Building Web Dashboard"

    local WEB_DIR="$PROJECT_ROOT/apps/web"

    if [ ! -f "$WEB_DIR/package.json" ]; then
        warn "No package.json found at $WEB_DIR — skipping web build"
        return
    fi

    cd "$WEB_DIR"

    # Install dependencies
    info "Installing npm dependencies..."
    npm install --silent 2>&1
    ok "Dependencies installed"

    # Type check
    info "Running TypeScript type check..."
    if npx tsc --noEmit 2>&1; then
        ok "Type check passed"
    else
        warn "Type check had issues (non-blocking)"
    fi

    # Build
    info "Building Next.js application..."
    if npm run build 2>&1; then
        ok "Web dashboard built"
    else
        warn "Web build failed — check apps/web for details"
    fi

    cd "$PROJECT_ROOT"
}

# ═══════════════════════════════════════════════════════════════════════════════
# Main
# ═══════════════════════════════════════════════════════════════════════════════

echo ""
echo -e "${BOLD}${CYAN}ShadowStack Build${NC}"
echo -e "${DIM}Mode: ${MODE}${NC}"

case "$MODE" in
    --web-only)
        build_web
        ;;
    --java-only|--clean|--test)
        build_java
        ;;
    *)
        build_java
        build_web
        ;;
esac

echo ""
echo -e "${BOLD}${GREEN}Build complete in $(elapsed)${NC}"
echo ""
