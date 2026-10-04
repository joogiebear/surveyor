package xyz.joogiebear.surveyor.core.edit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * Comma separated block types with optional weights: {@code stone}, {@code 50%stone,50%andesite},
 * {@code stone,dirt} (equal weights). A block may carry a bracketed state such as
 * {@code oak_stairs[facing=north,half=top]}; commas inside brackets do not split. Weights are
 * relative, so they need not add up to 100, and are either given for every entry or for none.
 * This class only reads the syntax; whether a block exists is for the server to decide.
 */
public final class BlockPattern {

    public record Entry(String block, int weight) {
    }

    private final List<Entry> entries;
    private final long totalWeight;

    private BlockPattern(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        this.totalWeight = entries.stream().mapToLong(Entry::weight).sum();
    }

    public static BlockPattern parse(String text) {
        List<Entry> entries = new ArrayList<>();
        int weighted = 0;
        for (String part : split(text)) {
            int weight = 1;
            String block = part;
            if (hasWeight(part)) {
                weighted++;
                int percent = part.indexOf('%');
                try {
                    weight = Integer.parseInt(part.substring(0, percent).trim());
                } catch (NumberFormatException e) {
                    throw new PatternException(PatternException.Reason.BAD_WEIGHT, part);
                }
                if (weight < 1) {
                    throw new PatternException(PatternException.Reason.BAD_WEIGHT, part);
                }
                block = part.substring(percent + 1);
            }
            block = block.trim().toLowerCase(Locale.ROOT);
            if (block.isEmpty()) {
                throw new PatternException(PatternException.Reason.EMPTY, part);
            }
            entries.add(new Entry(block, weight));
        }
        if (weighted != 0 && weighted != entries.size()) {
            throw new PatternException(PatternException.Reason.MIXED_WEIGHTS, text);
        }
        return new BlockPattern(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Index into {@link #entries()}, chosen with probability proportional to its weight. */
    public int pickIndex(RandomGenerator random) {
        long roll = random.nextLong(totalWeight);
        for (int i = 0; i < entries.size(); i++) {
            roll -= entries.get(i).weight();
            if (roll < 0) {
                return i;
            }
        }
        throw new IllegalStateException("weights do not add up");
    }

    // A '%' inside brackets belongs to a block state, not to a weight.
    static boolean hasWeight(String part) {
        int percent = part.indexOf('%');
        int bracket = part.indexOf('[');
        return percent >= 0 && (bracket < 0 || percent < bracket);
    }

    /** Splits on commas outside brackets. Rejects empty parts and unbalanced brackets. */
    static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ',';
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth < 0) {
                    throw new PatternException(PatternException.Reason.UNBALANCED, text);
                }
            } else if (c == ',' && depth == 0) {
                String part = text.substring(start, i).trim();
                if (part.isEmpty()) {
                    throw new PatternException(PatternException.Reason.EMPTY, text);
                }
                parts.add(part);
                start = i + 1;
            }
        }
        if (depth != 0) {
            throw new PatternException(PatternException.Reason.UNBALANCED, text);
        }
        return parts;
    }
}
