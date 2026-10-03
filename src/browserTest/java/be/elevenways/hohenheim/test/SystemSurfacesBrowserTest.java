package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.cms.BackupTargetParts;
import be.elevenways.hohenheim.server.cms.BanParts;
import be.elevenways.hohenheim.server.cms.NotificationChannelParts;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.cms.test.support.PanelSurfaces;
import be.elevenways.zenit.cms.test.support.PlacedOperationMoves;
import be.elevenways.zenit.cms.test.support.SurfaceBaselines;
import be.elevenways.zenit.cms.test.support.SurfaceCase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * The notification channel, backup target and ban entries of the Hohenheim legacy-admin remainder, stored before they
 * move onto shared parts and compared exactly after it.
 *
 * AIDEV-NOTE: the stored set ({@code /panel-surfaces/system.txt}) is the behaviour captured on the legacy
 * NotificationChannelResource, BackupTargetResource and BanResource, now their *Parts. A failing comparison is a
 * changed surface, never a file to refresh; an accepted difference is declared as a move.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class SystemSurfacesBrowserTest extends HohenheimTestBase {

    private static final String PREFIX = "system-surfaces-";
    private static final String ADMIN = HohenheimSlugs.ADMIN;
    private static final String CHANNELS = "notifications";
    private static final String TARGETS = "backup-targets";
    private static final String BANS = "bans";

    private static String channelId;
    private static String targetId;
    private static String activeBanId;
    private static String liftedBanId;
    private static AccessContext operator;
    private static AccessContext tenant;

    @BeforeAll
    static void seed() throws Exception {
        freshSeededDatabase();
        int tenantId = ApiSupport.user(PREFIX + "tenant@hohenheim.local", "System Surfaces Tenant");
        channelId = String.valueOf(channel(PREFIX + "channel"));
        targetId = String.valueOf(target(PREFIX + "target"));
        activeBanId = String.valueOf(ban("203.0.113.231", true));
        liftedBanId = String.valueOf(ban("203.0.113.232", false));
        operator = TenantConduits.operator();
        tenant = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(tenantId, "System Surfaces Tenant")));
    }

    /** The fixture rows leave with the class. */
    @AfterAll
    static void cleanUp() {
        HardDeletes.byId(Models.get(NotificationChannelModel.class), Integer.parseInt(channelId));
        HardDeletes.byId(Models.get(BackupTargetModel.class), Integer.parseInt(targetId));
        HardDeletes.byId(Models.get(BanModel.class), Integer.parseInt(activeBanId));
        HardDeletes.byId(Models.get(BanModel.class), Integer.parseInt(liftedBanId));
    }

    @Test
    void theSystemEntriesOfferWhatTheyOfferedBeforeTheMove() {
        // The channel test, the target test and the ban lift moved from their legacy record action routes onto the
        // placed operations of the same ids; every other fact compares exactly.
        SurfaceBaselines stored = SurfaceBaselines.load(SystemSurfacesBrowserTest.class, "/panel-surfaces/system.txt")
            .placedOperations(PlacedOperationMoves.of(NotificationChannelParts.TEST.id(), BackupTargetParts.TEST.id(),
                BanParts.LIFT.id()));

        // 1. Each entry for the operator, record-less and on its records; a tenant is refused the panel.
        for (String entry : List.of(CHANNELS, TARGETS, BANS)) {
            stored.check(capture(SurfaceCase.of(ADMIN, entry, "operator", operator)));
            stored.check(capture(SurfaceCase.of(ADMIN, entry, "tenant", tenant)
                .refusedFor(ZenitRefusalReason.FORBIDDEN)));
        }
        stored.check(capture(SurfaceCase.of(ADMIN, CHANNELS, "operator", operator).onRecord(channelId, "channel")));
        stored.check(capture(SurfaceCase.of(ADMIN, TARGETS, "operator", operator).onRecord(targetId, "target")));
        stored.check(capture(SurfaceCase.of(ADMIN, BANS, "operator", operator).onRecord(activeBanId, "active")));
        stored.check(capture(SurfaceCase.of(ADMIN, BANS, "operator", operator).onRecord(liftedBanId, "lifted")));

        // 2. Every stored case matched exactly.
        stored.finish();
    }

    /** A capture with every generated fixture id declared at the bindings a destination carries it. */
    private static PanelSurfaces capture(SurfaceCase fixture) {
        return PanelSurfaces.capture(fixture
            .key(CHANNELS, "channel", channelId).key(TARGETS, "target", targetId)
            .key(BANS, "active_ban", activeBanId).key(BANS, "lifted_ban", liftedBanId));
    }

    private static int channel(String name) {
        Model channels = Models.get(NotificationChannelModel.class);
        Row row = channels.createEmptyRow();
        row.set(NotificationChannelModel.NAME, name);
        row.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
        row.set(NotificationChannelModel.FORMAT, NotificationChannelModel.FORMAT_GENERIC);
        row.set(NotificationChannelModel.URL, "https://example.com/" + name);
        row.set(NotificationChannelModel.EVENTS, List.of("cert_expiring"));
        channels.save(row);
        return row.get(NotificationChannelModel.ID);
    }

    private static int target(String name) {
        Model targets = Models.get(BackupTargetModel.class);
        Row row = targets.createEmptyRow();
        row.set(BackupTargetModel.NAME, name);
        row.set(BackupTargetModel.KIND, "hohenheim:filesystem");
        row.set(BackupTargetModel.SETTINGS, Map.of("path", "/tmp/" + name));
        targets.save(row);
        return row.get(BackupTargetModel.ID);
    }

    /** A ban row as the audit trail stores it; no firewall is programmed for a surface capture. */
    private static int ban(String ip, boolean active) {
        Model bans = Models.get(BanModel.class);
        Row row = bans.createEmptyRow();
        row.set(BanModel.IP, ip);
        row.set(BanModel.REASON, PREFIX + "reason");
        row.set(BanModel.SOURCE, BanModel.SOURCE_MANUAL);
        row.set(BanModel.ACTIVE, active);
        row.set(BanModel.CREATED_AT, Now.instant());
        if (!active) {
            row.set(BanModel.LIFTED_AT, Now.instant());
            row.set(BanModel.LIFTED_BY, "operator");
        }
        bans.save(row);
        return row.get(BanModel.ID);
    }
}
