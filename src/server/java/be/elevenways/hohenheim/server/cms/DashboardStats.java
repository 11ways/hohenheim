package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.app.DashboardStat;
import be.elevenways.hohenheim.host.HostStanding;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.time.RelativeTime;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.text.ByteText;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The admin dashboard's count tiles (board Main): Apps, Hosts, Certificates and Backups, each with the line saying what
 * its count holds, read through the panel's own lists for the viewer.
 *
 * AIDEV-NOTE: a tile exists only where the list it counts and links to is registered and admits the viewer, so the
 * node's roles decide which tiles exist without a switch of their own. Every line is a fact the counted list already
 * shows (the app verdict, the host verdict, the certificate's state cell, the newest instance backup); a tile with no
 * fact behind a line draws none, never an invented one. The board's "succeeded last night" is not said: a backup
 * records when it ran, not which night's schedule it belonged to.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class DashboardStats {

    private DashboardStats() {
    }

    /**
     * @param apps the apps this panel lists for this viewer, read once for the dashboard
     * @return the tiles this viewer is offered, in the board's order
     */
    static @NonNull List<DashboardStat> read(@NonNull Panel panel, @NonNull List<AppDirectory.App> apps,
                                             @NonNull AccessContext access) {
        AppDirectory.Wording words = AppDirectory.Wording.of(access);
        List<DashboardStat> tiles = new ArrayList<>(4);
        if (AppDirectory.offers(panel, AppParts.SLUG, access)) {
            tiles.add(apps(panel, apps, words));
        }
        if (AppDirectory.offers(panel, ServerParts.SLUG, access)) {
            tiles.add(hosts(panel, access, words));
        }
        if (AppDirectory.offers(panel, HohenheimSlugs.CERTIFICATES, access)) {
            tiles.add(certificates(panel, access, words));
        }
        if (AppDirectory.offers(panel, InstanceBackupParts.SLUG, access)) {
            tiles.add(backups(panel, access, words));
        }
        return tiles;
    }

    /** Every app, and how many of them are live and how many have a problem, by the verdict their pages lead with. */
    private static @NonNull DashboardStat apps(@NonNull Panel panel, @NonNull List<AppDirectory.App> apps,
                                               AppDirectory.@NonNull Wording words) {
        int live = 0;
        int problems = 0;
        for (AppDirectory.App app : apps) {
            switch (app.health().tone()) {
                case OK -> live++;
                case ATTENTION, BROKEN -> problems++;
                case UNKNOWN -> {
                    // Starting, deploying or not read yet: neither live nor a problem.
                }
            }
        }
        List<Microcopy> parts = new ArrayList<>(2);
        if (live > 0) {
            parts.add(copy("stat_apps_live").withArg("count", live));
        }
        if (problems > 0) {
            parts.add(copy("stat_apps_problem").withArg("count", problems));
        }
        return tile("apps", words.say(copy("apps")), String.valueOf(apps.size()), words.join(parts), "cubes",
            panel, AppParts.SLUG);
    }

    /** Every host, tallied by its verdict's standing (the Hosts list's state cell). */
    private static @NonNull DashboardStat hosts(@NonNull Panel panel, @NonNull AccessContext access,
                                                AppDirectory.@NonNull Wording words) {
        List<Row> servers = AppDirectory.listed(panel, ServerParts.SLUG, access);
        Map<HostStanding, Integer> tally = new EnumMap<>(HostStanding.class);
        for (Row server : servers) {
            tally.merge(HostVerdict.of(server).standing(), 1, Integer::sum);
        }
        List<Microcopy> parts = new ArrayList<>(tally.size());
        tally.forEach((standing, count) -> parts.add(standing.tally(count)));
        return tile("hosts", words.say(Microcopy.of("plural").withFilter("scope", "server")),
            String.valueOf(servers.size()), words.join(parts), "server", panel, ServerParts.SLUG);
    }

    /**
     * Every certificate, and how many need a look by the list's own state cell; while none does, how long until the
     * next one expires.
     */
    private static @NonNull DashboardStat certificates(@NonNull Panel panel, @NonNull AccessContext access,
                                                       AppDirectory.@NonNull Wording words) {
        List<Row> certificates = AppDirectory.listed(panel, HohenheimSlugs.CERTIFICATES, access);
        int attention = 0;
        Long nextDays = null;
        Instant now = Now.instant();
        for (Row certificate : certificates) {
            StateLineCell state = CertificateParts.stateCell(certificate);
            if (state.variant() != BadgeVariant.SUCCESS) {
                attention++;
                continue;
            }
            Instant expires = certificate.get(CertificateModel.EXPIRES_ON);
            if (expires != null) {
                long days = Duration.between(now, expires).toDays();
                nextDays = nextDays == null ? days : Math.min(nextDays, days);
            }
        }
        Microcopy detail = attention > 0 ? copy("stat_certs_attention").withArg("count", attention)
            : nextDays != null ? copy("stat_certs_next").withArg("days", nextDays)
            : null;
        return tile("certificates", words.say(Microcopy.of("plural").withFilter("scope", "certificate")),
            String.valueOf(certificates.size()), detail == null ? null : words.say(detail), "lock", panel,
            HohenheimSlugs.CERTIFICATES);
    }

    /**
     * The apps that are backed up: of the workloads with a backup target, how many have a newest backup that
     * completed, and when the newest completed copy ran and how big it is.
     */
    private static @NonNull DashboardStat backups(@NonNull Panel panel, @NonNull AccessContext access,
                                                  AppDirectory.@NonNull Wording words) {
        InstanceBackupModel backups = Models.get(InstanceBackupModel.class);
        int total = 0;
        int complete = 0;
        Row newest = null;
        for (Row instance : AppDirectory.listed(panel, InstanceParts.SLUG, access)) {
            if (InstanceParts.isGenerated(instance) || instance.get(InstanceModel.BACKUP_TARGET_ID) == null) {
                continue;
            }
            total++;
            Row latest = backups.newestOf(instance.get(InstanceModel.ID));
            if (latest == null || !InstanceBackupModel.STATUS_COMPLETE.equals(latest.get(InstanceBackupModel.STATUS))) {
                continue;
            }
            complete++;
            Instant at = latest.get(InstanceBackupModel.CREATED_AT);
            Instant newestAt = newest == null ? null : newest.get(InstanceBackupModel.CREATED_AT);
            if (at != null && (newestAt == null || at.isAfter(newestAt))) {
                newest = latest;
            }
        }
        String label = words.say(copy("stat_backups"));
        if (total == 0) {
            return tile("backups", label, "0", words.say(copy("stat_backups_none")), "box-archive", panel,
                InstanceBackupParts.SLUG);
        }
        String detail = null;
        if (newest != null) {
            Long size = newest.get(InstanceBackupModel.SIZE_BYTES);
            detail = words.say(copy("stat_backups_newest")
                .withArg("ago", RelativeTime.ago(newest.get(InstanceBackupModel.CREATED_AT), wording(words)))
                .withArg("size", ByteText.human(size)));
        }
        return tile("backups", label,
            words.say(copy("stat_backups_value").withArg("ok", complete).withArg("total", total)), detail,
            "box-archive", panel, InstanceBackupParts.SLUG);
    }

    private static @NonNull DashboardStat tile(@NonNull String key, @NonNull String label, @NonNull String value,
                                               @Nullable String detail, @NonNull String icon, @NonNull Panel panel,
                                               @NonNull String slug) {
        return new DashboardStat(key, label, value, detail, icon, CmsRoutes.list(panel.slug(), slug).toUrl());
    }

    private static @Nullable RelativeTimeWording wording(AppDirectory.@NonNull Wording words) {
        return words.resolver() == null ? null : RelativeTimeWording.resolve(words.locales(), words.resolver());
    }

    private static @NonNull Microcopy copy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "dashboard");
    }
}
