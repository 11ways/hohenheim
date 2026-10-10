package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.test.support.GlobalTextSearchMoves;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.cms.test.support.TemporalFilterMoves;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The remote Spamservice entries' admin surfaces, stored before they moved onto store parts and compared exactly
 * after it; the q-to-search and date-pair-to-leaf filter moves are declared
 * correspondences, as is the keys create showing its judged client read-only.
 *
 * AIDEV-NOTE: the stored set ({@code /panel-surfaces/spamservice-remote.txt}) is today's behaviour, captured from the
 * legacy Resource family before the move; a failing comparison is a changed admin surface, never a file to refresh.
 * The test JVM runs no Spamservice, so every list is the disconnected one: the record surfaces need a live service
 * and are pinned by SpamserviceCmsContractTest's wire assertions instead.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class SpamserviceAdminSurfacesBrowserTest extends HohenheimTestBase {

    private static final String BASELINE = "/panel-surfaces/spamservice-remote.txt";

    @Test
    void theRemoteSpamserviceEntriesOfferWhatTheyOfferedBeforeTheMove() {
        SurfaceBaselines stored = SurfaceBaselines.load(SpamserviceAdminSurfacesBrowserTest.class, BASELINE);
        Row admin = Models.get(UserModel.class).find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        AccessContext operator = AccessContext.of(TenantConduits.stubFor(
            new UserPrincipal(admin.get(UserModel.ID), "Test Admin")));

        Map<String, PanelSurfaces> before = new HashMap<>();
        for (PanelSurfaces recorded : SurfaceBaselines.parseAll(baseline())) {
            before.put(recorded.caseName(), recorded);
        }

        // 1. Keys and samples changed nothing an operator sees: compared exactly, except that the keys create now
        //    shows the client its operation always takes from the parent read-only (declared correspondence).
        stored.judgedParentReadonly(surfaceCase(HohenheimSlugs.SPAMSERVICE_KEYS, operator).name(),
            SpamserviceClientKeysResource.create());
        for (String entry : List.of(HohenheimSlugs.SPAMSERVICE_KEYS, HohenheimSlugs.SPAMSERVICE_SAMPLES)) {
            stored.check(capture(entry, operator));
        }

        // 2. Clients and words: the legacy global q is now the list search over the columns the API searches, and
        //    nothing else moved (declared correspondence, never a recapture).
        for (String entry : List.of(HohenheimSlugs.SPAMSERVICE_CLIENTS, HohenheimSlugs.SPAMSERVICE_WORDS)) {
            PanelSurfaces after = capture(entry, operator);
            List<String> columns = entry.equals(HohenheimSlugs.SPAMSERVICE_CLIENTS) ? List.of("name") : List.of("word");
            stored.check(GlobalTextSearchMoves.of(columns).legacyProjection(
                before.get(after.caseName()), after));
        }

        // 3. Security events: the since/until date pair is now one temporal leaf over day, and nothing else moved.
        PanelResource<?> events = SpamserviceSecurityEventsResource.create();
        FilterSpec day = events.list().table().filters().stream().filter(filter -> filter.name().equals("day"))
            .findFirst().orElseThrow();
        PanelSurfaces after = capture(HohenheimSlugs.SPAMSERVICE_SECURITY_EVENTS, operator);
        stored.check(TemporalFilterMoves.of("since", "until", day, events.list().storePages().filterVocabulary(), null)
            .legacyProjection(before.get(after.caseName()), after));

        // 4. Every stored case matched exactly or through its declared move.
        stored.finish();
    }

    private static PanelSurfaces capture(String entry, AccessContext operator) {
        return PanelSurfaces.capture(surfaceCase(entry, operator));
    }

    private static SurfaceCase surfaceCase(String entry, AccessContext operator) {
        return SurfaceCase.of(HohenheimSlugs.ADMIN, entry, "operator", operator);
    }

    private static String baseline() {
        try (InputStream in = SpamserviceAdminSurfacesBrowserTest.class.getResourceAsStream(BASELINE)) {
            return new String(Objects.requireNonNull(in, BASELINE).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
