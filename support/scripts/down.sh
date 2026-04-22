#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUPPORT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$SUPPORT_DIR"

echo "Stopping pgcheck support environment..."
docker-compose down --volumes --remove-orphans

echo ""
echo "Environment stopped and volumes removed."
