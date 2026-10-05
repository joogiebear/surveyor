package xyz.joogiebear.surveyor.core.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every token, in memory, so an HTTP request is authenticated without touching the database and a
 * revocation takes effect on the very next call. Tokens are few, so lookups scan them all and
 * compare hashes in constant time.
 */
public final class TokenRegistry {

    private final Map<Long, AgentToken> byId = new ConcurrentHashMap<>();

    public void replaceAll(Collection<AgentToken> tokens) {
        byId.clear();
        tokens.forEach(this::put);
    }

    public void put(AgentToken token) {
        byId.put(token.id(), token);
    }

    public AgentToken get(long id) {
        return byId.get(id);
    }

    public AgentToken findByName(String name) {
        return byId.values().stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public List<AgentToken> list() {
        return byId.values().stream().sorted(Comparator.comparing(t -> t.name().toLowerCase(Locale.ROOT))).toList();
    }

    /** The token that owns this secret, or null. The caller still checks {@link AgentToken#status}. */
    public AgentToken authenticate(String secret) {
        byte[] wanted = TokenSecrets.hash(secret).getBytes(StandardCharsets.UTF_8);
        AgentToken found = null;
        for (AgentToken token : byId.values()) {
            if (MessageDigest.isEqual(wanted, token.secretHash().getBytes(StandardCharsets.UTF_8))) {
                found = token;
            }
        }
        return found;
    }
}
