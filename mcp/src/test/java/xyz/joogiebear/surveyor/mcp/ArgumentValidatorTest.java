package xyz.joogiebear.surveyor.mcp;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ArgumentValidatorTest {

    private static String check(String tool, String json) {
        return ArgumentValidator.validate(Tools.find(tool).inputSchema(), JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void acceptsAFittingCall() {
        assertNull(check("edit.set", "{\"world\":\"world\",\"pos1\":[0,0,0],\"pos2\":[1,1,1],\"pattern\":\"stone\","
                + "\"dryRun\":true,\"reason\":\"why\"}"));
        assertNull(check("world.list", "{}"));
        assertNull(check("flag.set", "{\"world\":\"w\",\"region\":\"r\",\"flag\":\"build\",\"value\":\"deny\",\"audience\":\"members\"}"));
        assertNull(check("edit.set", "{\"world\":\"w\",\"pattern\":\"stone\",\"pos1\":[1.0,2,3],\"pos2\":[1,2,3]}"));
    }

    @Test
    void missingRequiredArgumentsAreNamed() {
        assertTrue(check("edit.set", "{\"world\":\"world\"}").contains("'pattern'"));
        assertTrue(check("operation.status", "{}").contains("'operationId'"));
    }

    @Test
    void unknownArgumentsAreRefusedAndTheAllowedOnesListed() {
        String problem = check("edit.set", "{\"world\":\"w\",\"pattern\":\"stone\",\"patern\":\"x\"}");
        assertTrue(problem.contains("'patern'") && problem.contains("pattern"), problem);
    }

    @Test
    void typesAreChecked() {
        assertTrue(check("edit.set", "{\"world\":1,\"pattern\":\"stone\"}").contains("string"));
        assertTrue(check("edit.set", "{\"world\":\"w\",\"pattern\":\"stone\",\"dryRun\":\"yes\"}").contains("true or false"));
        assertTrue(check("region.update", "{\"world\":\"w\",\"region\":\"r\",\"priority\":1.5}").contains("whole number"));
        assertTrue(check("region.update", "{\"world\":\"w\",\"region\":\"r\",\"priority\":3000000000}").contains("32 bits"));
        assertTrue(check("edit.set", "{\"world\":\"w\",\"pattern\":\"s\",\"pos1\":[1,2]}").contains("[x, y, z]"));
        assertTrue(check("edit.set", "{\"world\":\"w\",\"pattern\":\"s\",\"pos1\":[1,2,\"3\"]}").contains("whole numbers"));
        assertTrue(check("edit.set", "{\"world\":\"w\",\"pattern\":\"s\",\"pos1\":\"1 2 3\"}").contains("array"));
    }

    @Test
    void enumsAndNullsAndLengthsAreChecked() {
        assertTrue(check("flag.set", "{\"world\":\"w\",\"region\":\"r\",\"flag\":\"build\",\"value\":\"maybe\"}").contains("one of"));
        assertTrue(check("edit.set", "{\"world\":null,\"pattern\":\"s\"}").contains("null"));
        JsonObject big = new JsonObject();
        big.addProperty("world", "w");
        big.addProperty("pattern", "x".repeat(5000));
        assertTrue(ArgumentValidator.validate(Tools.find("edit.set").inputSchema(), big).contains("longer"));
    }
}
