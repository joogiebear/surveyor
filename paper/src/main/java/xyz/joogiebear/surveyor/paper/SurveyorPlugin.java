package xyz.joogiebear.surveyor.paper;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.joogiebear.surveyor.core.agent.BindPolicy;
import xyz.joogiebear.surveyor.mcp.McpHttpServer;
import xyz.joogiebear.surveyor.paper.storage.AgentStore;
import xyz.joogiebear.surveyor.paper.storage.Database;
import xyz.joogiebear.surveyor.paper.storage.EditStore;
import xyz.joogiebear.surveyor.paper.storage.RegionStore;

public final class SurveyorPlugin extends JavaPlugin {

    private volatile Settings settings;
    private volatile Messages messages;
    private Database database;
    private RegionStore regions;
    private EditService edits;
    private AgentApi agents;
    private Protection protection;
    private McpHttpServer mcp;
    private volatile String mcpFailure;
    private boolean folia;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!Files.exists(getDataFolder().toPath().resolve("messages.yml"))) {
            saveResource("messages.yml", false);
        }
        try {
            settings = Settings.load(this);
            messages = Messages.load(this);
        } catch (RuntimeException e) {
            getLogger().severe("Invalid configuration, disabling: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        folia = detectFolia();

        database = new Database();
        regions = new RegionStore(database);
        edits = new EditService(this, new EditStore(database));
        agents = new AgentApi(this, new AgentStore(database));
        protection = new Protection(this, regions);
        Path file = getDataFolder().toPath().resolve(settings.storageFile());
        CompletableFuture<Integer> loaded = database.open(file)
                .thenCompose(ignored -> {
                    getLogger().info("Database " + database.status());
                    return regions.load();
                });
        loaded.whenComplete((count, error) -> {
                    if (error != null) {
                        getLogger().severe("Storage failed to start, region protection is not active: "
                                + (error.getCause() != null ? error.getCause().getMessage() : error.getMessage()));
                    } else {
                        getLogger().info("Loaded " + count + " regions");
                        new Maintenance(this, protection).start();
                        edits.recover().exceptionally(failure -> {
                            getLogger().severe("Could not check for interrupted edits: " + failure.getMessage());
                            return null;
                        });
                    }
                });
        // The tokens are loaded before the server starts, so nothing is ever served without them.
        loaded.thenCompose(ignored -> agents.load()).whenComplete((tokens, error) -> {
            if (error != null) {
                mcpFailure = "storage failed to start";
                return;
            }
            getLogger().info("Loaded " + tokens + " agent tokens");
            getServer().getAsyncScheduler().runNow(this, task -> startMcp());
        });

        getServer().getPluginManager().registerEvents(new ProtectionListener(protection), this);
        getServer().getPluginManager().registerEvents(new EditListener(this), this);
        getCommand("sv").setExecutor(new SvCommand(this));
        getLogger().info("Surveyor " + getPluginMeta().getVersion() + " enabled on "
                + getServer().getName() + (folia ? " (Folia)" : ""));
    }

    @Override
    public void onDisable() {
        if (mcp != null) {
            mcp.stop();
        }
        if (edits != null) {
            edits.shutdown();
        }
        if (protection != null) {
            protection.flushBypasses(true);
        }
        if (database != null) {
            database.close();
        }
    }

    Settings settings() {
        return settings;
    }

    Messages messages() {
        return messages;
    }

    Database database() {
        return database;
    }

    RegionStore regions() {
        return regions;
    }

    EditService edits() {
        return edits;
    }

    AgentApi agents() {
        return agents;
    }

    /** The address the MCP server listens on, or null when it is not running. */
    String mcpAddress() {
        McpHttpServer running = mcp;
        return running == null ? null : running.address();
    }

    /** Why the MCP server is not running, or null when it is, or when it is switched off. */
    String mcpFailure() {
        return mcpFailure;
    }

    private void startMcp() {
        if (!settings.mcpEnabled()) {
            return;
        }
        try {
            // TLS is not built, so a remote bind is always refused.
            InetAddress address = BindPolicy.resolve(settings.mcpBind(), settings.allowRemote(), false);
            McpHttpServer server = new McpHttpServer(address, settings.mcpPort(), agents, getPluginMeta().getVersion(), getLogger());
            server.start();
            mcp = server;
            getLogger().info("MCP server listening on http://" + server.address() + "/mcp");
        } catch (IllegalArgumentException e) {
            mcpFailure = e.getMessage();
            getLogger().severe("The MCP server was not started: " + e.getMessage());
        } catch (IOException e) {
            mcpFailure = "could not bind " + settings.mcpBind() + ":" + settings.mcpPort() + " (" + e.getMessage() + ")";
            getLogger().severe("The MCP server was not started: " + mcpFailure);
        }
    }

    boolean folia() {
        return folia;
    }

    /** Reloads config.yml and messages.yml off the region threads and reports back to the sender. */
    void reload(CommandSender sender) {
        getServer().getAsyncScheduler().runNow(this, task -> {
            try {
                Settings next = Settings.load(this);
                Messages nextMessages = Messages.load(this);
                boolean storageChanged = !next.storageFile().equals(settings.storageFile());
                boolean serverChanged = next.mcpEnabled() != settings.mcpEnabled() || next.mcpPort() != settings.mcpPort()
                        || !next.mcpBind().equals(settings.mcpBind()) || next.allowRemote() != settings.allowRemote();
                settings = next;
                messages = nextMessages;
                getLogger().info("Configuration and messages reloaded by " + sender.getName());
                reply(sender, messages.get("reload.success"));
                if (storageChanged) {
                    reply(sender, messages.get("reload.storage-changed"));
                }
                if (serverChanged) {
                    reply(sender, messages.get("reload.server-changed"));
                }
            } catch (RuntimeException e) {
                getLogger().warning("Reload failed, keeping the previous settings: " + e.getMessage());
                reply(sender, messages.get("reload.failed", unparsed("reason", String.valueOf(e.getMessage()))));
            }
        });
    }

    /** Messages to a player must go through that player's own scheduler on Folia. */
    void reply(CommandSender sender, Component message) {
        if (sender instanceof Entity entity) {
            entity.getScheduler().run(this, task -> sender.sendMessage(message), null);
        } else {
            sender.sendMessage(message);
        }
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
