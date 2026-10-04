package xyz.joogiebear.surveyor.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.joogiebear.surveyor.core.Limits;

record Settings(boolean mcpEnabled, Limits limits, String storageFile) {

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
        return new Settings(yaml.getBoolean("server.enabled"), limits, file);
    }
}
