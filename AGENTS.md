# pgcheck — Agent Reference

## Project

One-shot PostgreSQL executor for LLM agents. Takes SQL, returns structured JSON to stdout, exits. No interactive shell, no ANSI decoration — clean output agents can parse directly.

Implemented as a single JBang-runnable Java file (`pgcheck.java`). JBang handles compilation and dependency download on first run — no build step required.

## Tech stack

- Java 17+ · JBang (bundled wrappers: `jbang`, `jbang.cmd`, `jbang.ps1`)
- picocli · Jackson · PostgreSQL JDBC · tinylog
- Docker Compose for local dev database

## Key files

| Path | Purpose |
|------|---------|
| `pgcheck.java` | Entire implementation — one file, all classes |
| `.pgcheck.properties.example` | Connection config template → copy to `~/.pgcheck.properties` |
| `support/docker-compose.yml` | Local PostgreSQL 16 instance |
| `support/initdb/` | Schema + seed SQL applied on container start |
| `.claude/skills/pgcheck.md` | pgcheck skill (Claude Code auto-discovers) |
| `jbang-catalog.json` | Local alias: `./jbang pgcheck` |

## Local database

```sh
bash support/scripts/up.sh    # start (waits for healthcheck)
bash support/scripts/down.sh  # stop and remove volumes
```

Connection defaults (match Docker Compose): `localhost:5432`, db `pgcheck_demo`, user/password `pgcheck`.

### Schema: `store`

```sql
CREATE TABLE customers (
    id         SERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    email      VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id          SERIAL PRIMARY KEY,
    customer_id INTEGER      NOT NULL REFERENCES customers(id),
    status      VARCHAR(20)  NOT NULL DEFAULT 'pending'
                             CHECK (status IN ('pending', 'shipped', 'delivered', 'cancelled')),
    total_cents INTEGER      NOT NULL CHECK (total_cents >= 0),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

## Running pgcheck

```sh
# Unix
./jbang pgcheck.java --sql "SELECT count(*) FROM store.customers"

# Windows
jbang.cmd pgcheck.java --sql "SELECT count(*) FROM store.customers"
```

Output is always JSON on stdout. Exit code signals result category: `0` ok · `1` sql_error · `2` policy_violation · `3` input_error · `4` connection_error.

## Architecture

Everything lives in `pgcheck.java`. JBang single-file mode requires all types in one file — this is intentional, not a shortcut. The file contains: `pgcheck` (main command), `ExitCode`, `DatabaseConfig`, `QueryOptions`, `SqlInputGroup`, `StatementType`, `PolicyGuard`, `QueryExecutor`, `ResponseWriter`, and supporting types.

## Code principles

- **Stay in one file.** JBang single-file mode is a hard constraint — never split into multiple files.
- **One responsibility per type.** Each class/record has a single clear purpose.
- **Names reveal intent.** Honest, intention-revealing names. No type suffixes, no abbreviations, no clever tricks.
- **No silent failures.** All error paths produce a valid JSON error envelope. Exceptions are handled close to origin or propagated intentionally — never swallowed.
- **Comments explain why, never what.** Comment only non-obvious constraints or workarounds. Well-named code is self-documenting.
- **Don't abstract early.** Wait for the third occurrence before extracting a helper.
- **Prefer real collaborators.** Mock only at system boundaries (JDBC, filesystem).

## Skills

Agent-executable skills live in [`.claude/skills/`](.claude/skills/). Each file has YAML frontmatter (name, description) followed by instructions.

| Skill | Description |
|-------|-------------|
| [pgcheck](.claude/skills/pgcheck.md) | Run a SQL query against PostgreSQL and return structured JSON |
