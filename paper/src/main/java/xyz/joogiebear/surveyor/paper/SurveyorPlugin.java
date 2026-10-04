package xyz.joogiebear.surveyor.paper;

import static net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed;

import java.nio.file.Files;
import java.nio.file.Path;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.joogiebear.surveyor.paper.storage.Database;
import xyz.joogiebear.surveyor.paper.storage.EditStore;
import xyz.joogiebear.surveyor.paper.storage.RegionStore;

public final class SurveyorPlugin extends JavaPlugin {

    private volatile Settings settings;
    private volatile Messages messages;
    private Database database;
    private RegionStore regions;
    private EditService edits;
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
        Path file = getDataFolder().toPath().resolve(settings.storageFile());
        database.open(file)
                .thenCompose(ignored -> {
                    getLogger().info("Database " + database.status());
                    return regions.load();
                })
                .whenComplete((count, error) -> {
                    if (error != null) {
                        getLogger().severe("Storage failed to start, region protection is not active: "
                                + (error.getCause() != null ? error.getCause().getMessage() : error.getMessage()));
                    } else {
                        getLogger().info("Loaded " + count + " regions");
                        edits.recover().exceptionally(failure -> {
                            getLogger().severe("Could not check for interrupted edits: " + failure.getMessage());
                            return null;
                        });
                    }
                });

        getServer().getPluginManager().registerEvents(new ProtectionListener(new Protection(this, regions)), this);
        getServer().getPluginManager().registerEvents(new EditListener(this), this);
        getCommand("sv").setExecutor(new SvCommand(this));
        getLogger().info("Surveyor " + getPluginMeta().getVersion() + " enabled on "
                + getServer().getName() + (folia ? " (Folia)" : ""));
    }

    @Override
    public void onDisable() {
        if (edits != null) {
            edits.shutdown();
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
                settings = next;
                messages = nextMessages;
                getLogger().info("Configuration and messages reloaded by " + sender.getName());
                reply(sender, messages.get("reload.success"));
                if (storageChanged) {
                    reply(sender, messages.get("reload.storage-changed"));
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
