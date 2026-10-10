package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.WordedKind;
import be.elevenways.hohenheim.auth.SiteAuthProviderTypeRegistry;
import be.elevenways.hohenheim.backup.BackupTargetRegistry;
import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.instance.VariableTypeRegistry;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.source.GitProviderKindRegistry;
import be.elevenways.hohenheim.upstream.UpstreamKinds;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.CmsMicrocopy;
import be.elevenways.zenit.common.setting.ContentLocales;
import be.elevenways.zenit.microcopy.server.JavaMicrocopyKeys;
import be.elevenways.zenit.microcopy.server.MicrocopyManifestDrift;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import be.elevenways.zenit.test.support.ActivityLabelCoverage;
import be.elevenways.zenit.test.support.TaskLabelCoverage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
     * AIDEV-NOTE: every {@link HohenheimMicrocopy} constant's {@code of} ({@code HohenheimMicrocopy.VIOLATIONS.of}) and
     * zenit-cms's {@code CmsMicrocopy.COPY.of} are scope constants JavaMicrocopyKeys finds on its own, so a key read
     * through the scope home is judged like a bare {@code Microcopy.of}. Only a helper of another shape is declared
     * here, which is why no class may spell a scoped helper of its own: the scan would not see a key it builds.
     */
    private JavaMicrocopyKeys scan() {
        return JavaMicrocopyKeys.in(Path.of("src/common"), Path.of("src/server"))
            // The helpers' own files only FORWARD their parameters; they declare no key.
            .excluding("HohenheimMicrocopy.java", "HohenheimViolations.java", "HohenheimCounts.java")
            // An operation on an instance refused, the instance named.
            .factory("HohenheimViolations.instanceRefusal", List.of("scope=" + HohenheimMicrocopy.VIOLATIONS.scope()),
                List.of())
            .factory("HohenheimViolations.instanceRefusalText",
                List.of("scope=" + HohenheimMicrocopy.VIOLATIONS.scope()), List.of())
            // A counted noun ("2 stacks") a sentence counting several things carries as an argument.
            .factory("HohenheimCounts.of", List.of("scope=" + HohenheimMicrocopy.COUNT.scope()), List.of())
            // zenit-cms's own words (a "Delete" label) Hohenheim reads under that module's scope.
            .factory("CmsMicrocopy.of", List.of("scope=" + CmsMicrocopy.SCOPE), List.of())
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

    @Test
    void theActivityLogsOwnCopyShipsUnderHohenheimsScope() {
        // Its description and its default filter's chip shipped under core's "activity" scope, a foreign module's name.
        ShippedCatalogs catalogs = new ShippedCatalogs();
        for (Microcopy copy : List.of(CmsSupport.navHint(HohenheimMicrocopy.HOHENHEIM),
                HohenheimMicrocopy.HOHENHEIM.of("people_only"))) {
            for (String language : List.of("en", "nl")) {
                assertNotNull(catalogs.resolveSource(copy.key(), LocaleChain.ofTags(language), copy.filters()),
                    copy.key() + " ships under scope " + HohenheimMicrocopy.HOHENHEIM.scope() + " in " + language);
            }
        }
    }

    @Test
    void everyActivityVerbHasALabel() {
        // "move_shared" printed its raw key, and every other verb read its label from zenit-cms's scope.
        ActivityLabelCoverage.requireShipped(HohenheimMicrocopy.HOHENHEIM.scope(), HohenheimActivityAction.class);
        // A row written outside an operation read "Jelle: Deployed Instance #3": every verb tells it as a sentence, and
        // the coverage above then requires that sentence in en and nl.
        for (HohenheimActivityAction verb : HohenheimActivityAction.values()) {
            assertNotNull(verb.happened(), verb.id() + " heads its rows with a sentence");
        }
    }

    @Test
    void everyTaskAndKindReadsAsWordsInBothLanguages() {
        // 1. Every task's label ships in en and nl: the framework's own coverage, since the key is the task's id path
        //    (ScheduledTask.labelIn), which no literal-key scan sees.
        TaskLabelCoverage.requireShipped(21, Path.of("src/server/java"), Path.of("src/common/java"));

        // 2. Every production kind's name and description ship in en and nl, for the instance, upstream, git provider,
        //    backup target, template variable type and site auth provider families; both are keyed by the kind's id
        //    path (WordedKind), so a new kind without words fails here, never as a raw key (or an English display name
        //    in every language) on a kind picker. Touching InstanceKinds runs the kinds' autoload first.
        ShippedCatalogs catalogs = new ShippedCatalogs();
        InstanceKinds.kindsWhere(kind -> true);
        List<WordedKind> kinds = new ArrayList<>();
        InstanceKindRegistry.REGISTRY.forEach(kinds::add);
        UpstreamKinds.REGISTRY.forEach(kinds::add);
        GitProviderKindRegistry.REGISTRY.forEach(kinds::add);
        BackupTargetRegistry.REGISTRY.forEach(kinds::add);
        VariableTypeRegistry.REGISTRY.forEach(kinds::add);
        SiteAuthProviderTypeRegistry.REGISTRY.forEach(kinds::add);
        kinds.removeIf(kind -> !kind.getClass().getName().startsWith("be.elevenways.hohenheim.server."));
        assertTrue(kinds.size() >= 26, () -> "step 2: the registries list the production kinds, found " + kinds);
        List<String> missing = new ArrayList<>();
        for (WordedKind kind : kinds) {
            for (Microcopy words : List.of(kind.getLabel(), kind.getDescription())) {
                for (String language : List.of("en", "nl")) {
                    if (catalogs.resolveSource(words.key(), LocaleChain.ofTags(language), words.filters()) == null) {
                        missing.add(kind.typeId() + " " + words.key() + words.filters() + " in " + language);
                    }
                }
            }
        }
        assertEquals(List.of(), missing, "step 2: every kind reads as words, in en and nl");
    }
}
