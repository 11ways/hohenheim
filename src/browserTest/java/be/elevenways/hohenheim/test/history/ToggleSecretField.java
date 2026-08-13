package be.elevenways.hohenheim.test.history;

import be.elevenways.zenit.common.orm.field.StringField;

/**
 * A string field whose {@code .secret()} declaration can be flipped after rows have already
 * been written -- the ONLY way to reproduce the situation this whole purge exists for.
 *
 * AIDEV-NOTE: the production defect is "a field that was plain when history was written and is
 * secret now". Every writer in the stack redacts on the way in, so a plaintext history row
 * cannot be produced by today's code at all: the field has to be plain at write time. Building
 * the row by hand instead would prove nothing -- the point of the fixture is that the REAL
 * RevisionableBehaviour and the REAL ActivityLog wrote it, in the real encoding, and then the
 * declaration moved underneath them. isSecret() is a plain attribute read on Field, so
 * overriding it is enough; nothing caches the answer.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class ToggleSecretField extends StringField {

    private static volatile boolean secret;

    private ToggleSecretField(StringField.Builder builder) {
        super(builder);
    }

    public static ToggleSecretField named(String name) {
        return new ToggleSecretField(StringField.builder(name));
    }

    /** Flips every field of this class at once; the fixture declares only its own. */
    public static void declareSecret(boolean value) {
        secret = value;
    }

    @Override
    public boolean isSecret() {
        return secret;
    }
}
