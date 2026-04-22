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

## Skills

Agent-executable skills live in [`.claude/skills/`](.claude/skills/). Each skill file is self-contained with a YAML frontmatter header (name, description) followed by instructions.

| Skill | Description |
|-------|-------------|
| [pgcheck](.claude/skills/pgcheck.md) | Run a read-only SQL query against PostgreSQL and return structured JSON results |
