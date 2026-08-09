package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.protoblast.guard.NamedPattern;
import be.elevenways.protoblast.guard.ScanResult;
import be.elevenways.protoblast.guard.ScanRoot;
import be.elevenways.protoblast.guard.SourceRule;
import be.elevenways.protoblast.guard.SourceRuleScanner;
import be.elevenways.protoblast.guard.Violation;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.security.KnownPermission;
import be.elevenways.zenit.common.security.KnownPermissions;
import be.elevenways.zenit.microcopy.server.DefaultCatalogLoader;
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
 * Every {@code hohenheim.*} permission the code ENFORCES is also in the vocabulary the
 * grants editor offers, and carries copy in en and nl.
 *
 * AIDEV-NOTE: this exists because {@code hohenheim.databases.create} shipped enforced but
 * unregistered (2026-08-09). The grants editor's field is an autocomplete over
 * KnownPermissions.all() (zenit-auth AuthGrantsBinding), so an unregistered permission is
 * one an admin cannot find -- it denies by invisibility while every gate keeps working,
 * and the surface test that covered the lane granted the string PROGRAMMATICALLY and so
 * never touched the vocabulary at all. The walk is the shared protoblast-source-guard
 * scanner; the JUDGEMENT is a runtime one on purpose and that is why this is a hohenheim
 * test rather than a source-guard rule: registration happens through indirection
 * ({@code HohenheimPanel.ACCESS.value()}), which only the booted registry can resolve.
 */
class PermissionVocabularyTest {

    /** A permission literal at its DECLARATION site; group 1 is the permission string. */
    private static final Pattern DECLARED =
        Pattern.compile("Permission\\.of\\(\"(hohenheim\\.[^\"]+)\"\\)");

    @BeforeAll
    static void boot() {
        HohenheimTestRuntime.ensureBooted();
        // THE registration site: the same call ServerMain makes at boot.
        ServerMain.installAuthBaselines();
    }

    @Test
    void everyEnforcedPermissionIsOfferedByTheGrantsEditorAndDescribedInBothLocales()
            throws IOException {
        Path server = Path.of("src/server/java/be/elevenways/hohenheim");
        Path common = Path.of("src/common/java/be/elevenways/hohenheim");
        assertThat(Files.isDirectory(server) && Files.isDirectory(common))
            .as("the scan needs the hohenheim project dir as its working directory")
            .isTrue();

        List<String> known = KnownPermissions.all();
        assertThat(known)
            .as("the vocabulary really was registered (an empty corpus makes this vacuous)")
            .contains("hohenheim.admin.access");

        // The judge doubles as the collector: the copy check below is about exactly the
        // permissions this walk FOUND, never about strings merely offered by an endpoint.
        List<String> declared = new ArrayList<>();
        SourceRule rule = SourceRule.builder("permission-vocabulary")
            .consequence("an enforced permission missing from KnownPermissions.register"
                + " (ServerMain.installAuthBaselines) cannot be found in the grants editor,"
                + " whose field is an autocomplete over KnownPermissions.all()")
            .root(ScanRoot.of(server, "server"))
            .root(ScanRoot.of(common, "common"))
            .extensions("java")
            .pattern(NamedPattern.of("permission-declaration", DECLARED, "server"))
            .pattern(NamedPattern.of("permission-declaration", DECLARED, "common"))
            .judge(match -> {
                declared.add(match.group(1));
                return known.contains(match.group(1)) ? null : match.group(1);
            })
            .build();

        ScanResult result = SourceRuleScanner.scan(rule);
        assertThat(result.scannedFiles())
            .as("the scan actually walked the source tree (a zero count is vacuous)")
            .isGreaterThan(20);
        assertThat(result.violations())
            .as(() -> "enforced but unreachable in the grants editor:\n"
                + String.join("\n", result.violations().stream().map(Violation::format).toList()))
            .isEmpty();

        assertThat(declared)
            .as("the walk found the known declaration sites (a vacuous walk finds none)")
            .contains("hohenheim.admin.access", "hohenheim.instances.create",
                "hohenheim.databases.create");

        // The half the scanner structurally cannot see: a permission offered without
        // resolvable copy renders as a raw token in the editor, in one locale or both.
        List<String> undescribed = new ArrayList<>();
        DefaultCatalogLoader catalogs = new DefaultCatalogLoader();
        for (KnownPermission entry : KnownPermissions.entries()) {
            if (!declared.contains(entry.permission())) {
                continue;
            }
            Microcopy description = entry.description();
            if (description == null) {
                undescribed.add(entry.permission() + " -> no description at all");
                continue;
            }
            for (String tag : List.of("en", "nl")) {
                String resolved = description.resolve(LocaleChain.ofTags(tag), catalogs);
                if (resolved.equals(description.key())) {
                    undescribed.add(tag + " " + entry.permission() + " -> '" + resolved + "'");
                }
            }
        }
        assertThat(undescribed)
            .as("every enforced hohenheim permission carries real copy in en AND nl")
            .isEmpty();
    }
}
