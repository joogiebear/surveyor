package xyz.joogiebear.surveyor.core.agent;

/**
 * A token as stored: only the hash of the secret, plus the first characters of the secret so an
 * admin can tell tokens apart.
 */
public record AgentToken(long id, String name, String secretHash, String prefix, AgentScope scope,
                         String createdBy, long createdAt, Long expiresAt, Long revokedAt) {

    public enum Status { ACTIVE, EXPIRED, REVOKED }

    public Status status(long now) {
        if (revokedAt != null) {
            return Status.REVOKED;
        }
        return expiresAt != null && now >= expiresAt ? Status.EXPIRED : Status.ACTIVE;
    }

    public AgentToken withScope(AgentScope next) {
        return new AgentToken(id, name, secretHash, prefix, next, createdBy, createdAt, expiresAt, revokedAt);
    }

    public AgentToken revoked(long at) {
        return new AgentToken(id, name, secretHash, prefix, scope, createdBy, createdAt, expiresAt, at);
    }
}
