package be.elevenways.hohenheim.test;

import be.elevenways.zenit.common.security.ExecutionIdentity;
import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Declares test code the OPERATOR: every test method, lifecycle method and test constructor
 * runs as zenit's system identity, so a fixture seeding rows or calling a service directly
 * is judged as the installation acting, exactly as it was before the gates failed closed.
 *
 * Registered through {@code META-INF/services} with extension autodetection
 * ({@code junit-platform.properties}), so no test class enrols by hand. A test that speaks
 * for a tenant still does so with {@link TenantConduits#as}, whose request frame wins over
 * this one; a test proving the no-identity refusal runs its body under
 * {@code ExecutionIdentity.run(null, ...)}.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class OperatorIdentity implements InvocationInterceptor {

    /** The reason the test harness's system identity carries. */
    public static final String REASON = "test-operator";

    @Override
    public <T> T interceptTestClassConstructor(Invocation<T> invocation,
                                               ReflectiveInvocationContext<Constructor<T>> context,
                                               ExtensionContext extensionContext) throws Throwable {
        return asOperator(invocation);
    }

    @Override
    public void interceptBeforeAllMethod(Invocation<Void> invocation,
                                         ReflectiveInvocationContext<Method> context,
                                         ExtensionContext extensionContext) throws Throwable {
        asOperator(invocation);
    }

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation,
                                          ReflectiveInvocationContext<Method> context,
                                          ExtensionContext extensionContext) throws Throwable {
        asOperator(invocation);
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> context,
                                    ExtensionContext extensionContext) throws Throwable {
        asOperator(invocation);
    }

    @Override
    public <T> T interceptTestFactoryMethod(Invocation<T> invocation,
                                           ReflectiveInvocationContext<Method> context,
                                           ExtensionContext extensionContext) throws Throwable {
        return asOperator(invocation);
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
                                            ReflectiveInvocationContext<Method> context,
                                            ExtensionContext extensionContext) throws Throwable {
        asOperator(invocation);
    }

    @Override
    public void interceptDynamicTest(Invocation<Void> invocation,
                                     DynamicTestInvocationContext invocationContext,
                                     ExtensionContext extensionContext) throws Throwable {
        asOperator(invocation);
    }

    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation,
                                         ReflectiveInvocationContext<Method> context,
                                         ExtensionContext extensionContext) throws Throwable {
        asOperator(invocation);
    }

    @Override
    public void interceptAfterAllMethod(Invocation<Void> invocation,
                                        ReflectiveInvocationContext<Method> context,
                                        ExtensionContext extensionContext) throws Throwable {
        asOperator(invocation);
    }

    private static <T> T asOperator(Invocation<T> invocation) throws Throwable {
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        ExecutionIdentity.runAsSystem(REASON, () -> {
            try {
                result[0] = invocation.proceed();
            } catch (Throwable thrown) {
                failure[0] = thrown;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }
}
