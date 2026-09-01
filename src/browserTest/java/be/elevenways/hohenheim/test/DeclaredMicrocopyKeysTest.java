package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.common.setting.ContentLocales;
import be.elevenways.zenit.microcopy.server.JavaMicrocopyKeys;
import be.elevenways.zenit.microcopy.server.MicrocopyManifestDrift;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JAVA half of Hohenheim's vocabulary: field labels, help texts, form-section titles,
 * confirmations and toasts resolve through {@code Microcopy.of(...)} in Java and no
 * template literal ever manifests them, so {@link MicrocopyManifestDriftTest} -- which
 * judges the COMPILER's template manifest -- is blind to every one of them.
 *
 * AIDEV-NOTE: this gate is why the QA sweep of 2026-09-01 found raw keys on shipped forms.
 * {@code host_port} carried a {@code field} variant and no {@code help} one (its help text
 * had been written under a key nothing reads, {@code instance_host_port}), and
 * {@code application|field}, {@code status|field} and
 * {@code delete_confirm|instance_database} were absent outright -- four user-visible raw
 * tokens, with the whole suite green, because nothing here was judging Java-declared keys.
 */
class DeclaredMicrocopyKeysTest {

    private static final LocaleChain EN = LocaleChain.ofTags("en");

    /**
     * AIDEV-NOTE: the {@code HohenheimFormCopy} helpers are declared by their QUALIFIED
     * call text only. A factory is matched by call text across every scanned file, and a
     * bare {@code label} would swallow every builder's {@code .label(...)} and stamp
     * {@code scope=field} on keys that pin something else. Hohenheim static-imports none of
     * the three, so the qualified spelling covers every call site there is.
     */
    private JavaMicrocopyKeys scan() {
        return JavaMicrocopyKeys.in(Path.of("src/common"), Path.of("src/server"))
            // The helper's own file only FORWARDS its parameter; it declares no key.
            .excluding("HohenheimFormCopy.java")
            .factory("HohenheimFormCopy.label", List.of("scope=field"), List.of())
            .factory("HohenheimFormCopy.help", List.of("scope=help"), List.of())
            .factory("HohenheimFormCopy.section", List.of("scope=form_section"), List.of())
            // ServerResource's own host-scoped helper; a key it builds is never spelled
            // through Microcopy.of, so without this the whole host vocabulary is invisible.
            .factory("serverCopy", List.of("scope=server"), List.of())
            .factory(JavaMicrocopyKeys.MICROCOPY_OF);
    }

    @Test
    void everyJavaResolvedLiteralKeyResolvesInBothShippedLanguages() {
        JavaMicrocopyKeys scan = this.scan();

        // The scan must keep SEEING the vocabulary: a refactor that routed these through a
        // helper of its own would otherwise leave a green gate over nothing.
        assertTrue(scan.declared().references().size() >= 500,
            () -> "the Java key scan no longer sees Hohenheim's vocabulary (found "
                + scan.declared().references().size() + ")");

        MicrocopyManifestDrift.ofDeclared(scan.declared().references()).requireResolvable(EN);
        // The chain a "Accept-Language: nl" browser actually receives, built the way the
        // request pipeline builds it rather than hand-spelled as "nl,en".
        MicrocopyManifestDrift.ofDeclared(scan.declared().references())
            .requireResolvable(ContentLocales.endingWithDefault(LocaleChain.ofTags("nl")));
    }
}
