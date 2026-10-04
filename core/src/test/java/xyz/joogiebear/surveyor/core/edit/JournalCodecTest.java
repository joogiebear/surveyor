package xyz.joogiebear.surveyor.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.joogiebear.surveyor.core.edit.JournalCodec.Entry;

class JournalCodecTest {

    @Test
    void roundTripsNegativeChunksAndHeights() {
        List<Entry> entries = List.of(
                new Entry(-1, -64, -16, "minecraft:stone"),
                new Entry(-16, 319, -1, "minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]"),
                new Entry(-8, 0, -8, "minecraft:stone"));
        assertEquals(entries, JournalCodec.decode(-1, -1, JournalCodec.encode(-1, -1, entries)));
    }

    @Test
    void roundTripsEmptyAndUnicode() {
        assertEquals(List.of(), JournalCodec.decode(0, 0, JournalCodec.encode(0, 0, List.of())));
        List<Entry> entries = List.of(new Entry(3, 70, 4, "mod:blöck[x=ü]"));
        assertEquals(entries, JournalCodec.decode(0, 0, JournalCodec.encode(0, 0, entries)));
    }

    @Test
    void roundTripsAFullBatchAndStaysSmall() {
        List<Entry> entries = new ArrayList<>();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    entries.add(new Entry(32 + x, y + 60, 48 + z, (x + z) % 3 == 0 ? "minecraft:dirt" : "minecraft:stone"));
                }
            }
        }
        byte[] bytes = JournalCodec.encode(2, 3, entries);
        assertEquals(entries, JournalCodec.decode(2, 3, bytes));
        assertTrue(bytes.length < entries.size() * 3, "expected well under 3 bytes per block, got " + bytes.length);
    }

    @Test
    void rejectsEntriesOutsideTheChunk() {
        assertThrows(IllegalArgumentException.class,
                () -> JournalCodec.encode(0, 0, List.of(new Entry(16, 0, 0, "minecraft:stone"))));
        assertThrows(IllegalArgumentException.class,
                () -> JournalCodec.encode(0, 0, List.of(new Entry(0, 0, -1, "minecraft:stone"))));
    }

    @Test
    void rejectsCorruptRows() {
        assertThrows(IllegalArgumentException.class, () -> JournalCodec.decode(0, 0, new byte[] {1, 2, 3}));
        byte[] good = JournalCodec.encode(0, 0, List.of(new Entry(1, 1, 1, "minecraft:stone")));
        byte[] truncated = java.util.Arrays.copyOf(good, good.length - 6);
        assertThrows(IllegalArgumentException.class, () -> JournalCodec.decode(0, 0, truncated));
    }
}
