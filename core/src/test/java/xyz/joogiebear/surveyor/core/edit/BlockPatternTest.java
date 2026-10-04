package xyz.joogiebear.surveyor.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BlockPatternTest {

    private static PatternException.Reason reasonOf(String text) {
        return assertThrows(PatternException.class, () -> BlockPattern.parse(text)).reason();
    }

    @Test
    void singleBlock() {
        assertEquals(List.of(new BlockPattern.Entry("stone", 1)), BlockPattern.parse("stone").entries());
    }

    @Test
    void namespaceAndCaseAreKeptOrLowered() {
        assertEquals("minecraft:stone", BlockPattern.parse("Minecraft:Stone").entries().get(0).block());
    }

    @Test
    void weightedEntries() {
        assertEquals(List.of(new BlockPattern.Entry("stone", 50), new BlockPattern.Entry("andesite", 50)),
                BlockPattern.parse("50%stone,50%andesite").entries());
    }

    @Test
    void weightsNeedNotAddUpToOneHundred() {
        assertEquals(3, BlockPattern.parse("3%stone,1%dirt").entries().get(0).weight());
    }

    @Test
    void unweightedEntriesAreEqual() {
        assertEquals(List.of(new BlockPattern.Entry("stone", 1), new BlockPattern.Entry("dirt", 1)),
                BlockPattern.parse("stone, dirt").entries());
    }

    @Test
    void commasInsideBracketsDoNotSplit() {
        List<BlockPattern.Entry> entries = BlockPattern.parse("30%oak_stairs[facing=north,half=top],70%stone").entries();
        assertEquals(2, entries.size());
        assertEquals("oak_stairs[facing=north,half=top]", entries.get(0).block());
    }

    @Test
    void rejectsBadInput() {
        assertEquals(PatternException.Reason.EMPTY, reasonOf(""));
        assertEquals(PatternException.Reason.EMPTY, reasonOf("stone,,dirt"));
        assertEquals(PatternException.Reason.EMPTY, reasonOf("50%"));
        assertEquals(PatternException.Reason.BAD_WEIGHT, reasonOf("x%stone"));
        assertEquals(PatternException.Reason.BAD_WEIGHT, reasonOf("0%stone"));
        assertEquals(PatternException.Reason.BAD_WEIGHT, reasonOf("-5%stone"));
        assertEquals(PatternException.Reason.MIXED_WEIGHTS, reasonOf("50%stone,dirt"));
        assertEquals(PatternException.Reason.UNBALANCED, reasonOf("oak_stairs[facing=north"));
        assertEquals(PatternException.Reason.UNBALANCED, reasonOf("stone]"));
    }

    @Test
    void pickFollowsTheWeights() {
        BlockPattern pattern = BlockPattern.parse("75%stone,25%dirt");
        Random random = new Random(42);
        int[] counts = new int[2];
        for (int i = 0; i < 20_000; i++) {
            counts[pattern.pickIndex(random)]++;
        }
        assertEquals(0.75, counts[0] / 20_000.0, 0.02);
    }

    @Test
    void singleEntryAlwaysPicksItself() {
        BlockPattern pattern = BlockPattern.parse("stone");
        for (int i = 0; i < 100; i++) {
            assertEquals(0, pattern.pickIndex(new Random(i)));
        }
    }

    @Test
    void maskIsAListWithoutWeights() {
        assertEquals(List.of("stone", "dirt"), BlockMask.parse("Stone,dirt").blocks());
        assertEquals(PatternException.Reason.WEIGHT_NOT_ALLOWED,
                assertThrows(PatternException.class, () -> BlockMask.parse("50%stone")).reason());
    }
}
