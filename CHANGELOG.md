# Changelog

## Unreleased

- Project skeleton: Gradle modules (`core`, `paper`, `mcp`), Folia-supported `plugin.yml`.
- SQLite storage (WAL) loaded through Paper's library loader, with forward-only migrations.
- `config.yml` and `messages.yml` with hot reload via `/sv reload`.
- `/sv status` command.
- Regions: cuboids per world with owner and member roles, priorities, and the `build`,
  `interact`, `pvp`, `mob-spawning` and `entry` flags with audiences. Stored in SQLite and
  protected by listeners; `defaults.*` in `config.yml` applies where no flag is set.
- `/sv region create|delete|info|list|addmember|removemember|owner|flag|priority`.
- `surveyor.bypass.region` ignores protection; each override is written to the audit log.
