# Coding Guidelines

These guidelines apply to all code in this repository, regardless of the AI agent or assistant generating it.

## Core principles

- **SOLID**: Single responsibility, open/closed, Liskov substitution, interface segregation, dependency inversion.
- **Clean Code**: Names are honest and intention-revealing. Code reads like prose. No clever tricks.
- **One level of abstraction per method**: A method either orchestrates or operates — not both. If a method calls helpers, it should contain only calls to helpers at the same conceptual level.
- **Small, single-purpose units**: Functions, methods, and classes do one thing. If you need "and" to describe what a unit does, split it.
- **No surprises**: Side effects are explicit, not hidden. A reader should be able to predict what a function does from its name alone.

## Naming

- Names encode intent, not type (`userEmail`, not `emailStr`).
- Booleans read as questions (`isEmpty`, `hasExpired`).
- Methods that return values use noun phrases; methods that perform actions use verb phrases.
- Avoid abbreviations unless they are universally understood in the domain (e.g., `id`, `url`).

## Functions and methods

- Short: aim for functions that fit on a screen without scrolling.
- One entry, one exit point — unless early returns clarify logic (guard clauses are fine).
- No flag arguments. A boolean parameter that changes a function's behavior is two functions in disguise.
- Prefer returning values to mutating arguments.

## Classes and modules

- A class encapsulates a coherent concept, not a grab-bag of utilities.
- Constructors establish a valid object; they do not perform I/O or heavy computation.
- Dependencies are injected, not fetched. No static singletons hidden inside methods.
- Interfaces over concrete types at boundaries.

## Error handling

- Errors are handled close to where they occur or propagated intentionally — not silently swallowed.
- Error paths are as readable as the happy path.
- Never use exceptions for flow control.

## Tests

- Each test exercises one behaviour and has one reason to fail.
- Test names describe the scenario and expected outcome, not the method under test.
- No test logic that mirrors the implementation — tests should catch regressions, not echo production code.
- Prefer real collaborators over mocks where the cost is low; mock at system boundaries.

## Comments

- Default to no comments. Well-named code is self-documenting.
- Write a comment only when the *why* is non-obvious: a hidden constraint, a workaround for an external bug, a subtle invariant.
- Never describe *what* the code does — that is the code's job.

## General

- Duplication is bad; premature abstraction is worse. Wait for the third occurrence before abstracting.
- Delete dead code. Version control is the history.
- Leave the codebase in a better state than you found it — but only within the scope of the current task.

---

<!-- pgcheck skill — keep in sync with agent-skill.md -->

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
