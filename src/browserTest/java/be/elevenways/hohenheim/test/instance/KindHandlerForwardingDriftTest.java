package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every test fake that wraps a real {@link InstanceKindHandler} answers every kind fact itself, so a new default
 * method on the interface can never be read off a fake as the default instead of the wrapped kind's answer.
 *
 * AIDEV-NOTE: the defect this pins: FakeDockerDaemon's container kind once kept the default {@code portSettings()},
 * so the ports flow asked container_port in tests and never in production.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class KindHandlerForwardingDriftTest {

    @Test
    void everyWrappingFakeForwardsEveryKindFact() throws IOException, URISyntaxException {
        List<Class<?>> wrapping = wrappingFakes();

        // 1. The scan sees the wrapping fakes at all (a scan finding nothing would prove nothing).
        assertThat(wrapping).as("step 1: the browserTest classes hold wrapping kind fakes").isNotEmpty();

        // 2. No wrapping fake leaves an interface method to its default: each resolves to a class's own answer.
        List<String> unforwarded = new ArrayList<>();
        for (Class<?> fake : wrapping) {
            for (Method method : InstanceKindHandler.class.getMethods()) {
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                Method resolved = resolve(fake, method);
                if (resolved == null || resolved.getDeclaringClass().isInterface()) {
                    unforwarded.add(fake.getName() + "#" + method.getName());
                }
            }
        }
        assertThat(new TreeSet<>(unforwarded))
            .as("step 2: every wrapping fake forwards every InstanceKindHandler method (extend ForwardingKindHandler)")
            .isEmpty();
    }

    /** The class's own resolution of an interface method, walking superclasses only. */
    private static Method resolve(Class<?> type, Method method) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            }
        }
        return null;
    }

    /** Every compiled browserTest class implementing the kind seam while holding another kind handler. */
    private static List<Class<?>> wrappingFakes() throws IOException, URISyntaxException {
        Path root = Path.of(KindHandlerForwardingDriftTest.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI());
        ClassLoader loader = KindHandlerForwardingDriftTest.class.getClassLoader();
        List<Class<?>> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String name = root.relativize(file).toString().replace('/', '.');
                name = name.substring(0, name.length() - ".class".length());
                Class<?> type;
                try {
                    type = Class.forName(name, false, loader);
                } catch (ClassNotFoundException | LinkageError skipped) {
                    continue;
                }
                if (!type.isInterface() && InstanceKindHandler.class.isAssignableFrom(type) && holdsAHandler(type)) {
                    found.add(type);
                }
            }
        }
        return found;
    }

    private static boolean holdsAHandler(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            Field[] fields = current.getDeclaredFields();
            if (Arrays.stream(fields).anyMatch(field -> InstanceKindHandler.class.isAssignableFrom(field.getType()))) {
                return true;
            }
        }
        return false;
    }
}
