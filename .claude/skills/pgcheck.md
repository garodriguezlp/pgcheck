---
name: pgcheck
description: Run a read-only SQL query against the configured PostgreSQL database using pgcheck and return structured JSON results.
---

# pgcheck — PostgreSQL Validation Skill

## When to use pgcheck

Use pgcheck whenever you need to inspect or validate data in a PostgreSQL database:

- Counting rows or checking record existence
- Inspecting schema (columns, types, constraints)
- Validating foreign key relationships or referential integrity
- Checking for null values, duplicates, or unexpected data
- Running ad-hoc queries during debugging or data verification tasks
- Confirming that a migration, seed, or transformation produced correct results

pgcheck is a one-shot executor. It connects, runs one SQL statement, prints structured JSON, and exits. It does not maintain sessions.

## Invocation

pgcheck is invoked directly via JBang:

```sh
jbang pgcheck.java --sql "<SQL>"
```

**Examples:**

Count rows:
```sh
jbang pgcheck.java --sql "SELECT count(*) FROM orders"
```

Inspect schema:
```sh
jbang pgcheck.java --sql "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'orders' ORDER BY ordinal_position"
```

Check for nulls:
```sh
jbang pgcheck.java --sql "SELECT count(*) FROM users WHERE email IS NULL"
```

Run from a file:
```sh
jbang pgcheck.java --file ./checks/validate_fk.sql
```

Read SQL from stdin:
```sh
echo "SELECT now()" | jbang pgcheck.java --stdin
```

Limit result size:
```sh
jbang pgcheck.java --sql "SELECT * FROM events" --max-rows 10
```

Set a shorter timeout:
```sh
jbang pgcheck.java --sql "SELECT count(*) FROM large_table" --timeout 60
```

## Options you control

| Option | Description | Default |
|--------|-------------|---------|
| `--sql <string>` | Inline SQL to execute | — |
| `--file <path>` | Path to a `.sql` file | — |
| `--stdin` | Read SQL from standard input | false |
| `--max-rows <n>` | Maximum rows returned for SELECT | 100 |
| `--timeout <s>` | Query timeout in seconds | 30 |

Provide exactly one of `--sql`, `--file`, or `--stdin` per invocation.

## Options you must NOT set

Do not pass `--url`, `--username`, `--password`, or `--allow-writes`. These are operator-managed and set via `~/.pgcheck.properties`. Passing them overrides operator policy and may expose credentials.

## Output format

All output is JSON on stdout. The exit code indicates success or failure category.

### Successful SELECT

```json
{
  "status": "ok",
  "statement_type": "SELECT",
  "row_count": 3,
  "truncated": false,
  "columns": [
    { "name": "id", "type": "int4" },
    { "name": "email", "type": "varchar" }
  ],
  "rows": [
    { "id": 1, "email": "alice@example.com" },
    { "id": 2, "email": "bob@example.com" },
    { "id": 3, "email": "carol@example.com" }
  ],
  "duration_ms": 12
}
```

### Successful DML (when writes are enabled)

```json
{
  "status": "ok",
  "statement_type": "UPDATE",
  "rows_affected": 5,
  "duration_ms": 8
}
```

### Error responses

```json
{ "status": "error", "error_type": "sql_error",        "message": "...", "sql_state": "42703", "duration_ms": 3 }
{ "status": "error", "error_type": "connection_error", "message": "..." }
{ "status": "error", "error_type": "policy_violation", "message": "...", "statement_type": "UPDATE" }
{ "status": "error", "error_type": "input_error",      "message": "..." }
```

## Reading the output

- Check `status` first: `"ok"` means the statement executed; `"error"` means it did not.
- On `"ok"` with SELECT: read `rows` for data, `columns` for schema context.
- On `"ok"` with DML: read `rows_affected` to confirm the expected number of rows changed.
- On `"error"`: read `error_type` to determine the category, then `message` for details.

## Interpreting truncation

If `truncated` is `true`, the result was cut at `--max-rows` (default 100). You are seeing an incomplete result set. Do not draw conclusions about totals or completeness from a truncated result. Instead:

- Use aggregation (`COUNT`, `SUM`, etc.) to get exact totals.
- Narrow the query with a `WHERE` clause.
- Increase `--max-rows` if you need more rows (the operator may have set a cap).

## Error handling

| `error_type` | Meaning | What to do |
|---|---|---|
| `connection_error` | Could not reach the database | Infrastructure problem; do not retry with different SQL. Report to the operator. |
| `sql_error` | JDBC reported an error executing the SQL | The query is wrong. Check `message` and `sql_state` and fix the SQL. |
| `policy_violation` | Statement type is blocked | You attempted a write (or DDL) that the operator has not enabled. Use a SELECT instead, or ask the operator to enable writes. |
| `input_error` | Bad invocation (missing or conflicting SQL input) | Fix the command arguments. |

## Safe query patterns

By default pgcheck is read-only. Prefer `SELECT` queries for all validation tasks:

```sql
-- Check existence
SELECT count(*) FROM orders WHERE customer_id = 42;

-- Verify referential integrity
SELECT count(*) FROM order_items oi
WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = oi.order_id);

-- Inspect a sample
SELECT * FROM customers LIMIT 5;

-- Check a constraint
SELECT id, email FROM users WHERE email NOT LIKE '%@%';
```

Write operations (`INSERT`, `UPDATE`, `DELETE`) are blocked unless the operator has explicitly enabled them via `~/.pgcheck.properties`. DDL (`CREATE`, `DROP`, `ALTER`, `TRUNCATE`) is always blocked.
