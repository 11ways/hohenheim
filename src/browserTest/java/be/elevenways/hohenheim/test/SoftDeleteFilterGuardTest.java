package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No production source hand-rolls the soft delete SoftDeleteBehaviour owns: no spelled
 * {@code deleted_at IS NULL} filter and no hand stamp of deleted_at on a site, instance or
 * preview deployment.
 *
 * AIDEV-NOTE: the guard-mirror shape (a source scan, like the committed .guard rules). The
 * behaviour's find hook already scopes every find, count and updateAll, so a spelled filter is
 * dead weight at best -- and it is how the hand-rolled era produced the reads that FORGOT it.
 * A read that must see a trashed row says so ({@code withTrashed()}, {@code onlyTrashed()},
 * {@code StoredRows}); a relation hop into one of these models (where no find hook runs)
 * spells the behaviour's own {@code SOFT_DELETE.isNotTrashed()}, which this scan allows; a
 * trash is the model's delete. A pattern here sees only its literal shapes, so a pass is not
 * proof: review still owns the rest. Every exemption names its reason, and an exemption whose
 * file no longer matches fails, so it cannot outlive what it excused.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@DisplayName("Soft delete is the behaviour's, never hand-rolled")
class SoftDeleteFilterGuardTest {

    /** The production trees the rule covers. */
    private static final List<Path> ROOTS = List.of(
        Path.of("src/server/java"), Path.of("src/common/java"));

    /** A spelled trash filter on one of the three soft-deleted models, qualified or (inside the model) bare. */
    private static final Pattern FILTER = Pattern.compile(
        "(?:\\b(?:SiteModel|InstanceModel|PreviewDeploymentModel)\\.)?\\bDELETED_AT\\.is(?:Not)?Null\\(\\)");

    /** A hand stamp of deleted_at on one of the three soft-deleted models. */
    private static final Pattern STAMP = Pattern.compile(
        "\\.set\\(\\s*(?:SiteModel|InstanceModel|PreviewDeploymentModel)\\.DELETED_AT\\b");

    /** Files allowed to match, each with its reason; the reason is the whole justification. */
    private static final Map<String, String> EXEMPTIONS = Map.of(
        "src/server/java/be/elevenways/hohenheim/server/cms/InstanceResource.java",
        "its accessFunction still spells deleted_at IS NULL beside the release-kind filter; the"
            + " file carried another session's uncommitted work when the behaviour landed, so the"
            + " redundant half stays until that work is in -- remove both together");

    @Test
    @DisplayName("no production file spells a trash filter or stamps deleted_at by hand")
    void noHandRolledSoftDelete() throws IOException {
        // 1. The patterns see what they are meant to see, so an empty scan is not a blind one.
        assertThat(FILTER.matcher(".where(SiteModel.DELETED_AT.isNull())").find())
            .as("step 1: the filter pattern matches a qualified filter").isTrue();
        assertThat(FILTER.matcher("find().where(DELETED_AT.isNotNull())").find())
            .as("step 1: and a bare one inside a model").isTrue();
        assertThat(FILTER.matcher("InstanceModel.SOFT_DELETE.isNotTrashed()").find())
            .as("step 1: but never the behaviour's own criteria").isFalse();
        assertThat(STAMP.matcher("row.set(InstanceModel.DELETED_AT, Now.instant());").find())
            .as("step 1: the stamp pattern matches a hand stamp").isTrue();

        // 2. The scan: every hit outside the exemptions is a finding.
        Map<String, List<String>> hits = scan();
        Map<String, List<String>> findings = new TreeMap<>(hits);
        findings.keySet().removeAll(EXEMPTIONS.keySet());
        assertThat(findings)
            .as("step 2: hand-rolled soft delete found; use the behaviour (withTrashed/onlyTrashed,"
                + " StoredRows, the model's delete, SOFT_DELETE.isNotTrashed() in a relation hop)")
            .isEmpty();

        // 3. No exemption outlives what it excused.
        for (String exempt : EXEMPTIONS.keySet()) {
            assertThat(hits)
                .as("step 3: %s is exempt (%s) but no longer matches; delete the exemption",
                    exempt, EXEMPTIONS.get(exempt))
                .containsKey(exempt);
        }
    }

    /** @return file -> offending lines ("line: text") across the production roots */
    private static Map<String, List<String>> scan() throws IOException {
        Map<String, List<String>> hits = new TreeMap<>();
        for (Path root : ROOTS) {
            assertThat(Files.isDirectory(root)).as("the scan root %s exists", root).isTrue();
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                    List<String> lines = Files.readAllLines(file);
                    for (int index = 0; index < lines.size(); index++) {
                        String line = lines.get(index);
                        String trimmed = line.trim();
                        if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                            continue;
                        }
                        Matcher filter = FILTER.matcher(line);
                        Matcher stamp = STAMP.matcher(line);
                        if (filter.find() || stamp.find()) {
                            hits.computeIfAbsent(file.toString().replace('\\', '/'),
                                    key -> new ArrayList<>())
                                .add((index + 1) + ": " + trimmed);
                        }
                    }
                }
            }
        }
        return hits;
    }
}
