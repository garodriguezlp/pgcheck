#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUPPORT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$SUPPORT_DIR"

echo "Starting pgcheck support environment..."
docker compose up -d --wait

echo ""
echo "PostgreSQL is ready."
echo ""
echo "  Connection string : jdbc:postgresql://localhost:5432/pgcheck_demo"
echo "  Username          : pgcheck"
echo "  Password          : pgcheck"
echo ""
echo "Quick test:"
echo "  jbang pgcheck.java --url jdbc:postgresql://localhost:5432/pgcheck_demo --username pgcheck --password pgcheck --sql \"SELECT count(*) FROM store.customers\""
echo ""
echo "Or set ~/.pgcheck.properties:"
echo "  url=jdbc:postgresql://localhost:5432/pgcheck_demo"
echo "  username=pgcheck"
echo "  password=pgcheck"
