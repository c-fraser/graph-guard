#!/usr/bin/env bash

# Record demo:
#   asciinema rec docs/cli/demo.cast --overwrite
# Generate demo gif:
#   agg -v --idle-time-limit 1 docs/cli/demo.cast docs/cli/demo.gif

if [ $# -gt 0 ]; then
    echo 'Execute queries proxied through the CLI application to a Neo4J container.'
    echo ''
    echo 'Usage:'
    echo '  ./demo.sh'
    exit 0
fi

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname "$0")" >/dev/null 2>&1; pwd -P)"
PROJECT_DIR=$(dirname "$SCRIPT_DIR")

function cleanup()
{
    CONTAINER_ID=$(cat "$SCRIPT_DIR/neo4j.cid")
    docker rm -f "$CONTAINER_ID" > /dev/null 2>&1
}

trap cleanup EXIT

docker run -d -p 7687:7687 --env NEO4J_AUTH=neo4j/password neo4j:latest > "$SCRIPT_DIR/neo4j.cid"
cd "$PROJECT_DIR"
sleep 10 && \
  ./gradlew graph-guard-cli:test --tests 'io.github.cfraser.graphguard.cli.E2ETest' \
    -Dkotest.tags='Local' \
    -Dgraph-guard.e2e.test='true' \
    --rerun \
  > /dev/null 2>&1 &
./gradlew graph-guard-cli:clean graph-guard-cli:installShadowDist > /dev/null 2>&1
python3 -c "import pathlib, re; print(re.search(r'\"{3}([\s\S]*?)\"{3}', pathlib.Path('$PROJECT_DIR/graph-guard/src/testFixtures/kotlin/io/github/cfraser/graphguard/knit/Example03.kt').read_text(), re.RegexFlag.MULTILINE)[1])" \
  | timeout 15 ./graph-guard-cli/build/install/graph-guard-cli-shadow/bin/graph-guard-cli --styled -s -
