#!/usr/bin/env bash

if [ $# -eq 0 ]; then
    echo 'Execute a command in the containerized development environment.'
    echo ''
    echo 'Examples:'
    echo '  Start interactive shell:'
    echo '    ./exec.sh zsh'
    echo '  Run tests:'
    echo '    ./exec.sh zsh ./gradlew clean test'
    exit 0
fi

set -euo pipefail

DEVCONTAINER_DIR=$(dirname "$0")
WORKSPACE_DIR=$(dirname "$DEVCONTAINER_DIR")

function cleanup()
{
    CONTAINER_ID=$(npx fx .containerId "$DEVCONTAINER_DIR/up.json")
    docker rm -f "$CONTAINER_ID" > /dev/null 2>&1
}

trap cleanup EXIT

npx @devcontainers/cli up \
  --workspace-folder "$WORKSPACE_DIR" \
  --remove-existing-container 'true' \
  | npx fx . \
  > "$DEVCONTAINER_DIR/up.json"
npx @devcontainers/cli exec --workspace-folder "$WORKSPACE_DIR" "$@"
