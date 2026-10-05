package xyz.joogiebear.surveyor.paper;

import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import xyz.joogiebear.surveyor.core.agent.AgentScope;

/** Who an edit runs for: a player, who is told how it went, or an agent token, who polls. */
interface Actor {

    /** The {@code actor_type} stored with operations and audit rows. */
    String type();

    /** The {@code actor_id} stored with them: a player UUID or a token id. */
    String id();

    /** Key for selections and clipboards. Players and tokens never collide: UUID versus Long. */
    Object key();

    /** The identity region audiences see. Null for an agent, which is never a member of anything. */
    UUID regionIdentity();

    /** What the actor may touch, or null when only regions limit it. Read afresh, so a change applies to running edits. */
    AgentScope scope();

    void tell(SurveyorPlugin plugin, Component message);

    record Human(Player player) implements Actor {

        @Override
        public String type() {
            return "player";
        }

        @Override
        public String id() {
            return player.getUniqueId().toString();
        }

        @Override
        public Object key() {
            return player.getUniqueId();
        }

        @Override
        public UUID regionIdentity() {
            return player.getUniqueId();
        }

        @Override
        public AgentScope scope() {
            return null;
        }

        @Override
        public void tell(SurveyorPlugin plugin, Component message) {
            plugin.reply(player, message);
        }
    }

    record Agent(long tokenId, Supplier<AgentScope> currentScope) implements Actor {

        @Override
        public String type() {
            return "agent";
        }

        @Override
        public String id() {
            return String.valueOf(tokenId);
        }

        @Override
        public Object key() {
            return tokenId;
        }

        @Override
        public UUID regionIdentity() {
            return null;
        }

        @Override
        public AgentScope scope() {
            return currentScope.get();
        }

        @Override
        public void tell(SurveyorPlugin plugin, Component message) {
        }
    }
}
