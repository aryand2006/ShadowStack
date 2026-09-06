#!/usr/bin/env bash
# Idempotent Cloud Agent bootstrap for ShadowStack.
# Prepares the "honest demo path" that runs without Docker/Postgres:
#   - Spring Boot API (demo profile, in-memory seeded review queue)
#   - Next.js web dashboard
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# The default base image ships Java 21, Node, and Python but not Maven.
if ! command -v mvn >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq maven
fi

# Build the Spring Boot API and the engine packages it depends on.
# The demo profile needs no database, so skipping tests keeps install fast and deterministic.
mvn -pl apps/api -am package -DskipTests --batch-mode

# Web dashboard dependencies and local environment file.
cd apps/web
npm install
cp -n .env.local.example .env.local 2>/dev/null || true
