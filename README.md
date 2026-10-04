# Surveyor

Region protection and world editing for AI agents and developers, as one Paper + Folia plugin.
Players use commands and a wand. AI agents use an MCP server and typed JSON operations. Both go
through the same permission, region, limit and audit checks.

Status: early development. Regions and protection work; editing and the agent interface are planned; see [docs/SPEC.md](docs/SPEC.md).

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
| `/sv status` | `surveyor.admin` | Plugin and server versions, Folia detection, database and MCP state |
| `/sv reload` | `surveyor.admin` | Reload `config.yml` and `messages.yml` |
| `/sv region <create\|delete\|info\|list\|addmember\|removemember\|owner\|flag\|priority>` | `surveyor.region.manage` | Manage regions and their flags |

`surveyor.bypass.region` lets a player ignore region protection; every override is audited.

## AI assistance

This project is developed with AI assistance (Claude by Anthropic) and reviewed by its author.

## License

Proprietary. All rights reserved. See [LICENSE](LICENSE).
