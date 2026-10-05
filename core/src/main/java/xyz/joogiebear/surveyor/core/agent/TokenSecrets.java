package xyz.joogiebear.surveyor.core.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Makes and hashes token secrets. The secret itself is never kept. */
public final class TokenSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "sv_";
    private static final int PREFIX_SHOWN = PREFIX.length() + 6;

    /** @param secret shown once to the admin; @param hash and @param prefix are what gets stored */
    public record Generated(String secret, String hash, String prefix) {
    }

    private TokenSecrets() {
    }

    /** 256 bits from a secure random source. */
    public static Generated generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new Generated(secret, hash(secret), secret.substring(0, PREFIX_SHOWN));
    }

    /** Lowercase hex SHA-256. */
    public static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }
}
