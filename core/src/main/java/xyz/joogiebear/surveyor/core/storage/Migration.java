package xyz.joogiebear.surveyor.core.storage;

public record Migration(int version, String name) {

    public Migration {
        if (version < 1) {
            throw new IllegalArgumentException("migration version must be at least 1, got " + version);
        }
    }
}
