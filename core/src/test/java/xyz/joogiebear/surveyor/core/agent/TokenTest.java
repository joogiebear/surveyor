package xyz.joogiebear.surveyor.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TokenTest {

    private static AgentToken token(long id, String name, TokenSecrets.Generated secret, Long expiresAt, Long revokedAt) {
        return new AgentToken(id, name, secret.hash(), secret.prefix(), AgentScope.NONE, "console", 1000, expiresAt, revokedAt);
    }

    @Test
    void secretsAreLongRandomAndOnlyTheirHashIsKept() {
        TokenSecrets.Generated a = TokenSecrets.generate();
        TokenSecrets.Generated b = TokenSecrets.generate();
        assertNotEquals(a.secret(), b.secret());
        assertTrue(a.secret().startsWith("sv_"));
        // 32 bytes in unpadded base64url is 43 characters.
        assertEquals(3 + 43, a.secret().length());
        assertEquals(64, a.hash().length());
        assertEquals(a.hash(), TokenSecrets.hash(a.secret()));
        assertFalse(a.hash().contains(a.secret()));
        assertTrue(a.secret().startsWith(a.prefix()));
        assertEquals(9, a.prefix().length());
    }

    @Test
    void hashMatchesKnownSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", TokenSecrets.hash("abc"));
    }

    @Test
    void statusFollowsExpiryAndRevocation() {
        TokenSecrets.Generated s = TokenSecrets.generate();
        assertEquals(AgentToken.Status.ACTIVE, token(1, "a", s, null, null).status(5000));
        assertEquals(AgentToken.Status.ACTIVE, token(1, "a", s, 6000L, null).status(5000));
        assertEquals(AgentToken.Status.EXPIRED, token(1, "a", s, 5000L, null).status(5000));
        assertEquals(AgentToken.Status.REVOKED, token(1, "a", s, 9000L, 100L).status(5000));
        assertEquals(AgentToken.Status.REVOKED, token(1, "a", s, null, null).revoked(7).status(5000));
    }

    @Test
    void registryFindsTheOwnerOfASecret() {
        TokenSecrets.Generated one = TokenSecrets.generate();
        TokenSecrets.Generated two = TokenSecrets.generate();
        TokenRegistry registry = new TokenRegistry();
        registry.replaceAll(List.of(token(1, "builder", one, null, null), token(2, "Auditor", two, null, null)));
        assertEquals("builder", registry.authenticate(one.secret()).name());
        assertEquals("Auditor", registry.authenticate(two.secret()).name());
        assertNull(registry.authenticate("sv_nope"));
        assertNull(registry.authenticate(""));
    }

    @Test
    void revocationIsSeenByTheNextLookup() {
        TokenSecrets.Generated s = TokenSecrets.generate();
        TokenRegistry registry = new TokenRegistry();
        registry.put(token(1, "builder", s, null, null));
        registry.put(registry.get(1).revoked(42));
        assertEquals(AgentToken.Status.REVOKED, registry.authenticate(s.secret()).status(100));
    }

    @Test
    void namesAreFoundIgnoringCaseAndListedInOrder() {
        TokenRegistry registry = new TokenRegistry();
        registry.put(token(1, "Zed", TokenSecrets.generate(), null, null));
        registry.put(token(2, "alpha", TokenSecrets.generate(), null, null));
        assertEquals(2, registry.findByName("ALPHA").id());
        assertNull(registry.findByName("missing"));
        assertEquals(List.of("alpha", "Zed"), registry.list().stream().map(AgentToken::name).toList());
    }

    @Test
    void scopeChangeKeepsTheRestOfTheToken() {
        TokenSecrets.Generated s = TokenSecrets.generate();
        AgentToken before = token(1, "a", s, 9L, null);
        AgentScope scope = AgentScope.NONE.withMaxBlocks(5);
        AgentToken after = before.withScope(scope);
        assertEquals(scope, after.scope());
        assertEquals(before.secretHash(), after.secretHash());
        assertEquals(before.expiresAt(), after.expiresAt());
    }
}
