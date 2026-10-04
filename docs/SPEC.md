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
   `/sv pos2`). `/sv sel` shows the selection, `/sv sel clear` drops it.
2. Run `/sv set <pattern>`, `/sv replace <from> <to>`, `/sv copy`, `/sv paste`, `/sv undo
   [operationId]`, `/sv cancel [operationId]`. `set`, `replace` and `paste` also accept a trailing
   `strict`.
3. Claim land: `/sv region create <name> <x1> <y1> <z1> <x2> <y2> <z2>` (`~` is the player's own
   position; from the console name the region `world:name`). `create <name>` alone uses the
   player's selection in their world. The creator becomes an owner. Then manage
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
| `/sv set`, `replace`, `cancel` | `surveyor.edit.modify` |
| `/sv copy`, `paste` | `surveyor.edit.clipboard` |
| `/sv undo [operationId]` | `surveyor.edit.undo` |
| `/sv region create/delete/info/list` | `surveyor.region.manage` |
| `/sv region addmember/removemember/owner/flag/priority` | `surveyor.region.manage` |

Undo and cancel act on the actor's own operations; acting on someone else's needs
`surveyor.admin`. All `surveyor.edit.*` permissions default to op.

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

- Selection: two positions, per actor, per world. Held in memory; dropped when the player quits.
- Wand: a wooden axe tagged with persistent data (`surveyor:wand`), given by `/sv wand`. Left click
  a block is position 1, right click is position 2; the click is cancelled. Needs
  `surveyor.edit.select`; without it the axe is an ordinary axe.
- Operations (v1): `set`, `replace`, `copy`, `paste`, `undo`. Redo is post-v1.
- Patterns: comma separated block types with optional weights, `stone` or `50%stone,50%andesite`.
  Weights are positive whole numbers, relative to each other (they need not add up to 100), and are
  given for every entry or for none (then equal). A block may carry a state in brackets,
  `oak_stairs[facing=north]`; commas inside brackets do not split. Block names are the server's own
  block ids. `replace <from> <to>`: `from` is a comma separated block list without weights; a plain
  type matches in any state, a type with a bracketed state must match that state. Document in the
  README when final.
- Block state: block data (facing, waterlogged, ...) is kept by copy, paste and undo. Tile entity
  contents (chests, signs, spawners, banners) are not: they are lost when overwritten and are not
  restored by undo.
- Clipboard: one per player, in memory only, lost on quit or restart. Size is capped by
  `limits.max-blocks-per-operation`. It stores the copied box and where it sat relative to the
  block the player stood on; paste puts that same relationship back at the player's current block.
  No rotation or flip in v1. Air is part of the copy.
- Execution: plan off-thread into per-chunk batches (at most 4096 blocks, split by height), then
  work through the batches one at a time. For each batch, on the region scheduler that owns the
  chunk: scan it in slices, commit its previous block data to the undo journal, then apply it in
  slices. Every slice stops at `limits.tick-budget-ms` and continues on the next tick. Blocks are
  set without physics updates. The journal row is stored before any block of its batch changes.
  The area is cut to the world's build height; its size is checked against
  `limits.max-blocks-per-operation` and `limits.max-concurrent-operations` is enforced.
- Protection: every changed position is checked against regions (`build` flag) before it is
  journaled and again just before it is applied. Positions that fail are skipped and counted in
  `skipped.protected`; only blocks that would actually change are counted. With `strict`, the
  whole area is checked first and the operation fails with nothing applied if any position in it
  is protected (including positions a `replace` would not have touched). Actor scope joins this
  check in milestone 4. `surveyor.bypass.region` skips the checks and writes one `bypass.edit`
  audit row per operation that would otherwise have been denied something.
- Undo journal: one `undo_journal` row per batch: a palette of block data strings plus one entry per
  changed block (x and z in the chunk, y, palette index), gzipped. `seq` is the batch number.
  `/sv undo [operationId]` replays the rows into the world in reverse order, through the same
  scheduling and protection checks. Without an id it takes the actor's newest operation that
  changed blocks and has not been undone. An operation is marked undone (`operations.undone_by`)
  only if nothing was skipped; otherwise it can be undone again. Undoing an old operation after a
  newer one touched the same blocks restores the old state over the newer one. Undo is not itself
  journaled (no redo).
- Cancel: `/sv cancel [operationId]` (agents: `operation.cancel`) stops further batches; batches
  already applied stay undoable.
- Status: `planned` (row written, planning), `running`, then `done`, `failed` or `cancelled`.
  `copy` is tracked like the others but changes nothing and has no journal.
- Shutdown: new batches stop and running operations are recorded as `cancelled`. Region tasks stop
  with the plugin, so a batch in the middle of being applied may be left partly applied; its
  journal row is already stored, so undo restores it exactly.
- Crash recovery: at startup, operations still `planned` or `running` are set to `failed`, a
  warning lists their ids, and players with `surveyor.admin` get a notice when they join. Whatever
  they changed can be restored with `/sv undo <id>`.

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
  created_at, finished_at, undone_by)` ids look like `op_` plus 14 base 36 characters that sort in
  creation order
- `undo_journal(operation_id, chunk_x, chunk_z, seq, data)` previous block states per chunk batch
- `audit_log(id, at, actor_type, actor_id, action, target, operation_id, result)`

Undo journal writes for a batch are committed before that batch is applied to the world.

Migration 1 (`001_baseline.sql`) creates all the tables above. The runner owns `schema_version`.
Migration 2 (`002_undo_marker.sql`) adds `operations.undone_by` and an index on
`operations(actor_type, actor_id, created_at)`. Later schema changes are new numbered migrations; a database newer than the plugin is refused.

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
