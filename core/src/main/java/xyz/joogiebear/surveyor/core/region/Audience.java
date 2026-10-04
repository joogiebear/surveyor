package xyz.joogiebear.surveyor.core.region;

import java.util.Optional;

/** Who a flag value applies to. Owners count as members; a null role means not a member. */
public enum Audience {
    EVERYONE("everyone"),
    MEMBERS("members"),
    OWNERS("owners"),
    NON_MEMBERS("non-members");

    private final String key;

    Audience(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public boolean matches(Role role) {
        return switch (this) {
            case EVERYONE -> true;
            case MEMBERS -> role != null;
            case OWNERS -> role == Role.OWNER;
            case NON_MEMBERS -> role == null;
        };
    }

    public static Optional<Audience> fromKey(String key) {
        for (Audience audience : values()) {
            if (audience.key.equalsIgnoreCase(key)) {
                return Optional.of(audience);
            }
        }
        return Optional.empty();
    }
}
