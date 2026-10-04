package xyz.joogiebear.surveyor.core.edit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The undo journal row for one chunk batch: the previous block data of every changed position.
 * Layout before compression: a version byte, a palette of block data strings, then one entry per
 * block (x and z within the chunk packed in a byte, y as a zigzag varint, palette index as a
 * varint). The whole thing is gzipped. The chunk coordinates live in the table columns.
 */
public final class JournalCodec {

    private static final int VERSION = 1;

    public record Entry(int x, int y, int z, String data) {
    }

    private JournalCodec() {
    }

    public static byte[] encode(int chunkX, int chunkZ, List<Entry> entries) {
        Map<String, Integer> index = new HashMap<>();
        List<String> palette = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.x() >> 4 != chunkX || entry.z() >> 4 != chunkZ) {
                throw new IllegalArgumentException("entry is outside chunk " + chunkX + "," + chunkZ);
            }
            if (index.putIfAbsent(entry.data(), palette.size()) == null) {
                palette.add(entry.data());
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(VERSION);
            writeVarInt(out, palette.size());
            for (String data : palette) {
                byte[] utf8 = data.getBytes(StandardCharsets.UTF_8);
                writeVarInt(out, utf8.length);
                out.write(utf8);
            }
            writeVarInt(out, entries.size());
            for (Entry entry : entries) {
                out.writeByte(((entry.x() & 15) << 4) | (entry.z() & 15));
                writeVarInt(out, (entry.y() << 1) ^ (entry.y() >> 31));
                writeVarInt(out, index.get(entry.data()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** @throws IllegalArgumentException if the bytes are not a journal row this version wrote */
    public static List<Entry> decode(int chunkX, int chunkZ, byte[] data) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw new IllegalArgumentException("unknown journal version " + version);
            }
            int paletteSize = readVarInt(in);
            List<String> palette = new ArrayList<>(paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                byte[] utf8 = new byte[readVarInt(in)];
                in.readFully(utf8);
                palette.add(new String(utf8, StandardCharsets.UTF_8));
            }
            int count = readVarInt(in);
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int packed = in.readUnsignedByte();
                int zigzag = readVarInt(in);
                int y = (zigzag >>> 1) ^ -(zigzag & 1);
                entries.add(new Entry((chunkX << 4) + (packed >> 4), y, (chunkZ << 4) + (packed & 15),
                        palette.get(readVarInt(in))));
            }
            // Reading to the end makes gzip check its length and CRC, so a damaged row is not applied.
            if (in.read() != -1) {
                throw new IllegalArgumentException("corrupt journal row: trailing data");
            }
            return entries;
        } catch (IOException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("corrupt journal row", e);
        }
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("varint too long");
    }
}
