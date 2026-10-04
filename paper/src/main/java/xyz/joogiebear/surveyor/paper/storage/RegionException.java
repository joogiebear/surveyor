package xyz.joogiebear.surveyor.paper.storage;

/** A rejected region change. The message is a key under `error.` in messages.yml. */
public final class RegionException extends RuntimeException {

    public RegionException(String messageKey) {
        super(messageKey, null, false, false);
    }
}
