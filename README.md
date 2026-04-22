# pgcheck

One-shot PostgreSQL executor for LLM agents. Supply SQL, get structured JSON back, done.

## Why this exists

LLM agents need to inspect databases — checking row counts, validating schema, confirming data after migrations. Standard PSQL clients are interactive and ANSI-decorated. pgcheck is neither: it takes SQL, executes it, and writes a predictable JSON envelope to stdout. The agent can parse that directly.

**Why JBang + Java 17 + picocli?**

JBang turns a single `.java` file into a runnable script with no build step — `jbang pgcheck.java --sql "..."` just works. Picocli gives clean CLI ergonomics, `--help`, and a properties-file default provider. The PostgreSQL JDBC driver handles connection and type mapping reliably. The result is a single file you can audit, copy, and run anywhere Java is available.

**Why one-shot?**

An agent calling a tool doesn't need connection pooling or session state. One call, one result, one exit code. JDBC auto-commit makes this natural and safe.

## Prerequisites

- [JBang](https://www.jbang.dev/download/) — `sdk install jbang` or `brew install jbangdev/tap/jbang`
- Java 17+ (JBang will download one automatically if not present)
- A running PostgreSQL instance
- [Docker](https://docs.docker.com/get-docker/) — only for the local support environment

## Quick start

**1. Configure the connection:**

```sh
cp .pgcheck.properties.example ~/.pgcheck.properties
# Edit ~/.pgcheck.properties with your connection details
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

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `url` | string | `jdbc:postgresql://localhost:5432/postgres` | JDBC connection URL |
| `username` | string | `postgres` | Database username |
| `password` | string | `` | Database password |
| `allow-writes` | boolean | `false` | Allow DML (INSERT/UPDATE/DELETE). DDL always blocked. |
| `max-rows` | integer | `100` | Maximum rows returned for SELECT queries |
| `timeout` | integer | `30` | Query timeout in seconds |

### Operator-only vs. LLM-safe options

| Option | Who sets it | Notes |
|--------|-------------|-------|
| `url`, `username`, `password` | Operator (properties file) | Never pass via CLI from an agent |
| `allow-writes` | Operator (properties file) | Policy decision; agent must not override |
| `--sql`, `--file`, `--stdin` | Agent (CLI) | Primary input |
| `--max-rows`, `--timeout` | Agent (CLI) | Safe to tune per query |

## CLI usage

```
Usage: pgcheck [-hV] [--stdin] [--allow-writes] [--file=<file>]
               [--max-rows=<maxRows>] [--password=<password>]
               [--sql=<sql>] [--timeout=<timeout>] [--url=<url>]
               [--username=<username>]

One-shot PostgreSQL executor for LLM agents

      --allow-writes    Allow DML write operations; DDL always blocked
                          (operator-managed)
      --file=<file>     Path to a .sql file to execute
  -h, --help            Show this help message and exit.
      --max-rows=<maxRows>
                        Maximum rows returned for SELECT queries (default: 100)
      --password=<password>
                        Database password (operator-managed)
      --sql=<sql>       SQL statement to execute (inline)
      --stdin           Read SQL from standard input
      --timeout=<timeout>
                        Query timeout in seconds (default: 30)
      --url=<url>       JDBC connection URL (operator-managed)
      --username=<username>
                        Database username (operator-managed)
  -V, --version         Print version information and exit.
```

## JSON output examples

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
    { "id": 2, "email": "bob@example.com"   },
    { "id": 3, "email": "carol@example.com" }
  ],
  "duration_ms": 12
}
```

### Successful DML

```json
{
  "status": "ok",
  "statement_type": "UPDATE",
  "rows_affected": 5,
  "duration_ms": 8
}
```

### Policy violation

```json
{
  "status": "error",
  "error_type": "policy_violation",
  "message": "Write operations are disabled. Set allow-writes=true in ~/.pgcheck.properties to enable.",
  "statement_type": "UPDATE"
}
```

### SQL error

```json
{
  "status": "error",
  "error_type": "sql_error",
  "message": "ERROR: column \"emails\" does not exist",
  "sql_state": "42703",
  "duration_ms": 3
}
```

### Connection error

```json
{
  "status": "error",
  "error_type": "connection_error",
  "message": "Connection refused to localhost:5432"
}
```

## Exit codes

| Code | Meaning |
|------|---------|
| `0` | SQL executed successfully |
| `1` | SQL execution error (JDBC exception) |
| `2` | Policy violation (write or DDL blocked) |
| `3` | Configuration or input error |
| `4` | Connection error |

Agents can branch on `$?` to distinguish infrastructure problems (4) from query problems (1) from policy (2).

## Mutation policy

pgcheck defaults to **read-only**. Only `SELECT` (and equivalents: `WITH`, `TABLE`, `VALUES`, `SHOW`, `EXPLAIN`) are allowed by default.

To enable write operations, add to `~/.pgcheck.properties`:

```properties
allow-writes=true
```

This allows `INSERT`, `UPDATE`, `DELETE`, and `MERGE`.

**DDL is always blocked** regardless of `allow-writes`. `CREATE`, `DROP`, `ALTER`, `TRUNCATE`, `RENAME`, and `COMMENT` statements are rejected before execution in all configurations.

Statement type is detected from the first non-whitespace keyword of the SQL, case-insensitive.

## Local development environment

The `support/` folder provides a self-contained PostgreSQL environment for testing pgcheck.

**Start it:**

```sh
bash support/scripts/up.sh
```

This starts a Docker Compose service with PostgreSQL 16, waits for it to be healthy, and prints the connection string.

**Schema:** A simple `store` schema with two tables (`customers`, `orders`) and seed data — enough to produce non-empty SELECT results and test foreign key queries.

**Sample queries to try:**

```sh
# Count customers
jbang pgcheck.java --sql "SELECT count(*) FROM store.customers"

# Inspect orders by status
jbang pgcheck.java --sql "SELECT status, count(*) FROM store.orders GROUP BY status ORDER BY count DESC"

# Check referential integrity
jbang pgcheck.java --sql "SELECT count(*) FROM store.orders o WHERE NOT EXISTS (SELECT 1 FROM store.customers c WHERE c.id = o.customer_id)"

# Schema inspection
jbang pgcheck.java --sql "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_schema = 'store' AND table_name = 'orders' ORDER BY ordinal_position"
```

**Stop it:**

```sh
cd support && docker compose down
```

## JBang catalog

`jbang-catalog.json` at the repository root defines a local alias:

```sh
# Run from the repo root
jbang pgcheck

# Remote invocation (once the repo is public)
jbang pgcheck@garodriguezlp/pgcheck
```

The primary invocation for documentation and agent skill examples is `jbang pgcheck.java`. The catalog is a convenience layer.

**Optional: install a global JBang alias**

```sh
jbang alias add --name pgcheck pgcheck.java
```

After this, `pgcheck --sql "..."` works from any directory.

## Agent skill setup

### Claude Code

The skill file is already in place at `.claude/skills/pgcheck.md`. Claude Code discovers skills in that directory automatically when working in this repository.

### GitHub Copilot

The skill content is included in `.github/copilot-instructions.md`, which Copilot reads as workspace-level instructions.

### Other agents

Copy or reference `agent-skill.md` from the repository root. It is written in generic, tool-neutral language.
