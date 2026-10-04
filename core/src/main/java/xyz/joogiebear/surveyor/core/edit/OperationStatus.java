package xyz.joogiebear.surveyor.core.edit;

import java.util.Locale;

public enum OperationStatus {
    PLANNED, RUNNING, DONE, FAILED, CANCELLED;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean terminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }

    public boolean canMoveTo(OperationStatus next) {
        return switch (this) {
            case PLANNED -> next == RUNNING || next == FAILED || next == CANCELLED;
            case RUNNING -> next.terminal();
            default -> false;
        };
    }
}
