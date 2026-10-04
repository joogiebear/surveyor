# Surveyor: v1 specification

Status: draft from the kickoff interview. Everything under "Proposed" is a default the owner has
not reviewed in detail. Update this file in the same change as any behavior change.

## 1. Overview

Surveyor is one Paper + Folia plugin that combines two jobs:

1. Protection: named regions, membership, flags.
2. Editing: selections and bulk block edits with undo.

The differentiator is a first-class interface for AI agents and developers: typed operations,
dry-run previews, scoped tokens, rate limits, an audit log and undoable operations, exposed over
MCP (HTTP) and as JSON commands. Players get the same capabilities through commands and a wand.

Clean-room: see `CLAUDE.md`. This design is original.

## 2. Glossary

- Actor: a player, the console, or an agent token.
- Scope: what an actor may touch (worlds, bounding box, action list, limits).
- Operation: one tracked unit of work (an edit, a region change). Has an ID, a status and an audit
  record. Edits are undoable by operation ID.
- Dry run: planning without applying. Returns what would happen.

## 3. Flows

### 3.1 Player
1. Get the wand (`/sv wand`), left/right click blocks to set position 1 and 2 (or `/sv pos1`,
   `/sv pos2`).
2. Run `/sv set <pattern>`, `/sv replace <from> <to>`, `/sv copy`, `/sv paste`, `/sv undo`.
3. Claim land: `/sv region create <name> <x1> <y1> <z1> <x2> <y2> <z2>` (`~` is the player's own
   position; from the console name the region `world:name`). A plain `create <name>` from the
   selection arrives with selections in milestone 3. The creator becomes an owner. Then manage
   members and flags. Any `<name>` argument accepts `world:name` for another world.
4. Every action is checked against permissions, region rights and limits. Denials explain why.

### 3.2 Admin
1. Install, set `server.port` and limits in `config.yml`, restart or `/sv reload`.
2. Create an agent token: `/sv agent token create <name>`. The secret is shown once.
3. Set its scope: worlds, bounding box, allowed actions, per-operation block cap, rate limit.
4. Review activity with `/sv audit`; revoke with `/sv agent token revoke <name>`.

### 3.3 Agent
1. Connect an MCP client to `http://127.0.0.1:<port>/mcp` with `Authorization: Bearer <secret>`.
2. List tools, call them with typed JSON arguments. Mutating tools accept `dryRun`.
3. Call with `dryRun: true`, inspect the plan, then repeat without it. Large edits return an
   `operationId` immediately and finish over several ticks; poll `operation.status`.
4. Undo with `edit.undo { operationId }`.

## 4. Commands and permissions (proposed)

Root command `/sv` (aliases configurable). Double-slash aliases are off by default to avoid
clashing with other plugins.

| Command | Permission |
|---|---|
| `/sv wand`, `pos1`, `pos2`, `sel` | `surveyor.edit.select` |
| `/sv set`, `replace` | `surveyor.edit.modify` |
| `/sv copy`, `paste` | `surveyor.edit.clipboard` |
| `/sv undo [operationId]` | `surveyor.edit.undo` |
| `/sv region create/delete/info/list` | `surveyor.region.manage` |
| `/sv region addmember/removemember/owner/flag/priority` | `surveyor.region.manage` |

Syntax: `flag <region> <flag> <allow|deny|clear> [everyone|members|owners|non-members]`,
`addmember|removemember|owner <region> <player>` (player must have joined before),
`priority <region> <number>`, `list [world]`. Holding `surveyor.region.manage` lets you change any
region; owner-scoped management is not in v1.
| `/sv agent token create/list/revoke/scope` | `surveyor.agent.admin` |
| `/sv audit` | `surveyor.audit` |
| `/sv status`, `/sv reload` | `surveyor.admin` |
| Bypass region protection | `surveyor.bypass.region` (audited) |

## 5. Regions and flags

- Cuboid regions only in v1. Stored per world with integer bounds.
- Members have a role per region: owner or member.
- Overlaps resolved by `priority` (higher wins). At equal priority, deny beats allow.
- Flags (v1): `build`, `interact`, `pvp`, `mob-spawning`, `entry`. Values: allow or deny, with an
  optional audience: everyone, members, owners, non-members.
- Unflagged regions fall back to `defaults.*` in config.
- Region lookups must be O(log n) or better per world (spatial index in `core`) because they run
  on block-break and block-place events.

Resolution (implemented in `core`, `FlagResolver`):
- A flag value speaks only for actors in its audience; for anyone else that region is transparent.
  Owners count as `members`. An event with no player (a mob spawn) counts as `non-members`.
- Among the values that apply, the highest `priority` wins; at equal priority deny beats allow.
- If none applies, `defaults.<flag>` decides. Region names are 1-32 characters of `A-Za-z0-9_-`
  and unique per world, case-insensitively.
- Index: regions are bucketed by 16x16 column (hash lookup). Regions covering more than 1024
  columns go in a short list scanned on every lookup, so the worst case is linear in the number
  of such huge regions.
- Changes are written to SQLite first and applied to the in-memory registry after; readers use an
  immutable snapshot and never block. Until regions have loaded, `build` and `interact` are
  denied for everyone without the bypass permission.

Enforced events (v1): block break and place, bucket empty and fill (`build`); block right-click,
physical triggers and entity right-click (`interact`, except sneak-placing a block); player damage
by a player or their projectile (`pvp`); creature spawns except custom, command and egg spawns
(`mob-spawning`); crossing into a region by movement or teleport (`entry`; a player already
inside is never trapped). Not covered yet: explosions, pistons, fire, fluid flow, tree and mushroom
growth, vehicles, hanging entities, and non-player damage.

## 6. Editing

- Selection: two positions, per actor, per world.
- Operations (v1): `set`, `replace`, `copy`, `paste`, `undo`. Redo is post-v1.
- Patterns: a block type with optional weights (`50%stone,50%andesite`), defined in this spec's
  own syntax (document it in the README when final).
- Execution: plan off-thread into per-chunk batches, apply each batch on the owning region
  scheduler within `limits.tick-budget-ms`, record the previous state of every changed block into
  the undo journal before applying it.
- Protection: every changed position is checked against regions and the actor's scope. Positions
  that fail are skipped and counted in `skipped`; with `strict: true` the whole operation fails
  before applying anything.
- Cancel: `operation.cancel` stops further batches; already applied batches stay undoable.

## 7. Agent interface

Transport: MCP over HTTP (verify the current MCP HTTP transport spec when implementing), bound to
`server.bind` (default `127.0.0.1`). Auth: bearer token, secret stored as a hash.

### 7.1 Tools (proposed names)

| Tool | Mutates | Purpose |
|---|---|---|
| `world.list` | no | Worlds and bounds the token may use |
| `selection.get` / `selection.set` | set | Per-token selection (two positions) |
| `edit.set` / `edit.replace` | yes | Bulk block edits |
| `edit.copy` / `edit.paste` | yes (paste) | Clipboard per token |
| `edit.undo` | yes | Undo by `operationId` |
| `region.create` / `region.update` / `region.delete` | yes | Region management |
| `region.get` / `region.list` / `region.at` | no | Query regions, by id, filter or position |
| `flag.set` / `flag.clear` | yes | Region flags |
| `member.add` / `member.remove` | yes | Region membership |
| `operation.status` / `operation.cancel` | cancel | Track long operations |
| `audit.query` | no | Recent actions by token, region or operation |

### 7.2 Common arguments
- `dryRun` (bool): plan only, change nothing.
- `reason` (string): free-text rationale, stored in the audit log. Required if
  `agents.require-reason` is true.
- `idempotencyKey` (string): repeated calls with the same key return the original result.
- `strict` (bool): fail the whole operation if any position is denied.

### 7.3 Result shape
```json
{
  "ok": true,
  "operationId": "op_01...",
  "status": "planned | running | done | failed | cancelled",
  "blocksPlanned": 0,
  "blocksChanged": 0,
  "skipped": { "protected": 0, "outOfScope": 0 },
  "regionsAffected": ["spawn"],
  "warnings": [],
  "errors": [{ "code": "OUT_OF_SCOPE", "message": "..." }]
}
```
Error codes: `UNAUTHORIZED`, `OUT_OF_SCOPE`, `REGION_DENIED`, `LIMIT_EXCEEDED`, `RATE_LIMITED`,
`INVALID_ARGUMENT`, `NOT_FOUND`, `CONFLICT`, `INTERNAL`. Messages are written for an agent to act
on (say what was wrong and what to change).

### 7.4 Safety model
- Every tool call is checked against the token scope first, then region rights, then limits.
- Per-token rate limit and per-operation block cap. Global concurrent-operation cap.
- Audit record for every mutating call, including dry runs of large operations if configured.
- Tokens: 256-bit random secret, shown once, stored as SHA-256 hash, optional expiry, revocable
  immediately. Revoking cancels that token's running operations.
- No remote binding in v1 (config refuses a non-loopback bind unless `server.allow-remote: true`
  and TLS is configured; TLS itself is post-v1).

## 8. Configuration (proposed)

`config.yml`:
```yaml
server:
  enabled: true
  bind: 127.0.0.1
  port: 8765
  allow-remote: false
limits:
  max-blocks-per-operation: 250000
  tick-budget-ms: 8
  max-concurrent-operations: 4
agents:
  default-rate-limit-per-minute: 30
  require-reason: true
storage:
  type: sqlite
  file: surveyor.db
undo:
  retention-days: 14
audit:
  retention-days: 90
defaults:
  build: allow
  interact: allow
  pvp: allow
  mob-spawning: allow
  entry: allow
```
`messages.yml`: all player-facing text. Hot reload via `/sv reload` for both files.

## 9. Data model (SQLite, WAL)

All writes happen off the region threads. Schema is versioned with forward-only migrations.

- `schema_version(version)`
- `regions(id, world, name, min_x, min_y, min_z, max_x, max_y, max_z, priority, created_at)`
- `region_members(region_id, subject_type, subject_id, role)` subject: player UUID
- `region_flags(region_id, flag, value, audience)`
- `agent_tokens(id, name, secret_hash, scope_json, created_by, created_at, expires_at, revoked_at)`
- `operations(id, actor_type, actor_id, kind, world, status, params_json, reason, blocks_changed,
  created_at, finished_at)`
- `undo_journal(operation_id, chunk_x, chunk_z, seq, data)` previous block states per chunk batch
- `audit_log(id, at, actor_type, actor_id, action, target, operation_id, result)`

Undo journal writes for a batch are committed before that batch is applied to the world.

Migration 1 (`001_baseline.sql`) creates all the tables above. The runner owns `schema_version`.
Later schema changes are new numbered migrations; a database newer than the plugin is refused.

## 10. Milestones

1. Skeleton: Gradle modules, plugin.yml (`folia-supported`), SQLite and migrations, config and
   messages, `/sv status`, CI build, test server task.
2. Regions: model, spatial index, flags, member roles, protection listeners, `/sv region ...`.
3. Editing: selection, wand, chunked Folia-safe executor, `set`/`replace`/`copy`/`paste`, undo.
4. Agent interface: tokens, scope, rate limits, MCP server, tools, dry run, audit log.
5. Hardening: load tests on a Folia test server, crash-recovery tests for the undo journal,
   docs, README, changelog, BuiltByBit packaging and product page (with AI disclosure line).

### v1 acceptance ("done means")
- A player can claim a region, set flags, and edit blocks only where allowed, with undo.
- An MCP client with a scoped token can do the same, with `dryRun`, and cannot exceed its scope,
  limits or region rights.
- A 250k-block edit on a Folia server completes without exceeding the per-tick budget and without
  region-thread violations.
- Kill -9 during an edit leaves a consistent world state that can be undone from the journal.
- Core logic has unit tests; permission, scope and region-denial paths are covered.

## 11. Out of scope for v1

Schematic and file-format compatibility, brushes, biome and generation tools, polygon and
cylinder regions, redo, a web UI, importing data from other plugins, remote network access and
TLS, MySQL.

## 12. Open questions and checks before release

1. Product name and trademark: "Surveyor" is a working choice. Search BuiltByBit, SpigotMC,
   Modrinth, GitHub, domains and trademark databases before committing to branding.
2. Legal check on the clean-room approach and on factual comparison wording in marketing.
3. BuiltByBit rules on AI-assisted products, licensing, and required listing details.
4. Java version: confirmed as 25 (Paper docs). Scheduler APIs (`RegionScheduler`,
   `GlobalRegionScheduler`, `AsyncScheduler`, `EntityScheduler`) confirmed in the Folia 26.2
   Javadocs; still to verify per method as each is used.
5. Whether to support importing existing region data later (needs a licensing review of the
   source formats).
6. Remote access and TLS design for post-v1.
