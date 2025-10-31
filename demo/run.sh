#!/usr/bin/env bash

# Record demo:
#   asciinema rec docs/app/demo.cast --overwrite
# Generate demo gif:
#   agg -v --idle-time-limit 1 docs/app/demo.cast docs/app/demo.gif

if [ $# -gt 0 ]; then
    echo 'Demonstrate a client connecting to Neo4j through graph-guard.'
    echo ''
    echo 'Usage:'
    echo '  ./run.sh'
    exit 0
fi

SCRIPT_DIR="$(cd -- "$(dirname "$0")" >/dev/null 2>&1; pwd -P)"
PROJECT_DIR=$(dirname "$SCRIPT_DIR")

# Extract triple-quoted string content from a Kotlin file
# Usage: extract <source_file> <output_file> <description>
extract() {
  local source_file="$1"
  local output_file="$2"
  local description="$3"

  echo "Extracting $description from $(basename "$source_file")..."
  python3 -c "import pathlib, re; print(re.search(r'\"{3}([\s\S]*?)\"{3}', pathlib.Path('$source_file').read_text(), re.RegexFlag.MULTILINE)[1])" \
    > "$output_file"
}

extract \
  "$PROJECT_DIR/graph-guard/src/testFixtures/kotlin/io/github/cfraser/graphguard/knit/Example03.kt" \
  "$SCRIPT_DIR/schema.gg" \
  "schema"

extract \
  "$PROJECT_DIR/graph-guard/src/testFixtures/kotlin/io/github/cfraser/graphguard/knit/Example09.kt" \
  "$SCRIPT_DIR/plugin.gg.kts" \
  "plugin script"

echo 'Building graph-guard-app Docker image...'
cd "$PROJECT_DIR" || exit 1
./gradlew graph-guard-app:jibDockerBuild --image=graph-guard-app:latest

cd "$SCRIPT_DIR" || exit 1
docker compose down -v --remove-orphans
echo ''
echo 'Starting services with Docker Compose...'
echo ''
echo '  graph-guard-app:  http://localhost:8080'
echo ''
echo 'Connect to bolt://localhost:8787 with neo4j/password'
echo ''
echo 'Press Ctrl+C to stop all services...'
docker compose up --build
