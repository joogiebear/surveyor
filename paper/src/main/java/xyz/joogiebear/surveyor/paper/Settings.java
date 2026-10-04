package xyz.joogiebear.surveyor.paper;

import java.util.EnumMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.joogiebear.surveyor.core.Limits;
import xyz.joogiebear.surveyor.core.region.Flag;

record Settings(boolean mcpEnabled, Limits limits, String storageFile, Map<Flag, Boolean> defaults) {

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
        return new Settings(yaml.getBoolean("server.enabled"), limits, file, Map.copyOf(defaults));
    }
}
