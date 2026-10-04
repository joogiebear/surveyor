package xyz.joogiebear.surveyor.paper;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

final class SvCommand implements TabExecutor {

    private static final String PERMISSION = "surveyor.admin";
    private static final List<String> SUBCOMMANDS = List.of("status", "reload");

    private final SurveyorPlugin plugin;

    SvCommand(SurveyorPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages messages = plugin.messages();
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(messages.get("no-permission"));
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "status" -> status(sender, messages);
            case "reload" -> plugin.reload(sender);
            default -> sender.sendMessage(messages.get("usage", unparsed("label", label)));
        }
        return true;
    }

    private void status(CommandSender sender, Messages messages) {
        String mcp = messages.raw(plugin.settings().mcpEnabled() ? "status.mcp-on" : "status.mcp-off");
        sender.sendMessage(messages.get("status.version", unparsed("version", plugin.getPluginMeta().getVersion())));
        sender.sendMessage(messages.get("status.server",
                unparsed("software", plugin.getServer().getName()),
                unparsed("server_version", plugin.getServer().getVersion())));
        sender.sendMessage(messages.get("status.folia",
                unparsed("folia", messages.raw(plugin.folia() ? "answer-yes" : "answer-no"))));
        sender.sendMessage(messages.get("status.database", unparsed("database", plugin.database().status())));
        sender.sendMessage(messages.get("status.mcp", unparsed("mcp", mcp)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION) || args.length != 1) {
            return List.of();
        }
        return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
    }
}
