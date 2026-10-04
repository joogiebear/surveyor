package xyz.joogiebear.surveyor.core.region;

import java.util.Optional;

public enum Role {
    OWNER("owner"),
    MEMBER("member");

    private final String key;

    Role(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<Role> fromKey(String key) {
        for (Role role : values()) {
            if (role.key.equalsIgnoreCase(key)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
