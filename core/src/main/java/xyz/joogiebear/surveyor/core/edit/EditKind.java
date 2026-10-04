package xyz.joogiebear.surveyor.core.edit;

import java.util.Locale;

public enum EditKind {
    SET(true), REPLACE(true), PASTE(true), COPY(false), UNDO(false);

    private final boolean journaled;

    EditKind(boolean journaled) {
        this.journaled = journaled;
    }

    /** Whether its previous block states go into the undo journal, which also means it can be undone. */
    public boolean journaled() {
        return journaled;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
