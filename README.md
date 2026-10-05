# Surveyor

Region protection and world editing for AI agents and developers, as one Paper + Folia plugin.
Players use commands and a wand. AI agents use an MCP server and typed JSON operations. Both go
through the same permission, region, limit and audit checks.

Status: feature complete for 1.0, in pre-release testing. Design: [docs/SPEC.md](docs/SPEC.md).

## Requirements

- Paper or Folia 26.2
- Java 25

## Build

Needs a JDK 25. The Gradle wrapper downloads Gradle 9.8.0.

```
./gradlew build
```

The plugin jar is `paper/build/libs/Surveyor-<version>.jar`.

## Install

1. Put the jar in the server's `plugins` folder.
2. Start the server. Paper downloads the SQLite driver on first start, so the server needs
   internet access to Maven Central once.
3. Edit `plugins/Surveyor/config.yml` and `messages.yml`, then run `/sv reload`.

## Commands

| Command | Permission | Purpose |
|---|---|---|
| `/sv status` | `surveyor.admin` | Versions, Folia, database, MCP state, failed MCP logins |
| `/sv reload` | `surveyor.admin` | Reload `config.yml` and `messages.yml` (`server.*`, `storage.*` need a restart) |
| `/sv wand`, `pos1`, `pos2`, `sel [clear]` | `surveyor.edit.select` | Select an area |
| `/sv set <pattern>`, `replace <from> <to>`, `cancel [id]` | `surveyor.edit.modify` | Edit blocks (`strict` as last word refuses if any block is protected) |
| `/sv copy`, `paste` | `surveyor.edit.clipboard` | Clipboard, kept in memory only |
| `/sv undo [id]` | `surveyor.edit.undo` | Undo your newest edit, or a named one (others' needs `surveyor.admin`) |
| `/sv region <create\|delete\|info\|list\|addmember\|removemember\|owner\|flag\|priority>` | `surveyor.region.manage` | Regions and flags (`build`, `interact`, `pvp`, `mob-spawning`, `entry`) |
| `/sv agent token <create\|list\|revoke\|scope>` | `surveyor.agent.admin` | Tokens for AI agents |
| `/sv audit [token <name>] [count]` | `surveyor.audit` | Recent audit entries |

`surveyor.bypass.region` ignores region protection; overrides are audited (bursts are summarised
into one row a minute). Patterns: `stone`, `50%stone,50%andesite`, `oak_stairs[facing=north]`.
All permissions default to op.

## Configuration

`plugins/Surveyor/config.yml`: `server.*` (MCP bind/port; only loopback is accepted),
`limits.max-blocks-per-operation` (250000), `limits.tick-budget-ms` (8), `limits.max-concurrent-operations` (4),
`agents.default-rate-limit-per-minute`, `agents.require-reason`, `undo.retention-days` (14),
`audit.retention-days` (90; 0 keeps forever), `audit.log-dry-runs`, and `defaults.<flag>`.
All text is in `messages.yml`.

## Agent quickstart

1. In game: `/sv agent token create bot 30`, copy the secret (shown once).
2. Give it rights, it starts with none: `/sv agent token scope bot worlds world`, then `actions edit.*,world.list,operation.status`,
   optionally `box world x1 y1 z1 x2 y2 z2`, `maxblocks 50000`, `rate 60`.
3. Point an MCP client at `http://127.0.0.1:8765/mcp` with header `Authorization: Bearer <secret>`.
4. Call a tool with `dryRun: true`, check the plan, repeat without it. Edits return an `operationId`; poll
   `operation.status`; undo with `edit.undo`. Changes need a `reason`.

## Limits and known gaps

- **Chest, sign, spawner and banner contents are not copied, pasted or restored by undo.** Overwriting
  a container destroys what is inside. Block states (facing, waterlogged, ...) are kept.
- Redo, rotation/flip, schematics, brushes and non-cuboid regions are not included.
- Not protected yet: explosions, pistons, fire, fluid flow, tree growth, vehicles, hanging entities, non-player damage.
- The MCP server is loopback only and has no TLS. Agents are never region members.
- Undo journals are deleted after `undo.retention-days`; the SQLite file does not shrink.
- Failed-login counts and rate limits reset on restart.

## AI assistance

This project is developed with AI assistance (Claude by Anthropic) and reviewed by its author.

## License

Proprietary. All rights reserved. See [LICENSE](LICENSE).
