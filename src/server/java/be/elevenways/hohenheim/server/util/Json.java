package be.elevenways.hohenheim.server.util;

import be.elevenways.protoblast.common.dry.Dry;
import java.util.Map;

/**
 * Minimal plain-JSON writer for a {@code Map / List / String / Number / Boolean / null} tree, for Docker request
 * bodies and webhook payloads; its strings are Dry's literal spelling ({@link Dry#appendQuoted}).
 *
 * AIDEV-NOTE: not {@code Dry.toJson}, which writes a solidus as {@code \/} and serializes an unknown object through
 * its registered serializer; this writer keeps the bytes its consumers always sent (a bare solidus, an unknown
 * object as its {@code toString()} string).
 *
 * AIDEV-NOTE: the deleted IPC channel kept its own JSON writer: it paired with a parser
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
        Dry.appendQuoted(sb, s);
    }
}
