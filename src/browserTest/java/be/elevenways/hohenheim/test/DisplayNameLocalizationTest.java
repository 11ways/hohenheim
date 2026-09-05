package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.instance.InstanceKindInfo;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.hohenheim.upstream.UpstreamKindInfo;
import be.elevenways.hohenheim.upstream.UpstreamKinds;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.diagnostic.CommentMode;
import be.elevenways.protoblast.guard.NamedPattern;
import be.elevenways.protoblast.guard.ScanResult;
import be.elevenways.protoblast.guard.ScanRoot;
import be.elevenways.protoblast.guard.SourceRule;
import be.elevenways.protoblast.guard.SourceRuleScanner;
import be.elevenways.protoblast.diagnostic.Suppression;
import be.elevenways.protoblast.guard.Violation;
import be.elevenways.zenit.common.setting.ContentLocales;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.microcopy.server.DefaultCatalogLoader;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code TypeDefinition.getDisplayName()} is an English literal by contract, so nothing
 * server-side may CALL it -- {@code getLabel()} is the only reading a user ever sees.
 *
 * AIDEV-NOTE: this exists because two refusals embedded a display name as an argument of
 * a LOCALIZED violation (OwnedInstances instance_kind_owner_managed, SiteDatabaseResource
 * site_type_no_injection, 2026-08-09): a Dutch reader got a Dutch sentence with "Site
 * container" inside it. The guard is deliberately wider than that one shape -- it bans
 * the CALL, not just the withArg spelling -- because a display name is equally wrong in a
 * page title, a toast or a flash, and a rule that named only withArg would have to be
 * re-widened at every new call shape. Zero legitimate call sites exist; a genuinely
 * non-user-facing one declares itself with the same-line marker.
 */
class DisplayNameLocalizationTest {

    /** Where the browserTest source root's classes live; see {@link #ships}. */
    private static final String TEST_PACKAGE = "be.elevenways.hohenheim.test.";

    /**
     * The fewest shipped kinds the two registries can honestly hold -- six upstream kinds
     * are documented in CLAUDE.md and the instance kinds outnumber them.
     */
    private static final int SHIPPED_KIND_FLOOR = 10;

    /** A CALL of getDisplayName; the declaration carries no dot and never matches. */
    private static final Pattern CALL = Pattern.compile("\\.getDisplayName\\(\\)");

    @BeforeAll
    static void boot() {
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void noServerCodeRendersATypeDefinitionsEnglishDisplayName() throws IOException {
        Path server = Path.of("src/server/java/be/elevenways/hohenheim");
        Path common = Path.of("src/common/java/be/elevenways/hohenheim");
        assertThat(Files.isDirectory(server) && Files.isDirectory(common))
            .as("the scan needs the hohenheim project dir as its working directory")
            .isTrue();

        SourceRule rule = SourceRule.builder("display-name-localization")
            .consequence("getDisplayName() is an English literal: reading it into anything"
                + " a user sees renders half-translated copy. Use getLabel(), which is a"
                + " Microcopy and resolves in the reader's locale even as a message argument")
            .root(ScanRoot.of(server, "server"))
            .root(ScanRoot.of(common, "common"))
            .extensions("java")
            .commentMode(CommentMode.STRIP_BLOCK_AWARE)
            .suppression(Suppression.sameLine("display-name-ok"))
            .pattern(NamedPattern.of("display-name-call", CALL, "server"))
            .pattern(NamedPattern.of("display-name-call", CALL, "common"))
            .build();

        ScanResult result = SourceRuleScanner.scan(rule);
        assertThat(result.scannedFiles())
            .as("the scan actually walked the source tree (a zero count is vacuous)")
            .isGreaterThan(20);
        assertThat(result.violations())
            .as(() -> "English display names read into user-facing text:\n"
                + String.join("\n", result.violations().stream().map(Violation::format).toList()))
            .isEmpty();
    }

    /**
     * The half the scanner cannot see: every registered kind and site type actually HAS
     * copy behind its label, in en and nl. A getLabel() override pointing at a key that
     * was never written renders the raw key, which is worse than the English it replaced.
     */
    @Test
    void everyRegisteredTypeLabelResolvesInBothLocales() {
        DefaultCatalogLoader catalogs = new DefaultCatalogLoader();
        List<String> broken = new ArrayList<>();

        // Touch the registration hooks so BlastAutoLoadInit has populated both registries.
        assertThat(InstanceKinds.getHandler("hohenheim:docker_container"))
            .as("the instance-kind registry is populated (an empty walk is vacuous)")
            .isNotNull();
        assertThat(UpstreamKindHandlers.getHandler("hohenheim:static"))
            .as("the upstream-kind registry is populated (an empty walk is vacuous)")
            .isNotNull();

        // The discriminator ITSELF, pinned here rather than left to fork allocation:
        // whether a fixture that declares a fake kind happens to share this JVM is luck,
        // so the rule is proven directly on both sides instead of hoping for the polluted
        // fork that would otherwise be the only place it is exercised.
        assertThat(ships(UpstreamKinds.REGISTRY.iterator().next()))
            .as("a kind declared outside the test tree is judged").isTrue();
        assertThat(ships(new TestTreeDeclaration()))
            .as("a kind declared inside the test tree is skipped").isFalse();

        // The DESCRIPTION is walked beside the label because it became a Microcopy too
        // (the card presentations draw it): an English literal there rots exactly the same
        // way, and nothing else would notice.
        int walked = 0;
        for (InstanceKindInfo handler : InstanceKindRegistry.REGISTRY) {
            if (!ships(handler)) {
                continue;
            }
            walked++;
            check(handler.getLabel(), "instance kind " + handler.typeId(), catalogs, broken);
            check(handler.getDescription(), "instance kind description "
                + handler.typeId(), catalogs, broken);
        }
        for (UpstreamKindInfo type : UpstreamKinds.REGISTRY) {
            if (!ships(type)) {
                continue;
            }
            walked++;
            check(type.getLabel(), "upstream kind " + type.typeId(), catalogs, broken);
            check(type.getDescription(), "upstream kind description "
                + type.typeId(), catalogs, broken);
        }

        // The skip above must never be able to empty the walk: a filter that quietly
        // excluded everything would report green about nothing at all.
        assertThat(walked)
            .as("shipped kinds actually walked, after the test-declared ones were skipped")
            .isGreaterThanOrEqualTo(SHIPPED_KIND_FLOOR);
        assertThat(broken)
            .as("every type label and description resolves to real copy in en AND nl")
            .isEmpty();
    }

    /**
     * The half neither the scanner nor a direct resolve() can see: a label travels as a
     * message ARGUMENT, and the readers that matter most for a refusal (a caught
     * Violations, a log line, a test report) never render the surrounding message. If the
     * argument is not rendered there, getLabel() buys nothing over the English literal it
     * replaced -- it just swaps one wrong string for a debug toString.
     *
     * AIDEV-NOTE: registry-driven on purpose. The live call sites (OwnedInstances
     * instance_kind_owner_managed, VolumeBackends host_no_volume_quota) are one instance
     * each of the class; walking the registries means the next refusal that embeds a label
     * is covered without anyone remembering to extend this.
     */
    @Test
    void everyTypeLabelSurvivesBeingReadBackOutOfARefusal() {
        DefaultCatalogLoader catalogs = new DefaultCatalogLoader();
        LocaleChain described = LocaleChain.of(ContentLocales.getDefault());
        List<String> broken = new ArrayList<>();

        for (InstanceKindInfo handler : InstanceKindRegistry.REGISTRY) {
            if (!ships(handler)) {
                continue;
            }
            checkRefusal(handler.getLabel(), "instance kind " + handler.typeId(),
                catalogs, described, broken);
        }
        for (UpstreamKindInfo type : UpstreamKinds.REGISTRY) {
            if (!ships(type)) {
                continue;
            }
            checkRefusal(type.getLabel(), "upstream kind " + type.typeId(),
                catalogs, described, broken);
        }

        assertThat(broken)
            .as("every label embedded in a refusal is read back as TEXT")
            .isEmpty();
    }

    /** Stands in for a fixture-declared kind; its PACKAGE is the whole point. */
    private static final class TestTreeDeclaration {
    }

    /**
     * Whether a registration SHIPS, as opposed to being declared by a test fixture.
     *
     * AIDEV-NOTE: both registries are global and populated at CLASS LOAD, so any fixture
     * that declares a fake kind pollutes this walk for every test sharing the JVM fork --
     * which is how three `hohenheim:fake_*` kinds appeared here overnight without one of
     * them changing (2026-08-23). The discriminator is STRUCTURAL, never a name list: a
     * shipped kind can only be excluded by moving its class into the test source root,
     * and anything unrecognised is still checked. The count assertion above is what stops
     * this from silently emptying the walk.
     */
    private static boolean ships(@NonNull Object registration) {
        return !registration.getClass().getName().startsWith(TEST_PACKAGE);
    }

    private static void checkRefusal(Microcopy label, String what, DefaultCatalogLoader catalogs,
                                     LocaleChain described, List<String> broken) {
        String message = Violations.ofField("kind", "unused",
            Microcopy.of("instance_kind_owner_managed").withFilter("scope", "violations")
                .withArg("kind", label)).getMessage();
        if (message.contains("Microcopy{")) {
            broken.add(what + " -> reached the reader as a debug toString: " + message);
        } else if (!message.contains(label.resolve(described, catalogs))) {
            broken.add(what + " -> its text is missing from the refusal: " + message);
        }
    }

    private static void check(Microcopy label, String what, DefaultCatalogLoader catalogs,
                              List<String> broken) {
        for (String tag : List.of("en", "nl")) {
            String resolved = label.resolve(LocaleChain.ofTags(tag), catalogs);
            if (resolved.equals(label.key()) && !label.isLiteral()) {
                broken.add(tag + " " + what + " -> '" + resolved + "'");
            }
        }
    }
}
