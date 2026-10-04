package xyz.joogiebear.surveyor.core;

/** The `limits.*` config section. Rejects values that would stall or starve the server. */
public record Limits(int maxBlocksPerOperation, int tickBudgetMs, int maxConcurrentOperations) {

    // A tick is 50 ms; a budget at or above that would let an edit consume the whole tick.
    private static final int MAX_TICK_BUDGET_MS = 49;

    public Limits {
        if (maxBlocksPerOperation < 1) {
            throw new IllegalArgumentException("limits.max-blocks-per-operation must be at least 1");
        }
        if (tickBudgetMs < 1 || tickBudgetMs > MAX_TICK_BUDGET_MS) {
            throw new IllegalArgumentException(
                    "limits.tick-budget-ms must be between 1 and " + MAX_TICK_BUDGET_MS);
        }
        if (maxConcurrentOperations < 1) {
            throw new IllegalArgumentException("limits.max-concurrent-operations must be at least 1");
        }
    }
}
