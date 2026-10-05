package xyz.joogiebear.surveyor.core.edit;

import xyz.joogiebear.surveyor.core.agent.AgentScope;

/** Token scope in front of another guard: a position outside the scope is refused before regions are asked. */
public record ScopeGuard(AgentScope scope, String world, EditGuard next) implements EditGuard {

    @Override
    public boolean allows(int x, int y, int z) {
        return check(x, y, z) == Denial.NONE;
    }

    @Override
    public Denial check(int x, int y, int z) {
        return scope.contains(world, x, y, z) ? next.check(x, y, z) : Denial.OUT_OF_SCOPE;
    }
}
