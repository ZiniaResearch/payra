# Payra

Schedule SMS and forget about forgetting. Built for customers in Bangladesh.

## Current scope

Payra currently implements Google sign-in, browser-key-bound sessions, signed
refresh/logout, and a protected account-profile API. The React app has a full
BoardUI sidebar, independent account loading, and an initially empty `/analytics`
page.

SMS scheduling, recipients/consent, credits, payments, the application header, and
production deployment are planned—not implemented. The former identity-demo API
and blocking frontend loader have been removed.

## Applications

- `ui/`: React, TypeScript, TanStack Start/Router/Query, BoardUI, Tailwind CSS, and
  Biome. Use Bun.
- `server/`: Rust/Axum modular monolith with SQLx and PostgreSQL.
- `compose.yaml`: PostgreSQL 18 for local development; not a full deployment stack.

## Development

Follow [local setup](docs/architecture.md#local-development) for PostgreSQL,
migrations, environment settings, Google credentials, and application commands.
Do not commit real `.env` files or copy cookies, proofs, private keys, or tokens
into issues/logs.

## Documentation

| Guide                                  | Purpose                                                                                  |
| -------------------------------------- | ---------------------------------------------------------------------------------------- |
| [Architecture](docs/architecture.md)   | Setup, current/planned structure, ownership, deployment, and merge checklist.            |
| [System design](docs/system-design.md) | Desired product behavior, scheduling, credits, payments, and launch criteria.            |
| [Authentication](docs/auth.md)         | Login/profile, signed refresh/logout, shared errors, security limits, and manual checks. |

Implementation does not imply browser verification. See the
[merge checklist](docs/architecture.md#verification-and-merge-checklist) before
committing or merging.
