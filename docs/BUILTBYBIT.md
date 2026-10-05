# BuiltByBit product page (draft, not published)

Do not publish before the open items in SPEC section 12 are closed. In particular BuiltByBit's rules
say substantially AI-generated content (it names vibe-coded project generation) is forbidden; read the
full rules and decide whether this product may be listed.

## Title
Surveyor: Regions and World Editing for Players and AI Agents (Paper / Folia 26.2)

## Summary
One plugin for protecting land and editing blocks, built for Folia. Players use commands and a wand.
AI agents connect over a local MCP server with scoped, rate-limited, audited tokens.

## Features
- Cuboid regions with owners, members, priorities and flags (build, interact, pvp, mob-spawning, entry).
- Selections and a wand; set, replace, copy, paste and undo with weighted patterns.
- Folia-safe: edits are planned off-thread and applied per chunk on the owning region thread within a time budget.
- Crash-safe undo journal; interrupted edits are marked and undoable after a restart.
- Agent interface: MCP over HTTP on 127.0.0.1, typed tools, dry run, idempotency keys, scoped tokens (worlds, box, actions, block cap, rate), audit log.
- SQLite storage, hot-reloadable config and messages.

## Requirements
Paper or Folia 26.2, Java 25. Internet access once on first start (SQLite driver download).

## Known limitations
Chest/sign/spawner contents are not copied or restored. No redo, schematics, brushes or polygon regions. MCP is loopback only, no TLS.

## AI disclosure
This plugin was developed with AI assistance (Claude by Anthropic) and reviewed by its author.

## Legal checks still needed
Product name and trademark search; wording of any comparison with other plugins (never name them
without a legal check); licence/EULA text for sale (LICENSE is all-rights-reserved).
