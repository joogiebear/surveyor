package xyz.joogiebear.surveyor.paper;

import java.util.EnumMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.joogiebear.surveyor.core.Limits;
import xyz.joogiebear.surveyor.core.region.Flag;

record Settings(boolean mcpEnabled, String mcpBind, int mcpPort, boolean allowRemote, int defaultRatePerMinute,
                boolean requireReason, boolean logDryRuns, Limits limits, String storageFile,
                Map<Flag, Boolean> defaults) {

    static Settings load(JavaPlugin plugin) {
        YamlConfiguration yaml = ConfigFiles.load(plugin, "config.yml");
        String storageType = yaml.getString("storage.type");
        if (!"sqlite".equalsIgnoreCase(storageType)) {
            throw new IllegalArgumentException("storage.type must be sqlite, got " + storageType);
        }
        Limits limits = new Limits(
                yaml.getInt("limits.max-blocks-per-operation"),
                yaml.getInt("limits.tick-budget-ms"),
                yaml.getInt("limits.max-concurrent-operations"));
        String file = yaml.getString("storage.file");
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("storage.file must not be empty");
        }
        Map<Flag, Boolean> defaults = new EnumMap<>(Flag.class);
        for (Flag flag : Flag.values()) {
            String value = yaml.getString("defaults." + flag.key());
            if (!"allow".equalsIgnoreCase(value) && !"deny".equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("defaults." + flag.key() + " must be allow or deny, got " + value);
            }
            defaults.put(flag, "allow".equalsIgnoreCase(value));
        }
        int port = yaml.getInt("server.port");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("server.port must be between 1 and 65535, got " + port);
        }
        int rate = yaml.getInt("agents.default-rate-limit-per-minute");
        if (rate < 1) {
            throw new IllegalArgumentException("agents.default-rate-limit-per-minute must be at least 1");
        }
        return new Settings(yaml.getBoolean("server.enabled"), yaml.getString("server.bind", "127.0.0.1"), port,
                yaml.getBoolean("server.allow-remote"), rate, yaml.getBoolean("agents.require-reason"),
                yaml.getBoolean("audit.log-dry-runs"), limits, file, Map.copyOf(defaults));
    }
}
