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
- Agent interface: an embedded MCP server (`POST /mcp`, loopback only, bearer tokens) speaking
  revision 2026-07-28 and the earlier handshake revisions, with tools for worlds, selections, edits
  (`set`, `replace`, `copy`, `paste`, `undo`), regions, flags, members, operations and audit. Every
  change supports `dryRun`, `reason`, `idempotencyKey` and (edits) `strict`.
- Agent tokens: `/sv agent token create|list|revoke|scope` and `/sv audit`, with the permissions
  `surveyor.agent.admin` and `surveyor.audit`. Secrets are 256-bit, shown once in game and stored only
  as a SHA-256 hash; a token has worlds, an optional box, allowed actions, a block cap and a rate
  limit, can expire, and revoking one cancels its running operations at once.
- Operations now record how many blocks were planned and skipped (protected or out of scope) and
  which regions they touched, so `operation.status` still answers after a restart.
- `audit.log-dry-runs` in `config.yml`.
- Fixed edits never starting when no player is near the area: the chunk is now held with a plugin
  ticket while a batch runs, so Folia has a ticking region to run it on.
- Fixed a message added in a new version showing as its raw key when `messages.yml` on disk was
  older than the plugin.
- Fixed a region or selection whose size overflows a long being treated as small: block counts now
  saturate.
