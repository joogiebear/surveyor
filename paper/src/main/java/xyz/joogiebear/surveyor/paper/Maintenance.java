package xyz.joogiebear.surveyor.paper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Housekeeping that must stay off the region threads: summary rows for bypass bursts, and deleting
 * old undo journals and audit rows. Pruning works in small steps so the database thread is never
 * held long, and a retention of 0 days keeps everything.
 */
final class Maintenance {

    private static final int STEP_ROWS = 200;
    private static final long PRUNE_EVERY_HOURS = 6;

    private final SurveyorPlugin plugin;
    private final Protection protection;

    Maintenance(SurveyorPlugin plugin, Protection protection) {
        this.plugin = plugin;
        this.protection = protection;
    }

    /** Call once the database is open. The first prune runs a minute later, so it never competes with startup. */
    void start() {
        var async = plugin.getServer().getAsyncScheduler();
        async.runAtFixedRate(plugin, task -> protection.flushBypasses(false), 30, 30, TimeUnit.SECONDS);
        async.runAtFixedRate(plugin, task -> prune(), 1, PRUNE_EVERY_HOURS * 60, TimeUnit.MINUTES);
    }

    private void prune() {
        Settings settings = plugin.settings();
        long now = System.currentTimeMillis();
        if (settings.undoRetentionDays() > 0) {
            long cutoff = now - TimeUnit.DAYS.toMillis(settings.undoRetentionDays());
            drain(() -> plugin.edits().store().pruneJournals(cutoff, STEP_ROWS), 0).whenComplete((count, error) ->
                    report("undo journal", "operation(s)", count, error));
        }
        if (settings.auditRetentionDays() > 0) {
            long cutoff = now - TimeUnit.DAYS.toMillis(settings.auditRetentionDays());
            drain(() -> plugin.agents().store().pruneAudit(cutoff, STEP_ROWS * 5), 0).whenComplete((count, error) ->
                    report("audit log", "row(s)", count, error));
        }
    }

    private static CompletableFuture<Long> drain(Supplier<CompletableFuture<Integer>> step, long total) {
        return step.get().thenCompose(done -> done == 0 ? CompletableFuture.completedFuture(total) : drain(step, total + done));
    }

    private void report(String what, String unit, Long count, Throwable error) {
        if (error != null) {
            plugin.getLogger().warning("Could not prune the " + what + ": " + error.getMessage());
        } else if (count > 0) {
            plugin.getLogger().info("Pruned the " + what + ": removed " + count + " old " + unit);
        }
    }
}
