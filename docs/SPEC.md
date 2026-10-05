# Surveyor: v1 specification

Status: milestones 1-5 built; sections marked "proposed" are as built and accepted unless a note says otherwise. Update this file in the same change as any behavior change.

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
2. Create an agent token in game: `/sv agent token create <name> [days]`. The secret is shown once, in
   chat, and can be copied with a click. The console is refused, because it is logged.
3. Set its scope: `/sv agent token scope <name> worlds|box|actions|maxblocks|rate ...`. A new token
   can do nothing until it has worlds and actions. `scope <name>` alone shows the scope.
4. Review activity with `/sv audit [token <name>] [count]`; revoke with `/sv agent token revoke <name>`.

### 3.3 Agent
1. Connect an MCP client to `http://127.0.0.1:<port>/mcp` with `Authorization: Bearer <secret>`.
2. List tools, call them with typed JSON arguments. Mutating tools accept `dryRun`.
3. Call with `dryRun: true`, inspect the plan, then repeat without it. Large edits return an
   `operationId` immediately and finish over several ticks; poll `operation.status` (polls count
   against the rate limit).
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

Agent syntax: `agent token create <name> [days]` (players only), `agent token list`,
`agent token revoke <name>`, `agent token scope <name> [worlds <w,w|none>] [box <world> <x1> <y1> <z1>
<x2> <y2> <z2>|box clear] [actions <a,b|none>] [maxblocks <n|default>] [rate <n|default>]` (one setting
per command), `audit [token <name>] [count]`. `surveyor.agent.admin` and `surveyor.audit` default to op.

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
  slices. Every slice stops at `limits.tick-budget-ms` and continues on the next tick. While a batch runs the
  plugin holds a chunk ticket on its chunk and releases it when the batch ends: a chunk that is only
  loaded, with no player near it, belongs to no ticking region and Folia would never run the batch. Blocks are
  set without physics updates. The journal row is stored before any block of its batch changes.
  The area is cut to the world's build height; its size is checked against
  `limits.max-blocks-per-operation` and `limits.max-concurrent-operations` is enforced.
- Protection: every changed position is checked against regions (`build` flag) before it is
  journaled and again just before it is applied. Positions that fail are skipped and counted in
  `skipped.protected`; only blocks that would actually change are counted. With `strict`, the
  whole area is checked first and the operation fails with nothing applied if any position in it
  is protected (including positions a `replace` would not have touched). For an agent token, its
  scope is checked first and positions outside it are skipped and counted in `skipped.outOfScope`
  (see 7.4). `surveyor.bypass.region` skips the checks and writes one `bypass.edit`
  audit row per operation that would otherwise have been denied something. For events (break, place,
  click, entry) the first bypass of a player in a set of regions writes a row at once; the rest within
  60 seconds are counted and written as one summary row (`<regions>: N more within 60 s`), so a
  bypassing builder does not flood the log.
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

Transport: MCP over Streamable HTTP, one endpoint, `POST /mcp`, on `server.bind`:`server.port`
(default `127.0.0.1:8765`). Checked against the MCP specification revision 2026-07-28 and the
earlier handshake revisions on 2026-10-04.

- Revision 2026-07-28 is stateless: every request carries `_meta` with
  `io.modelcontextprotocol/protocolVersion` and `.../clientCapabilities`, and the headers
  `MCP-Protocol-Version`, `Mcp-Method` and, for `tools/call`, `Mcp-Name` (plain or `=?base64?...?=`).
  A header that disagrees with the body is `400` with `-32020`; an unknown version is `400` with
  `-32022` and the supported list; `server/discover` is answered; results carry
  `resultType: "complete"`.
- Revisions 2025-11-25, 2025-06-18 and 2025-03-26 are served on the same endpoint: an `initialize`
  request is answered with the client's version (or `2025-11-25`) and no `Mcp-Session-Id`, and later
  requests are served as they arrive. A request with no `MCP-Protocol-Version` header counts as
  2025-03-26.
- Every reply is `application/json`; there is no SSE stream, no subscription and no resumption. `GET` and
  `DELETE` get `405`, a notification gets `202`, a JSON-RPC batch is refused (`400`, `-32600`), a body
  over 1 MiB is `413`.
- Auth: `Authorization: Bearer <secret>` on every request, else `401` with `WWW-Authenticate`.
  If an `Origin` header is present its host must be `localhost`, `127.0.0.1` or `[::1]`, else `403`.
- Bind: only loopback is accepted. Anything else is refused even with `server.allow-remote: true`,
  because TLS is not built; the plugin keeps running without the MCP server and `/sv status` says why.
- `tools/list` returns only the tools the token's scope allows. `tools/call` returns the result of
  7.3 both as text and as `structuredContent`, with `isError` set when `ok` is false. An unknown
  tool or malformed arguments object is a JSON-RPC `-32602` error.

### 7.1 Tools

World arguments are `world` (name). Positions are `[x, y, z]` arrays of integers. Regions are named
by `world` and `region`.

| Tool | Mutates | Purpose |
|---|---|---|
| `world.list` | no | Worlds the token may use, height limits, its box, block cap, calls per minute |
| `selection.get` / `selection.set` | token state only | Per-token selection: `pos1`, `pos2`, or `clear: true` |
| `edit.set` | yes | `pattern`; area from `pos1`+`pos2`, or the selection when both are left out |
| `edit.replace` | yes | `from`, `to`; area as `edit.set` |
| `edit.copy` | clipboard only | Copies the area to the token's clipboard; optional `anchor` (default: minimum corner) |
| `edit.paste` | yes | `at`: the block the clipboard anchor lands on |
| `edit.undo` | yes | `operationId` of one of the token's own operations |
| `region.create` | yes | `region`, `min`, `max`; the token is not made a member |
| `region.update` | yes | `priority` only; bounds cannot be changed in v1 |
| `region.delete` | yes | |
| `region.get` / `region.list` / `region.at` | no | `at` is a position; `list` takes an optional world |
| `flag.set` / `flag.clear` | yes | `flag`, `value` (allow or deny), optional `audience` |
| `member.add` / `member.remove` | yes | `player` (name or UUID), `role` (member or owner) |
| `operation.status` / `operation.cancel` | cancel | The token's own operations only |
| `audit.query` | no | The token's own audit rows; filters `operationId`, `region`, `action`, `limit` (max 200) |

Arguments are checked against the tool's input schema before anything runs: unknown names, wrong
types, missing required values and bad enum values are `INVALID_ARGUMENT` with a message naming the
argument. The full schemas are what `tools/list` returns.

### 7.2 Common arguments
- `dryRun` (bool): plan only, change nothing. For edits it returns `blocksPlanned` (positions that pass
  scope and region checks), `skipped` and `regionsAffected`, with no `operationId`. For region, flag
  and member tools it validates fully and returns the region as it would look afterwards.
- `reason` (string): stored in the audit log and on the operation. Required for every non-dry
  change if `agents.require-reason` is true, except `operation.cancel`.
- `idempotencyKey` (string): for 24 hours, a repeat of the same request returns the first result with
  `replayed: true`; a different request under the same key is `CONFLICT`. Only successful results
  are kept, so a failed call can be corrected and retried under the same key. Not used with `dryRun`.
- `strict` (bool, edits): fail the whole call with nothing applied if any position in the area is
  protected or out of scope (the code is `OUT_OF_SCOPE` if any position is out of scope, else
  `REGION_DENIED`). Counts every position in the area, including ones a replace would not change.

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
`errors` is present only when `ok` is false (a refused strict edit still carries the counts). Tools
that are not edits return their own fields next to `ok` (`worlds`, `region`, `regions`, `entries`).
A rate-limited call also carries `retryAfterMs`.

An edit that is not refused returns at once, with the operation `planned`; edits run over several
ticks, and the final numbers come from `operation.status`. At start, `blocksPlanned` and `skipped` are
the plan (positions in the area); at the end they are the blocks that were actually skipped, which
for `replace` and for blocks already holding the new value can be fewer.

Error codes: `UNAUTHORIZED`, `OUT_OF_SCOPE`, `REGION_DENIED`, `LIMIT_EXCEEDED`, `RATE_LIMITED`,
`INVALID_ARGUMENT`, `NOT_FOUND`, `CONFLICT`, `INTERNAL`. Messages are written for an agent to act
on (say what was wrong and what to change). A token never learns that something outside its scope
exists: another token's operation, a region it cannot see, or a world it may not use are reported as
`NOT_FOUND` or `OUT_OF_SCOPE`, never as present.

### 7.4 Safety model
Every tool call passes these checks in this order, and the first that fails ends it:

1. The token exists, is not revoked and has not expired.
2. Rate limit: a token bucket per token, a full bucket holds one minute of calls, kept in memory
   (it resets on restart). The token's `ratePerMinute`, else `agents.default-rate-limit-per-minute`.
   Every call counts, reads and dry runs included. Calls refused here are not audited.
3. The tool is among the token's allowed actions.
4. The arguments fit the schema, and a reason is given where required.
5. Inside the tool: the world is allowed; the area is cut to the world's height, must be inside the
   world border, must not exceed the block cap (`min(limits.max-blocks-per-operation`, the token's
   `maxBlocks`)) and must touch the token's scope; then every position is checked by scope and then
   by region rights; then concurrency (`limits.max-concurrent-operations`) is checked when the edit
   starts. The size cap is checked before positions are scanned (the scan is what it protects), so
   it comes ahead of region rights, not after.

Scope (stored as `scope_json`, empty by default so a new token can do nothing):
```json
{ "worlds": ["world"],
  "box": { "world": "world", "min": [x, y, z], "max": [x, y, z] },
  "actions": ["edit.*", "world.list", "operation.status"],
  "maxBlocks": 50000, "ratePerMinute": 60 }
```
`actions` are tool names, group wildcards (`edit.*`) or `*`. With a `box` the token is confined to it:
positions outside it, in any world, are out of scope. An edit that partly overlaps the scope skips
the outside positions (`skipped.outOfScope`); one that does not touch it is refused. `edit.copy`
needs the whole area inside the scope. A token may read regions that touch its scope and change
only regions that lie entirely inside it; `region.create` needs the whole new region inside it. A scope
change applies to running operations at their next batch.

Region rights: an agent is not a player, so regions treat it as a non-member of everything, always
(there is no agent membership in v1). It is therefore stopped by `build` flags aimed at non-members
or everyone, including on regions it created. It never has bypass.

- Selections and clipboards are per token, in memory, dropped when the token is revoked or on restart.
- Audit: every call to a changing tool (`edit.set`, `edit.replace`, `edit.paste`, `edit.undo`,
  `region.*`, `flag.*`, `member.*`, `operation.cancel`) that passes the rate limit writes an audit row,
  refused calls included, with the reason. An accepted edit writes its row when it finishes (with
  its final status) rather than at the call. Dry runs are audited only if `audit.log-dry-runs` is true.
  `edit.copy` and `selection.set` change nothing outside the token's own state and are not audited.
- Tokens: 256-bit random secret (`sv_` plus base64url), shown once, stored as a SHA-256 hash plus its
  first 9 characters for display, optional expiry, revocable at once. Revoking takes effect on the
  next call, asks the token's running operations to stop (they finish as `cancelled`; applied
  batches stay undoable) and then saves. If the save fails the token stays revoked until restart and the
  admin is told. Secrets are only ever shown in game: `/sv agent token create` refuses the console,
  because the console is written to the server log.
- Failed authentication is counted, not stored: per token prefix of a real token, or `unknown`, in
  memory. `/sv status` shows the totals and the log gets at most one warning a minute, so nobody who
  can reach the port can grow the database or the log. Counts reset on restart.
- No remote binding in v1 (see Bind above; TLS itself and per-token region membership are post-v1).

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
  log-dry-runs: false
defaults:
  build: allow
  interact: allow
  pvp: allow
  mob-spawning: allow
  entry: allow
```
`messages.yml`: all player-facing text. Hot reload via `/sv reload` for both files, except `server.*`
and `storage.*`, which need a restart (the reload says so). A message missing from an older
`messages.yml` falls back to the bundled text.

## 9. Data model (SQLite, WAL)

All writes happen off the region threads. Schema is versioned with forward-only migrations.

- `schema_version(version)`
- `regions(id, world, name, min_x, min_y, min_z, max_x, max_y, max_z, priority, created_at)`
- `region_members(region_id, subject_type, subject_id, role)` subject: player UUID
- `region_flags(region_id, flag, value, audience)`
- `agent_tokens(id, name, secret_hash, secret_prefix, scope_json, created_by, created_at, expires_at,
  revoked_at)`
- `operations(id, actor_type, actor_id, kind, world, status, params_json, reason, blocks_planned,
  blocks_changed, skipped_protected, skipped_out_of_scope, regions_affected, created_at, finished_at,
  undone_by)` (`actor_type` is `player` or `agent`; for agents `actor_id` is the token id) ids look like `op_` plus 14 base 36 characters that sort in
  creation order
- `undo_journal(operation_id, chunk_x, chunk_z, seq, data)` previous block states per chunk batch
- `audit_log(id, at, actor_type, actor_id, action, target, operation_id, result, reason)`
- `idempotency(token_id, key, request_hash, response_json, created_at)` results kept for 24 hours,
  pruned at startup

Undo journal writes for a batch are committed before that batch is applied to the world.

Retention: `undo.retention-days` deletes the journal rows (not the operation row) of operations that
are `done`, `failed` or `cancelled` and finished before the cutoff; `planned` and `running` are never
touched. `audit.retention-days` deletes old `audit_log` rows. Both run on the database thread in
steps of at most 1000 rows, a minute after start and every 6 hours; 0 keeps everything. An undo of
an operation whose journal is gone is refused (`edit.error.journal-expired`, agents get `CONFLICT`).
SQLite reuses freed pages but the file does not shrink.

Migration 1 (`001_baseline.sql`) creates all the tables above. The runner owns `schema_version`.
Migration 2 (`002_undo_marker.sql`) adds `operations.undone_by` and an index on
`operations(actor_type, actor_id, created_at)`. Migration 3 (`003_agents.sql`) adds `operations.blocks_planned`, `skipped_protected`,
`skipped_out_of_scope` and `regions_affected`, `audit_log.reason`, `agent_tokens.secret_prefix`, the
`idempotency` table and indexes on `audit_log`. Later schema changes are new numbered migrations; a database newer than the plugin is refused.

## 10. Milestones

1. Skeleton: Gradle modules, plugin.yml (`folia-supported`), SQLite and migrations, config and
   messages, `/sv status`, CI build, test server task.
2. Regions: model, spatial index, flags, member roles, protection listeners, `/sv region ...`.
3. Editing: selection, wand, chunked Folia-safe executor, `set`/`replace`/`copy`/`paste`, undo.
4. Agent interface: tokens, scope, rate limits, MCP server, tools, dry run, audit log.
5. Hardening (built; see CHANGELOG for what is still untested): load tests on a Folia test server, crash-recovery tests for the undo journal,
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

0. BuiltByBit (item 3) is a blocker until read in full: its rules say "substantially AI generated
   content (e.g vibe coding project generation) is forbidden". Surveyor is largely AI-written.
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
