package xyz.joogiebear.surveyor.core.region;

import java.util.List;
import java.util.UUID;

public final class FlagResolver {

    private FlagResolver() {
    }

    /**
     * A flag value only speaks for actors in its audience; for everyone else the region is
     * transparent. Among the values that apply, the highest priority wins and at equal priority
     * deny beats allow. When none apply, the configured default decides.
     *
     * @param regions every region containing the position, in any order
     * @param actor   the acting player, or null when there is none (for example a mob spawn)
     */
    public static boolean resolve(List<Region> regions, Flag flag, UUID actor, boolean defaultAllow) {
        boolean found = false;
        int best = Integer.MIN_VALUE;
        boolean deny = false;
        for (Region region : regions) {
            FlagValue value = region.flags().get(flag);
            if (value == null || !value.audience().matches(region.roleOf(actor))) {
                continue;
            }
            if (!found || region.priority() > best) {
                found = true;
                best = region.priority();
                deny = !value.allow();
            } else if (region.priority() == best) {
                deny |= !value.allow();
            }
        }
        return found ? !deny : defaultAllow;
    }
}
