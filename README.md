# pgcheck

Minimal, non-interactive PostgreSQL CLI designed for LLM agent workflows. Supply SQL, receive
structured JSON, and exit.

No interactive shell, no ANSI decoration, and no human-first formatting noise: output is deterministic
and easy for agents to parse reliably. Implemented as a single JBang-runnable `.java` file; no build
step required.

**Prerequisites:** Java 17+ (JBang auto-downloads if
missing) · [Docker](https://docs.docker.com/get-docker/) for the local database.

## Start the local database

```sh
bash support/scripts/up.sh   # start PostgreSQL 16, wait for healthcheck
bash support/scripts/down.sh # stop and remove volumes when done
```

The `store` schema is seeded with `customers` and `orders` data ready to query.

> **Already have a database?** Copy `.pgcheck.properties.example` to `~/.pgcheck.properties` and
> edit the connection details.

## Quick start

```sh
# Unix/macOS
./jbang pgcheck.java --sql "SELECT id, name, email FROM store.customers"

# Windows
jbang.cmd pgcheck.java --sql "SELECT id, name, email FROM store.customers"
```

```json
{
  "status": "ok",
  "statement_type": "SELECT",
  "row_count": 5,
  "truncated": false,
  "columns": [{ "name": "id", "type": "int4" }, { "name": "name", "type": "varchar" }, { "name": "email", "type": "varchar" }],
  "rows": [
    { "id": 1, "name": "Alice Martin", "email": "alice@example.com" },
    { "id": 2, "name": "Bob Chen", "email": "bob@example.com" },
    { "id": 3, "name": "Carol Davis", "email": "carol@example.com" },
    { "id": 4, "name": "David Kim", "email": "david@example.com" },
    { "id": 5, "name": "Eve Nakamura", "email": "eve@example.com" }
  ],
  "duration_ms": 14
}
```

## Configuration — `~/.pgcheck.properties`

| Key            | Default                                         | Description                                           |
|----------------|-------------------------------------------------|-------------------------------------------------------|
| `url`          | `jdbc:postgresql://localhost:5432/pgcheck_demo` | JDBC connection URL                                   |
| `username`     | `pgcheck`                                       | Database username                                     |
| `password`     | `pgcheck`                                       | Database password                                     |
| `allow-writes` | `false`                                         | Allow DML (INSERT/UPDATE/DELETE). DDL always blocked. |
| `max-rows`     | `100`                                           | Maximum rows returned for SELECT queries              |
| `timeout`      | `30`                                            | Query timeout in seconds                              |

Connection settings (`url`, `username`, `password`, `allow-writes`) are operator-managed — set in
the properties file, never passed via CLI from an agent.

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

### SELECT

```json
{
  "status": "ok",
  "statement_type": "SELECT",
  "row_count": 3,
  "truncated": false,
  "columns": [{ "name": "customer", "type": "varchar" }, { "name": "order_count", "type": "int8" }, { "name": "total_spent", "type": "int8" }],
  "rows": [
    { "customer": "Alice Martin", "order_count": 2, "total_spent": 17499 },
    { "customer": "Bob Chen", "order_count": 2, "total_spent": 7399 },
    { "customer": "Carol Davis", "order_count": 2, "total_spent": 28100 }
  ],
  "duration_ms": 12
}
```

### DML

```json
{
  "status": "ok",
  "statement_type": "UPDATE",
  "rows_affected": 3,
  "duration_ms": 8
}
```

### Errors

```json
{
  "status": "error",
  "error_type": "policy_violation",
  "message": "Write operations are disabled...",
  "statement_type": "UPDATE"
}
{
  "status": "error",
  "error_type": "sql_error",
  "message": "ERROR: column \"x\" does not exist",
  "sql_state": "42703",
  "duration_ms": 3
}
{
  "status": "error",
  "error_type": "connection_error",
  "message": "Connection refused to localhost:5432"
}
{
  "status": "error",
  "error_type": "input_error",
  "message": "SQL input is empty"
}
```

## Exit codes

| Code | Meaning                                 |
|------|-----------------------------------------|
| `0`  | SQL executed successfully               |
| `1`  | SQL execution error                     |
| `2`  | Policy violation (write or DDL blocked) |
| `3`  | Input error                             |
| `4`  | Connection error                        |

## Mutation policy

Read-only by default. Allowed: `SELECT`, `WITH`, `TABLE`, `VALUES`, `SHOW`, `EXPLAIN`. DDL (
`CREATE`, `DROP`, `ALTER`, `TRUNCATE`, `RENAME`, `COMMENT`) is always blocked.

To enable DML writes, add to `~/.pgcheck.properties`:

```properties
allow-writes=true
```

## Agent skill setup

- **Claude Code** — `.claude/skills/pgcheck.md` is auto-discovered when working in this repo.
- **Other agents** — `AGENTS.md` at the repo root is picked up automatically by most agent
  frameworks.
