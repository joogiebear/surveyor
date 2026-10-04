package xyz.joogiebear.surveyor.core.region;

import java.util.Optional;

public enum Flag {
    BUILD("build"),
    INTERACT("interact"),
    PVP("pvp"),
    MOB_SPAWNING("mob-spawning"),
    ENTRY("entry");

    private final String key;

    Flag(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<Flag> fromKey(String key) {
        for (Flag flag : values()) {
            if (flag.key.equalsIgnoreCase(key)) {
                return Optional.of(flag);
            }
        }
        return Optional.empty();
    }
}
