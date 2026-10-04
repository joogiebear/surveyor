package xyz.joogiebear.surveyor.core.edit;

public final class PatternException extends IllegalArgumentException {

    public enum Reason {
        EMPTY("pattern-empty"),
        BAD_WEIGHT("pattern-bad-weight"),
        MIXED_WEIGHTS("pattern-mixed-weights"),
        UNBALANCED("pattern-unbalanced"),
        WEIGHT_NOT_ALLOWED("mask-weight");

        private final String messageKey;

        Reason(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    private final Reason reason;

    PatternException(Reason reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
