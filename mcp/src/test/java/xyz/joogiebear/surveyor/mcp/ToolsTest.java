package xyz.joogiebear.surveyor.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.joogiebear.surveyor.core.agent.AgentActions;

class ToolsTest {

    @Test
    void theCatalogueMatchesTheActionNamesAScopeCanGrant() {
        assertEquals(AgentActions.TOOLS, Tools.all().stream().map(Tools.Definition::name).toList());
        assertEquals(Tools.all().size(), new HashSet<>(Tools.all().stream().map(Tools.Definition::name).toList()).size());
    }

    @Test
    void namesFollowTheMcpNamingRules() {
        for (Tools.Definition tool : Tools.all()) {
            assertTrue(tool.name().matches("[A-Za-z0-9_.-]{1,128}"), tool.name());
        }
    }

    @Test
    void everySchemaIsAClosedObjectAndRequiredNamesExist() {
        for (Tools.Definition tool : Tools.all()) {
            JsonObject schema = tool.inputSchema();
            assertEquals("object", schema.get("type").getAsString(), tool.name());
            assertFalse(schema.get("additionalProperties").getAsBoolean(), tool.name());
            JsonObject props = schema.getAsJsonObject("properties");
            if (schema.has("required")) {
                schema.getAsJsonArray("required").forEach(name -> assertTrue(props.has(name.getAsString()),
                        tool.name() + " requires unknown property " + name));
            }
            assertFalse(tool.description().isBlank());
        }
    }

    @Test
    void changingToolsTakeReasonAndIdempotencyWherePromised() {
        for (String name : List.of("edit.set", "edit.replace", "edit.paste", "edit.undo", "region.create", "region.update",
                "region.delete", "flag.set", "flag.clear", "member.add", "member.remove")) {
            JsonObject props = Tools.find(name).inputSchema().getAsJsonObject("properties");
            assertTrue(props.has("dryRun"), name);
            assertTrue(props.has("reason"), name);
            assertTrue(props.has("idempotencyKey"), name);
        }
        for (String name : List.of("edit.set", "edit.replace", "edit.paste")) {
            assertTrue(Tools.find(name).inputSchema().getAsJsonObject("properties").has("strict"), name);
        }
        assertFalse(Tools.find("edit.copy").inputSchema().getAsJsonObject("properties").has("dryRun"));
    }

    @Test
    void readOnlyToolsAreMarkedAndHaveNoReason() {
        for (String name : List.of("world.list", "selection.get", "region.get", "region.list", "region.at",
                "operation.status", "audit.query")) {
            Tools.Definition tool = Tools.find(name);
            assertTrue(tool.readOnly(), name);
            assertFalse(tool.inputSchema().getAsJsonObject("properties").has("reason"), name);
        }
        assertFalse(Tools.find("edit.set").readOnly());
    }
}
