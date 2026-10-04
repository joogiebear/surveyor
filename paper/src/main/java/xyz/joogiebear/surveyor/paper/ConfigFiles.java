package xyz.joogiebear.surveyor.paper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class ConfigFiles {

    private ConfigFiles() {
    }

    /** Reads a yml from the data folder, falling back to the bundled copy for missing keys. */
    static YamlConfiguration load(JavaPlugin plugin, String name) {
        Path file = plugin.getDataFolder().toPath().resolve(name);
        YamlConfiguration yaml;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            yaml = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + name + ": " + e.getMessage(), e);
        }
        try (InputStream bundled = plugin.getResource(name)) {
            if (bundled != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(bundled, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read bundled " + name, e);
        }
        return yaml;
    }
}
