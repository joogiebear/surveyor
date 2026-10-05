package xyz.joogiebear.surveyor.mcp;

import xyz.joogiebear.surveyor.core.agent.AgentScope;

/** Who is calling: an authenticated token and what it may touch. */
public record Principal(long tokenId, String name, AgentScope scope) {
}
