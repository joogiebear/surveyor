package xyz.joogiebear.surveyor.core.edit;

import java.util.List;
import java.util.Locale;

/** The "from" side of a replace: comma separated block types, no weights. */
public record BlockMask(List<String> blocks) {

    public static BlockMask parse(String text) {
        List<String> blocks = BlockPattern.split(text).stream()
                .map(part -> {
                    if (BlockPattern.hasWeight(part)) {
                        throw new PatternException(PatternException.Reason.WEIGHT_NOT_ALLOWED, part);
                    }
                    return part.toLowerCase(Locale.ROOT);
                })
                .toList();
        return new BlockMask(blocks);
    }
}
