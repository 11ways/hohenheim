package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.AnonymousPrincipal;
import be.elevenways.zenit.common.security.PermissionChecker;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * A synthetic cms request: the panel it renders under, a query string and its own
 * request-scoped attributes -- so a test can prove per-request state is per REQUEST.
 *
 * AIDEV-NOTE: the {@link TenantConduits} dynamic-proxy shape, answering the three reads a
 * cms hook makes of its request (panel slug, query parameters, attributes); every other
 * method answers its type's default.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class QueryConduits {

    private QueryConduits() {
    }

    /** A request rendered under {@code panelSlug} carrying {@code query}. */
    public static Conduit request(String panelSlug, Map<String, String> query) {
        Map<IdentifierKey<?>, Object> attributes = new HashMap<>();
        Object[] self = new Object[1];
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getAttribute" -> attributes.get(args[0]);
            case "setAttribute" -> {
                if (args[1] == null) {
                    attributes.remove(args[0]);
                } else {
                    attributes.put((IdentifierKey<?>) args[0], args[1]);
                }
                yield null;
            }
            case "getParameter" -> args[0] == CmsEndpoints.PANEL_PARAM ? panelSlug : null;
            case "getQueryParam" -> query.get((String) args[0]);
            case "getConduit" -> self[0];
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "QueryConduits.request(" + panelSlug + ", " + query + ")";
            default -> defaultValue(method.getReturnType());
        };
        Conduit conduit = (Conduit) Proxy.newProxyInstance(
            QueryConduits.class.getClassLoader(), new Class<?>[] { Conduit.class }, handler);
        self[0] = conduit;
        return conduit;
    }

    /** An anonymous access context whose request is {@code conduit}. */
    public static AccessContext accessOf(Conduit conduit) {
        return AccessContext.of(conduit, AnonymousPrincipal.INSTANCE, PermissionChecker.DENY_ALL);
    }

    private static @Nullable Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return 0;
    }
}
