# pgcheck

One-shot PostgreSQL executor for LLM agents. Supply SQL, get structured JSON back, done.

LLM agents need to inspect databases — checking row counts, validating schema, confirming data after migrations. Standard PSQL clients are interactive and ANSI-decorated. pgcheck takes SQL, executes it, and writes a predictable JSON envelope to stdout that agents can parse directly. JBang turns a single `.java` file into a runnable script — `jbang pgcheck.java --sql "..."` just works, no build step required.

## Prerequisites

- [JBang](https://www.jbang.dev/download/) — `sdk install jbang` or `brew install jbangdev/tap/jbang`
- Java 17+ (JBang will download one automatically if not present)
- A running PostgreSQL instance
- [Docker](https://docs.docker.com/get-docker/) — only for the local support environment

## Quick start

**1. Configure the connection** (optional — hardcoded defaults match the local dev environment):

```sh
cp .pgcheck.properties.example ~/.pgcheck.properties
# Edit with your connection details
```

**2. Run a query:**

```sh
jbang pgcheck.java --sql "SELECT count(*) FROM information_schema.tables"
```

Output:

```json
{
  "status": "ok",
  "statement_type": "SELECT",
  "row_count": 1,
  "truncated": false,
  "columns": [{ "name": "count", "type": "int8" }],
  "rows": [{ "count": 67 }],
  "duration_ms": 14
}
```

## `~/.pgcheck.properties` reference

| Key | Default | Description |
|-----|---------|-------------|
| `url` | `jdbc:postgresql://localhost:5432/pgcheck_demo` | JDBC connection URL |
| `username` | `pgcheck` | Database username |
| `password` | `pgcheck` | Database password |
| `allow-writes` | `false` | Allow DML (INSERT/UPDATE/DELETE). DDL always blocked. |
| `max-rows` | `100` | Maximum rows returned for SELECT queries |
| `timeout` | `30` | Query timeout in seconds |

### Operator vs. agent options

| Option | Who sets it | Notes |
|--------|-------------|-------|
| `url`, `username`, `password` | Operator (properties file) | Never pass via CLI from an agent |
| `allow-writes` | Operator (properties file) | Policy decision; agent must not override |
| `--sql`, `--file`, `--stdin` | Agent (CLI) | Primary input — exactly one required |
| `--max-rows`, `--timeout` | Agent (CLI) | Safe to tune per query |

## CLI usage

```
Usage: pgcheck [-hVv] [--stdin] [--allow-writes] [--file=<file>]
               [--max-rows=<maxRows>] [--password=<password>]
               [--sql=<sql>] [--timeout=<timeout>] [--url=<url>]
               [--username=<username>]

  -v, --verbose         Enable verbose/debug logging to stderr
      --sql=<sql>       SQL statement to execute (inline)
      --file=<file>     Path to a .sql file to execute
      --stdin           Read SQL from standard input
      --max-rows=<maxRows>
                        Maximum rows returned for SELECT queries (default: 100)
      --timeout=<timeout>
                        Query timeout in seconds (default: 30)
      --allow-writes    Allow DML write operations; DDL always blocked (operator-managed)
      --url=<url>       JDBC connection URL (operator-managed)
      --username=<username>
                        Database username (operator-managed)
      --password=<password>
                        Database password (operator-managed)
  -h, --help            Show this help message and exit.
  -V, --version         Print version information and exit.
```

## JSON output

### Successful SELECT

```json
{
  "status": "ok",
  "statement_type": "SELECT",
  "row_count": 3,
  "truncated": false,
  "columns": [
    { "name": "id",    "type": "int4"    },
    { "name": "email", "type": "varchar" }
  ],
  "rows": [
    { "id": 1, "email": "alice@example.com" },
    { "id": 2, "email": "bob@example.com"   }
  ],
  "duration_ms": 12
}
```

### Successful DML

```json
{ "status": "ok", "statement_type": "UPDATE", "rows_affected": 5, "duration_ms": 8 }
```

### Errors

```json
{ "status": "error", "error_type": "policy_violation", "message": "Write operations are disabled...", "statement_type": "UPDATE" }
{ "status": "error", "error_type": "sql_error", "message": "ERROR: column \"x\" does not exist", "sql_state": "42703", "duration_ms": 3 }
{ "status": "error", "error_type": "connection_error", "message": "Connection refused to localhost:5432" }
{ "status": "error", "error_type": "input_error", "message": "SQL input is empty" }
```

## Exit codes

| Code | Meaning |
|------|---------|
| `0` | SQL executed successfully |
| `1` | SQL execution error |
| `2` | Policy violation (write or DDL blocked) |
| `3` | Input error |
| `4` | Connection error |

Agents can branch on `$?` to distinguish infrastructure problems (`4`) from query errors (`1`) from policy (`2`).

## Mutation policy

pgcheck defaults to **read-only**. `SELECT`, `WITH`, `TABLE`, `VALUES`, `SHOW`, and `EXPLAIN` are allowed by default.

To enable writes, add to `~/.pgcheck.properties`:

```properties
allow-writes=true
```

**DDL is always blocked** regardless of `allow-writes`. `CREATE`, `DROP`, `ALTER`, `TRUNCATE`, `RENAME`, and `COMMENT` are rejected before execution.

## Local development environment

```sh
# Start PostgreSQL 16 via Docker Compose
bash support/scripts/up.sh

# Run a query (no properties file needed — defaults match the local environment)
jbang pgcheck.java --sql "SELECT 1"

# Stop and remove volumes
bash support/scripts/down.sh
```

The `store` schema has two tables (`customers`, `orders`) with seed data for testing.

## JBang catalog

`jbang-catalog.json` defines a local alias so you can run `jbang pgcheck` from the repo root, or `jbang pgcheck@garodriguezlp/pgcheck` remotely once the repo is public.

## Agent skill setup

- **Claude Code** — `.claude/skills/pgcheck.md` is auto-discovered when working in this repo.
- **GitHub Copilot** — `.github/copilot-instructions.md` carries the skill content.
- **Other agents** — copy or reference `agent-skill.md` from the repo root.
