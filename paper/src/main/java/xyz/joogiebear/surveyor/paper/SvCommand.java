package xyz.joogiebear.surveyor.paper;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

final class SvCommand implements TabExecutor {

    private static final String ADMIN = "surveyor.admin";
    private static final String REGION = "surveyor.region.manage";
    private static final String SELECT = "surveyor.edit.select";
    private static final String MODIFY = "surveyor.edit.modify";
    private static final String CLIPBOARD = "surveyor.edit.clipboard";
    private static final String UNDO = "surveyor.edit.undo";
    private static final String AGENT = "surveyor.agent.admin";
    private static final String AUDIT = "surveyor.audit";

    private static final Map<String, String> PERMISSIONS = new LinkedHashMap<>();

    static {
        PERMISSIONS.put("status", ADMIN);
        PERMISSIONS.put("reload", ADMIN);
        PERMISSIONS.put("region", REGION);
        PERMISSIONS.put("agent", AGENT);
        PERMISSIONS.put("audit", AUDIT);
        for (String sub : List.of("wand", "pos1", "pos2", "sel")) {
            PERMISSIONS.put(sub, SELECT);
        }
        PERMISSIONS.put("set", MODIFY);
        PERMISSIONS.put("replace", MODIFY);
        PERMISSIONS.put("cancel", MODIFY);
        PERMISSIONS.put("copy", CLIPBOARD);
        PERMISSIONS.put("paste", CLIPBOARD);
        PERMISSIONS.put("undo", UNDO);
    }

    private final SurveyorPlugin plugin;
    private final RegionCommand regionCommand;
    private final EditCommand editCommand;
    private final AgentCommand agentCommand;

    SvCommand(SurveyorPlugin plugin) {
        this.plugin = plugin;
        this.regionCommand = new RegionCommand(plugin);
        this.editCommand = new EditCommand(plugin);
        this.agentCommand = new AgentCommand(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages messages = plugin.messages();
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        String needed = PERMISSIONS.get(sub);
        if (needed != null && !sender.hasPermission(needed)) {
            sender.sendMessage(messages.get("no-permission"));
            return true;
        }
        switch (sub) {
            case "status" -> status(sender, messages);
            case "reload" -> plugin.reload(sender);
            case "region" -> regionCommand.run(sender, label, Arrays.copyOfRange(args, 1, args.length));
            case "agent" -> agentCommand.run(sender, label, Arrays.copyOfRange(args, 1, args.length));
            case "audit" -> agentCommand.audit(sender, label, Arrays.copyOfRange(args, 1, args.length));
            default -> {
                if (needed != null) {
                    editCommand.run(sender, label, args);
                } else {
                    sender.sendMessage(messages.get("usage", unparsed("label", label)));
                }
            }
        }
        return true;
    }

    private void status(CommandSender sender, Messages messages) {
        String address = plugin.mcpAddress();
        String mcp = address != null ? messages.raw("status.mcp-listening").replace("<address>", address)
                : plugin.mcpFailure() != null ? messages.raw("status.mcp-failed").replace("<reason>", plugin.mcpFailure())
                : messages.raw(plugin.settings().mcpEnabled() ? "status.mcp-starting" : "status.mcp-off");
        String regions = plugin.regions().loaded()
                ? String.valueOf(plugin.regions().snapshot().size()) : messages.raw("status.regions-not-loaded");
        sender.sendMessage(messages.get("status.version", unparsed("version", plugin.getPluginMeta().getVersion())));
        sender.sendMessage(messages.get("status.server",
                unparsed("software", plugin.getServer().getName()),
                unparsed("server_version", plugin.getServer().getVersion())));
        sender.sendMessage(messages.get("status.folia",
                unparsed("folia", messages.raw(plugin.folia() ? "answer-yes" : "answer-no"))));
        sender.sendMessage(messages.get("status.database", unparsed("database", plugin.database().status())));
        sender.sendMessage(messages.get("status.regions", unparsed("regions", regions)));
        sender.sendMessage(messages.get("status.mcp", unparsed("mcp", mcp)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String last = args[args.length - 1].toLowerCase();
        if (args.length == 1) {
            return PERMISSIONS.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(last) && sender.hasPermission(e.getValue()))
                    .map(Map.Entry::getKey).toList();
        }
        if (args[0].equalsIgnoreCase("region") && sender.hasPermission(REGION)) {
            return regionCommand.complete(sender, Arrays.copyOfRange(args, 1, args.length));
        }
        if (args[0].equalsIgnoreCase("agent") && sender.hasPermission(AGENT)) {
            return agentCommand.complete(Arrays.copyOfRange(args, 1, args.length));
        }
        String needed = PERMISSIONS.get(args[0].toLowerCase());
        if (needed != null && EditCommand.SUBCOMMANDS.contains(args[0].toLowerCase()) && sender.hasPermission(needed)) {
            return editCommand.complete(sender, args);
        }
        return List.of();
    }
}
