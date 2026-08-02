package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.conduit.ConduitAttributes;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.security.Principal;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * A request scope carrying nothing but an identity, so a test can drive a write straight at
 * the MODEL and still be judged as the request it pretends to be.
 *
 * This is the bypass class the write-pipeline invariants exist for: revision restore, the
 * peer API, a zone-file import and any direct {@code model.save} reach the datasource
 * without touching a resource method, a form spec or an endpoint. Testing the freezes only
 * through /manage forms would prove the FORM drops fields, which is not the claim.
 *
 * AIDEV-NOTE: a dynamic proxy, not a hand-written 200-method stub. Everything the invariants
 * actually read is the principal attribute; every other Conduit method answers its type's
 * default, which is what a synthetic carrier legitimately looks like (AccessContext.of
 * documents that shape).
 */
final class TenantConduits {

    private TenantConduits() {
    }

    /** Run {@code body} inside a request scope whose principal is {@code principal}. */
    static void as(@Nullable Principal principal, Runnable body) {
        RouteScope.run(stub(principal), body);
    }

    private static Conduit stub(@Nullable Principal principal) {
        Map<IdentifierKey<?>, Object> attributes = new HashMap<>();
        if (principal != null) {
            attributes.put(ConduitAttributes.PRINCIPAL, principal);
        }
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
            case "getConduit" -> self[0];
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "TenantConduits.stub";
            default -> defaultValue(method);
        };
        Conduit conduit = (Conduit) Proxy.newProxyInstance(
            TenantConduits.class.getClassLoader(), new Class<?>[] { Conduit.class }, handler);
        self[0] = conduit;
        return conduit;
    }

    private static @Nullable Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return (char) 0;
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
        return 0;
    }
}
