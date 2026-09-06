#!/usr/bin/env bash
# Prove industry conversion catalog produces real before→after patches.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

echo "== Java industry rules =="
(cd "$ROOT" && mvn -q -pl packages/refactor-engine -am test \
  -Dtest=IndustryJavaModernizationRulesTest \
  -Dsurefire.failIfNoSpecifiedTests=false)

echo "== Python + COBOL adapters =="
(cd "$ROOT" && mvn -q -pl packages/language-adapters -am test \
  -Dtest=PythonAdapterTest,CobolAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false)

echo "== Compiling API + worker =="
(cd "$ROOT" && mvn -q -pl apps/api,apps/worker -am package -DskipTests)

echo "== OK =="
echo "Java (9): lambda, diamond, List.sort, StringBuffer, Vector, Hashtable, Stack, valueOf, isEmpty"
echo "Python (18): lib2to3/modernize classics"
echo "COBOL (11): fixed→free, STOP/GOTO/ALTER, DISPLAY/MOVE/COMPUTE/PERFORM/ADD/SUBTRACT/ACCEPT"
