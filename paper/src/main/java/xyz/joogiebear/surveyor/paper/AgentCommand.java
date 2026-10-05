package xyz.joogiebear.surveyor.paper;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component;
import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import xyz.joogiebear.surveyor.core.agent.AgentActions;
import xyz.joogiebear.surveyor.core.agent.AgentScope;
import xyz.joogiebear.surveyor.core.agent.AgentToken;
import xyz.joogiebear.surveyor.core.agent.TokenSecrets;
import xyz.joogiebear.surveyor.core.region.Cuboid;
import xyz.joogiebear.surveyor.core.region.Region;
import xyz.joogiebear.surveyor.paper.storage.AgentStore;
import xyz.joogiebear.surveyor.paper.storage.RegionException;

/** `/sv agent token create|list|revoke|scope` and `/sv audit`. */
final class AgentCommand {

    static final List<String> TOKEN_SUBCOMMANDS = List.of("create", "list", "revoke", "scope");
    static final List<String> SCOPE_KEYS = List.of("worlds", "box", "actions", "maxblocks", "rate");

    private static final int AUDIT_DEFAULT = 20;
    private static final int AUDIT_MAX = 100;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SurveyorPlugin plugin;

    AgentCommand(SurveyorPlugin plugin) {
        this.plugin = plugin;
    }

    /** @param args everything after `agent` */
    void run(CommandSender sender, String label, String[] args) {
        if (!plugin.database().ready()) {
            sender.sendMessage(plugin.messages().get("error.not-loaded"));
            return;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("token") || args.length == 1) {
            sender.sendMessage(plugin.messages().get("agent.usage", unparsed("label", label)));
            return;
        }
        String[] rest = Arrays.copyOfRange(args, 2, args.length);
        switch (args[1].toLowerCase()) {
            case "create" -> create(sender, label, rest);
            case "list" -> list(sender);
            case "revoke" -> revoke(sender, label, rest);
            case "scope" -> scope(sender, label, rest);
            default -> sender.sendMessage(plugin.messages().get("agent.usage", unparsed("label", label)));
        }
    }

    private void create(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        if (!(sender instanceof Player)) {
            // The console is written to the server log, and the secret must never be.
            sender.sendMessage(messages.get("agent.console-create"));
            return;
        }
        if (args.length < 1 || args.length > 2) {
            sender.sendMessage(messages.get("agent.usage-create", unparsed("label", label)));
            return;
        }
        String name = args[0];
        if (!Region.isValidName(name)) {
            sender.sendMessage(messages.get("agent.bad-name"));
            return;
        }
        Long expiresAt = null;
        if (args.length == 2) {
            try {
                int days = Integer.parseInt(args[1]);
                if (days < 1 || days > 3650) {
                    throw new NumberFormatException();
                }
                expiresAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(days);
            } catch (NumberFormatException e) {
                sender.sendMessage(messages.get("agent.bad-days", unparsed("value", args[1])));
                return;
            }
        }
        TokenSecrets.Generated secret = TokenSecrets.generate();
        plugin.agents().store().create(name, secret.hash(), secret.prefix(), sender.getName(), expiresAt)
                .whenComplete((token, error) -> {
                    if (error != null) {
                        fail(sender, error);
                        return;
                    }
                    plugin.agents().tokens().put(token);
                    audit(sender, "agent.token.create", name, "ok");
                    Component copyable = Component.text(secret.secret())
                            .clickEvent(ClickEvent.copyToClipboard(secret.secret()))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to copy")));
                    plugin.reply(sender, messages.get("agent.created", unparsed("name", name)));
                    plugin.reply(sender, messages.get("agent.secret", component("secret", copyable)));
                    plugin.reply(sender, messages.get("agent.next", unparsed("label", label), unparsed("name", name)));
                });
    }

    private void list(CommandSender sender) {
        Messages messages = plugin.messages();
        List<AgentToken> tokens = plugin.agents().tokens().list();
        if (tokens.isEmpty()) {
            sender.sendMessage(messages.get("agent.list-empty"));
            return;
        }
        long now = System.currentTimeMillis();
        sender.sendMessage(messages.get("agent.list-header", unparsed("count", String.valueOf(tokens.size()))));
        for (AgentToken token : tokens) {
            String expires = token.expiresAt() == null ? messages.raw("agent.never") : TIME.format(Instant.ofEpochMilli(token.expiresAt()));
            sender.sendMessage(messages.get("agent.list-entry", unparsed("name", token.name()), unparsed("prefix", token.prefix()),
                    unparsed("status", token.status(now).name().toLowerCase()), unparsed("expires", expires)));
        }
    }

    private void revoke(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        if (args.length != 1) {
            sender.sendMessage(messages.get("agent.usage-revoke", unparsed("label", label)));
            return;
        }
        AgentToken token = token(sender, args[0]);
        if (token == null) {
            return;
        }
        if (token.revokedAt() != null) {
            sender.sendMessage(messages.get("agent.already-revoked", unparsed("name", token.name())));
            return;
        }
        // In effect at once; the database write follows.
        plugin.agents().revoke(token).whenComplete((ignored, error) -> {
            audit(sender, "agent.token.revoke", token.name(), error == null ? "ok" : "failed");
            if (error != null) {
                plugin.getLogger().severe("Token " + token.name() + " is revoked in memory but the database write failed, "
                        + "so it would be valid again after a restart: " + error);
                plugin.reply(sender, messages.get("agent.revoke-unsaved", unparsed("name", token.name())));
                return;
            }
            plugin.reply(sender, messages.get("agent.revoked", unparsed("name", token.name())));
        });
    }

    private void scope(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        if (args.length == 0) {
            sender.sendMessage(messages.get("agent.usage-scope", unparsed("label", label)));
            return;
        }
        AgentToken token = token(sender, args[0]);
        if (token == null) {
            return;
        }
        if (args.length == 1) {
            show(sender, token);
            return;
        }
        AgentScope current = token.scope();
        AgentScope next;
        try {
            next = switch (args[1].toLowerCase()) {
                case "worlds" -> current.withWorlds(worlds(args));
                case "box" -> current.withBox(box(args));
                case "actions" -> current.withActions(actions(args));
                case "maxblocks" -> current.withMaxBlocks(optionalNumber(args));
                case "rate" -> current.withRatePerMinute(optionalNumber(args));
                default -> null;
            };
        } catch (ScopeProblem problem) {
            sender.sendMessage(messages.get(problem.key, unparsed("value", problem.value)));
            return;
        }
        if (next == null) {
            sender.sendMessage(messages.get("agent.usage-scope", unparsed("label", label)));
            return;
        }
        AgentScope saved = next;
        plugin.agents().store().setScope(token.id(), saved).whenComplete((ignored, error) -> {
            if (error != null) {
                fail(sender, error);
                return;
            }
            AgentToken changed = plugin.agents().tokens().get(token.id()).withScope(saved);
            plugin.agents().tokens().put(changed);
            audit(sender, "agent.token.scope", token.name() + " " + args[1].toLowerCase(), "ok");
            plugin.reply(sender, messages.get("agent.scope-set", unparsed("name", token.name())));
            describe(sender, changed);
        });
    }

    private void show(CommandSender sender, AgentToken token) {
        sender.sendMessage(plugin.messages().get("agent.scope-header", unparsed("name", token.name())));
        describe(sender, token);
    }

    private void describe(CommandSender sender, AgentToken token) {
        Messages messages = plugin.messages();
        AgentScope scope = token.scope();
        String none = messages.raw("agent.none");
        String box = scope.box() == null ? none : scope.box().world() + ": " + scope.box().area().minX() + " "
                + scope.box().area().minY() + " " + scope.box().area().minZ() + " to " + scope.box().area().maxX() + " "
                + scope.box().area().maxY() + " " + scope.box().area().maxZ();
        String defaults = messages.raw("agent.default");
        for (var line : List.of(
                messages.get("agent.scope-worlds", unparsed("value", join(scope.worlds(), none))),
                messages.get("agent.scope-box", unparsed("value", box)),
                messages.get("agent.scope-actions", unparsed("value", join(scope.actions(), none))),
                messages.get("agent.scope-maxblocks", unparsed("value", scope.maxBlocks() == null
                        ? defaults + " (" + plugin.settings().limits().maxBlocksPerOperation() + ")" : String.valueOf(scope.maxBlocks()))),
                messages.get("agent.scope-rate", unparsed("value", scope.ratePerMinute() == null
                        ? defaults + " (" + plugin.settings().defaultRatePerMinute() + ")" : String.valueOf(scope.ratePerMinute()))))) {
            sender.sendMessage(line);
        }
    }

    private static String join(Set<String> values, String none) {
        return values.isEmpty() ? none : String.join(", ", values.stream().sorted().toList());
    }

    private Set<String> worlds(String[] args) {
        if (args.length != 3) {
            throw new ScopeProblem("agent.usage-scope-worlds", "");
        }
        Set<String> worlds = new LinkedHashSet<>();
        if (!args[2].equalsIgnoreCase("none")) {
            for (String name : args[2].split(",")) {
                if (Bukkit.getWorld(name) == null) {
                    throw new ScopeProblem("error.unknown-world", name);
                }
                worlds.add(name);
            }
        }
        return worlds;
    }

    private AgentScope.Box box(String[] args) {
        if (args.length == 3 && args[2].equalsIgnoreCase("clear")) {
            return null;
        }
        if (args.length != 9) {
            throw new ScopeProblem("agent.usage-scope-box", "");
        }
        if (Bukkit.getWorld(args[2]) == null) {
            throw new ScopeProblem("error.unknown-world", args[2]);
        }
        int[] c = new int[6];
        for (int i = 0; i < 6; i++) {
            try {
                c[i] = Integer.parseInt(args[3 + i]);
            } catch (NumberFormatException e) {
                throw new ScopeProblem("error.bad-number", args[3 + i]);
            }
        }
        return new AgentScope.Box(args[2], Cuboid.of(c[0], c[1], c[2], c[3], c[4], c[5]));
    }

    private Set<String> actions(String[] args) {
        if (args.length != 3) {
            throw new ScopeProblem("agent.usage-scope-actions", "");
        }
        Set<String> actions = new LinkedHashSet<>();
        if (!args[2].equalsIgnoreCase("none")) {
            for (String action : args[2].split(",")) {
                if (!AgentActions.isValid(action)) {
                    throw new ScopeProblem("agent.bad-action", action);
                }
                actions.add(action);
            }
        }
        return actions;
    }

    private Integer optionalNumber(String[] args) {
        if (args.length != 3) {
            throw new ScopeProblem("agent.usage-scope-number", "");
        }
        if (args[2].equalsIgnoreCase("default")) {
            return null;
        }
        try {
            int value = Integer.parseInt(args[2]);
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ScopeProblem("error.bad-number", args[2]);
        }
    }

    private AgentToken token(CommandSender sender, String name) {
        AgentToken token = plugin.agents().tokens().findByName(name);
        if (token == null) {
            sender.sendMessage(plugin.messages().get("agent.unknown", unparsed("value", name)));
        }
        return token;
    }

    /** `/sv audit [token <name>] [limit]` */
    void audit(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.messages();
        if (!plugin.database().ready()) {
            sender.sendMessage(messages.get("error.not-loaded"));
            return;
        }
        String actorId = null;
        int limit = AUDIT_DEFAULT;
        try {
            int at = 0;
            if (args.length >= 2 && args[0].equalsIgnoreCase("token")) {
                AgentToken token = token(sender, args[1]);
                if (token == null) {
                    return;
                }
                actorId = String.valueOf(token.id());
                at = 2;
            }
            if (args.length > at + 1) {
                throw new NumberFormatException();
            }
            if (args.length == at + 1) {
                limit = Math.max(1, Math.min(AUDIT_MAX, Integer.parseInt(args[at])));
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(messages.get("audit.usage", unparsed("label", label)));
            return;
        }
        plugin.agents().store().audit(new AgentStore.AuditFilter(actorId == null ? null : "agent", actorId, null, null, null, limit))
                .whenComplete((rows, error) -> {
                    if (error != null) {
                        fail(sender, error);
                        return;
                    }
                    if (rows.isEmpty()) {
                        plugin.reply(sender, messages.get("audit.empty"));
                        return;
                    }
                    plugin.reply(sender, messages.get("audit.header", unparsed("count", String.valueOf(rows.size()))));
                    for (AgentStore.AuditRow row : rows) {
                        plugin.reply(sender, messages.get("audit.entry", unparsed("time", TIME.format(Instant.ofEpochMilli(row.at()))),
                                unparsed("actor", actorName(row)), unparsed("action", row.action()),
                                unparsed("target", row.target() == null ? "" : row.target()), unparsed("result", row.result()),
                                unparsed("reason", row.reason() == null ? "" : " - " + row.reason())));
                    }
                });
    }

    private static String actorName(AgentStore.AuditRow row) {
        if (row.actorName() != null) {
            return "agent:" + row.actorName();
        }
        try {
            String name = Bukkit.getOfflinePlayer(UUID.fromString(row.actorId())).getName();
            return name != null ? name : row.actorId();
        } catch (IllegalArgumentException e) {
            return row.actorType() + ":" + row.actorId();
        }
    }

    private void audit(CommandSender sender, String action, String target, String result) {
        String actor = sender instanceof Player player ? player.getUniqueId().toString() : "console";
        plugin.edits().store().audit(sender instanceof Player ? "player" : "console", actor, action, target, null, result)
                .exceptionally(error -> {
                    plugin.getLogger().warning("Could not write audit row for " + action + ": " + error.getMessage());
                    return null;
                });
    }

    private void fail(CommandSender sender, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof RegionException) {
            plugin.reply(sender, plugin.messages().get("error." + cause.getMessage()));
            return;
        }
        plugin.getLogger().severe("Agent command failed: " + cause);
        plugin.reply(sender, plugin.messages().get("error.internal"));
    }

    List<String> complete(String[] args) {
        String last = args[args.length - 1].toLowerCase();
        List<String> options = switch (args.length) {
            case 1 -> List.of("token");
            case 2 -> TOKEN_SUBCOMMANDS;
            case 3 -> switch (args[1].toLowerCase()) {
                case "revoke", "scope" -> plugin.agents().tokens().list().stream().map(AgentToken::name).toList();
                default -> List.of();
            };
            case 4 -> args[1].equalsIgnoreCase("scope") ? SCOPE_KEYS : List.of();
            case 5 -> args[1].equalsIgnoreCase("scope") && args[3].equalsIgnoreCase("box")
                    ? Bukkit.getWorlds().stream().map(world -> world.getName()).toList() : List.of();
            default -> List.of();
        };
        return options.stream().filter(o -> o.toLowerCase().startsWith(last)).toList();
    }

    private static final class ScopeProblem extends RuntimeException {

        private final String key;
        private final String value;

        ScopeProblem(String key, String value) {
            super(key, null, false, false);
            this.key = key;
            this.value = value;
        }
    }
}
