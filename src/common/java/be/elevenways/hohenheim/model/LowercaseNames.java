package be.elevenways.hohenheim.model;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The lowercase name shape hosts and stacks share: letters a-z, digits and dashes, a letter or digit first.
 *
 * AIDEV-NOTE: each record keeps its own rule on its model ({@link ServerModel#isValidName},
 * {@link StackModel#isValidName}) because the ceilings differ; only the shape lives here (DD8). A database name is a
 * different rule, Docker's object-name shape ({@link DatabaseModel#isValidName}).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class LowercaseNames {

    private LowercaseNames() {
    }

    /** @return whether {@code name} has the shape and at most {@code maxLength} characters */
    public static boolean isValid(@Nullable String name, int maxLength) {
        if (name == null || name.isEmpty() || name.length() > maxLength) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            boolean alphanumeric = (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9');
            if (!alphanumeric && (i == 0 || character != '-')) {
                return false;
            }
        }
        return true;
    }
}
