package xyz.joogiebear.surveyor.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final YamlConfiguration yaml;

    private Messages(YamlConfiguration yaml) {
        this.yaml = yaml;
    }

    static Messages load(JavaPlugin plugin) {
        return new Messages(ConfigFiles.load(plugin, "messages.yml"));
    }

    // getString(key) falls back to the bundled defaults; getString(key, default) would not, and an
    // older messages.yml on disk would show raw keys for every message added since.
    String raw(String key) {
        String text = yaml.getString(key);
        return text != null ? text : key;
    }

    Component get(String key, TagResolver... resolvers) {
        return MINI.deserialize(raw("prefix") + raw(key), resolvers);
    }
}
