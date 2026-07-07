package be.elevenways.hohenheim.server.util;

import java.util.Map;

/**
 * Minimal plain-JSON writer for a {@code Map / List / String / Number / Boolean / null} tree --
 * used where DRY's {@code stringify} can't be (it emits DRY's extended, non-JSON syntax), e.g.
 * Docker request bodies and webhook payloads.
 *
 * AIDEV-NOTE: IpcChannel intentionally keeps its own JSON writer: it pairs with a parser
 * shared with the Node child (a wire contract). Don't fold it into this utility.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class Json {

    private Json() {}

    /** Encode a Map/List/String/Number/Boolean/null tree as plain JSON. */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    private static void write(Object value, StringBuilder sb) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> writeString(s, sb);
            case Boolean b -> sb.append(b.toString());
            case Number n -> sb.append(n.toString());
            case Map<?, ?> map -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeString(String.valueOf(entry.getKey()), sb);
                    sb.append(':');
                    write(entry.getValue(), sb);
                }
                sb.append('}');
            }
            case Iterable<?> list -> {
                sb.append('[');
                boolean first = true;
                for (Object element : list) {
                    if (!first) sb.append(',');
                    first = false;
                    write(element, sb);
                }
                sb.append(']');
            }
            default -> writeString(value.toString(), sb);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
