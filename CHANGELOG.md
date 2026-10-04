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
- Editing: selections (`/sv wand`, `pos1`, `pos2`, `sel`), `/sv set`, `replace`, `copy`, `paste`,
  `undo` and `cancel`, with weighted block patterns (`50%stone,50%andesite`). Edits are planned
  off-thread and applied per chunk on the owning Folia region scheduler within
  `limits.tick-budget-ms`; the previous state of every changed block is saved to the undo journal
  before it is changed. Region protection and `strict` apply to every changed position.
- `/sv region create <name>` uses your selection.
- Permissions `surveyor.edit.select`, `surveyor.edit.modify`, `surveyor.edit.clipboard` and
  `surveyor.edit.undo`.
- Edits interrupted by a crash are marked failed at startup and reported to admins; `/sv undo <id>`
  restores what they changed.
