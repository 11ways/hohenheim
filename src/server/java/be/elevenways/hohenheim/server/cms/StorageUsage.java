package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCounts;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.DatabaseDumpModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.files.SiteFiles;
import be.elevenways.hohenheim.server.upstream.kinds.StaticUpstreamKind;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What uses each storage (board FO128): the recovery archive and the dumps a server setting sends there, the sites
 * keeping their own files there or still reading the files they did not move from there, the databases with dumps
 * stored there and the apps backing up there.
 *
 * AIDEV-NOTE: a page of storages is read in a fixed number of statements whatever its size: one for the sites, one
 * grouped count of databases, one of apps; the settings are read from memory. A site names its storage by name in
 * its kind's settings, so the live static sites are read once and judged here rather than queried by a JSON path.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
final class StorageUsage {

    private StorageUsage() {
    }

    /**
     * How much uses one storage.
     *
     * @param recoveryArchive whether the recovery archive setting names it
     * @param databaseDumps   whether the server-wide dump setting names it
     * @param sites           the sites keeping their own files there
     * @param databases       the databases with dumps stored there or choosing it for their own dumps
     * @param apps            the apps backing up there
     */
    record Counts(boolean recoveryArchive, boolean databaseDumps, long sites, long databases, long apps) {

        static final Counts NONE = new Counts(false, false, 0, 0, 0);

        /** @return whether anything uses it, so it cannot be deleted */
        boolean inUse() {
            return this.recoveryArchive || this.databaseDumps || this.sites > 0 || this.databases > 0
                || this.apps > 0;
        }

        /** @return the list's words ("Recovery archive, 4 apps"), or null when nothing uses it */
        @Nullable Microcopy words() {
            List<Microcopy> parts = new ArrayList<>();
            if (this.recoveryArchive) {
                parts.add(HohenheimMicrocopy.BACKUP_TARGET.of("used_by_recovery_archive"));
            }
            if (this.databaseDumps) {
                parts.add(HohenheimMicrocopy.BACKUP_TARGET.of("used_by_database_dumps"));
            }
            if (this.sites > 0) {
                parts.add(HohenheimCounts.of("sites", this.sites));
            }
            if (this.databases > 0) {
                parts.add(HohenheimCounts.of("databases", this.databases));
            }
            if (this.apps > 0) {
                parts.add(HohenheimCounts.of("instances", this.apps));
            }
            if (parts.isEmpty()) {
                return null;
            }
            Microcopy joined = parts.getFirst();
            for (Microcopy next : parts.subList(1, parts.size())) {
                joined = HohenheimMicrocopy.BACKUP_TARGET.of("used_by_joined").withArg("first", joined)
                    .withArg("next", next);
            }
            return joined;
        }
    }

    /** @return what uses each of the storages, by id; every asked id answered */
    static @NonNull Map<Integer, Counts> of(@NonNull List<Row> storages) {
        Map<Integer, String> names = new LinkedHashMap<>();
        for (Row storage : storages) {
            Integer id = storage.get(BackupTargetModel.ID);
            if (id != null) {
                names.put(id, String.valueOf((Object) storage.get(BackupTargetModel.NAME)));
            }
        }
        Map<Integer, Counts> counts = new HashMap<>();
        if (names.isEmpty()) {
            return counts;
        }
        Map<String, Long> sites = sitesByStorage();
        // A database counts once whether it has dumps there, chose it for its own, or both.
        Map<Integer, Set<Integer>> databaseSets = new HashMap<>();
        for (Row dump : Models.get(DatabaseDumpModel.class).find()
                .where(DatabaseDumpModel.TARGET_ID.in(names.keySet())).all()) {
            databaseSets.computeIfAbsent(dump.get(DatabaseDumpModel.TARGET_ID), ignored -> new HashSet<>())
                .add(dump.get(DatabaseDumpModel.DATABASE_ID));
        }
        for (Row database : Models.get(DatabaseModel.class).find()
                .where(DatabaseModel.BACKUP_TARGET_ID.in(names.keySet())).all()) {
            databaseSets.computeIfAbsent(database.get(DatabaseModel.BACKUP_TARGET_ID), ignored -> new HashSet<>())
                .add(database.get(DatabaseModel.ID));
        }
        Map<Integer, Long> databases = new HashMap<>();
        databaseSets.forEach((target, held) -> databases.put(target, (long) held.size()));
        Map<Integer, Long> apps = Models.get(InstanceModel.class).find()
            .countBy(InstanceModel.BACKUP_TARGET_ID, names.keySet());
        String archive = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET);
        String dumps = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.BACKUP_TARGET);
        names.forEach((id, name) -> counts.put(id, new Counts(name.equals(archive), name.equals(dumps),
            sites.getOrDefault(name, 0L), databases.getOrDefault(id, 0L), apps.getOrDefault(id, 0L))));
        return counts;
    }

    /** @return what uses the one storage */
    static @NonNull Counts of(@NonNull Row storage) {
        return of(List.of(storage)).getOrDefault(storage.get(BackupTargetModel.ID), Counts.NONE);
    }

    /**
     * The storage page's "Used by": one line per thing keeping something there, each linking to it, and what it
     * keeps there.
     */
    static @NonNull List<WidgetFact> facts(@NonNull Row storage, @NonNull String panelSlug,
                                           @NonNull AccessContext access) {
        String name = String.valueOf((Object) storage.get(BackupTargetModel.NAME));
        Integer id = storage.get(BackupTargetModel.ID);
        List<WidgetFact> facts = new ArrayList<>();
        Counts counts = of(storage);
        if (counts.recoveryArchive()) {
            facts.add(WidgetFact.of(access.text(HohenheimMicrocopy.BACKUP_TARGET.of("used_by_recovery_archive")),
                access.text(HohenheimMicrocopy.BACKUP_TARGET.of("keeps_recovery_archive"))));
        }
        if (counts.databaseDumps()) {
            facts.add(WidgetFact.of(access.text(HohenheimMicrocopy.BACKUP_TARGET.of("used_by_database_dumps")),
                access.text(HohenheimMicrocopy.BACKUP_TARGET.of("keeps_database_dumps"))));
        }
        for (Row site : liveFileSites()) {
            Map<String, Object> settings = RawValues.map(site.get(SiteModel.SETTINGS));
            if (!SiteFiles.readStorageNamesOf(settings).contains(name)) {
                continue;
            }
            // A site that moved away without moving its files still reads them here: the page says so.
            boolean kept = name.equals(SiteFiles.storageNameOf(settings));
            facts.add(WidgetFact.link(access.text(HohenheimMicrocopy.BACKUP_TARGET.of(kept ? "keeps_site"
                    : "keeps_site_unmoved")), String.valueOf((Object) site.get(SiteModel.NAME)),
                CmsRoutes.detail(panelSlug, HohenheimSlugs.SITES, site.get(SiteModel.ID)).toUrl()));
        }
        if (id != null) {
            Map<Integer, long[]> dumps = new LinkedHashMap<>();
            Map<Integer, String> databaseNames = new HashMap<>();
            for (Row dump : Models.get(DatabaseDumpModel.class).find()
                    .where(DatabaseDumpModel.TARGET_ID.eq(id)).all()) {
                Integer database = dump.get(DatabaseDumpModel.DATABASE_ID);
                long[] held = dumps.computeIfAbsent(database, ignored -> new long[2]);
                held[0]++;
                Long size = dump.get(DatabaseDumpModel.SIZE_BYTES);
                held[1] += size == null ? 0 : size;
                databaseNames.putIfAbsent(database, String.valueOf((Object) dump.get(DatabaseDumpModel.DATABASE_NAME)));
            }
            dumps.forEach((database, held) -> {
                String value = access.text(HohenheimMicrocopy.BACKUP_TARGET.of("keeps_dumps")
                    .withArg("dumps", HohenheimCounts.of("dumps", held[0])).withArg("bytes", held[1]));
                String label = databaseNames.get(database);
                facts.add(database == null ? WidgetFact.of(label, value)
                    : WidgetFact.link(label, value, CmsRoutes.detail(panelSlug, HohenheimSlugs.DATABASES, database)
                        .toUrl()));
            });
            // A database choosing the storage for its own dumps before it took one there.
            for (Row database : Models.get(DatabaseModel.class).find()
                    .where(DatabaseModel.BACKUP_TARGET_ID.eq(id)).all()) {
                Integer databaseId = database.get(DatabaseModel.ID);
                if (!dumps.containsKey(databaseId)) {
                    facts.add(WidgetFact.link(String.valueOf((Object) database.get(DatabaseModel.NAME)),
                        access.text(HohenheimMicrocopy.BACKUP_TARGET.of("keeps_no_dumps_yet")),
                        CmsRoutes.detail(panelSlug, HohenheimSlugs.DATABASES, databaseId).toUrl()));
                }
            }
            for (Row app : Models.get(InstanceModel.class).find().where(InstanceModel.BACKUP_TARGET_ID.eq(id)).all()) {
                facts.add(WidgetFact.link(String.valueOf((Object) app.get(InstanceModel.NAME)),
                    access.text(HohenheimMicrocopy.BACKUP_TARGET.of("keeps_app_backups")),
                    InstanceParts.recordRoute(panelSlug, app, null).toUrl()));
            }
        }
        if (facts.isEmpty()) {
            facts.add(WidgetFact.of(access.text(HohenheimMicrocopy.BACKUP_TARGET.of("used_by_none")),
                access.text(HohenheimMicrocopy.BACKUP_TARGET.of("used_by_none_detail"))));
        }
        return facts;
    }

    /** @return how many live sites keep or still read their own files in each storage, by its name; one read */
    static @NonNull Map<String, Long> sitesByStorage() {
        Map<String, Long> sites = new HashMap<>();
        for (Row site : liveFileSites()) {
            for (String name : SiteFiles.readStorageNamesOf(RawValues.map(site.get(SiteModel.SETTINGS)))) {
                sites.merge(name, 1L, Long::sum);
            }
        }
        return sites;
    }

    /** @return the live static sites serving their own files */
    private static @NonNull List<Row> liveFileSites() {
        List<Row> serving = new ArrayList<>();
        for (Row site : Models.get(SiteModel.class).find()
                .where(SiteModel.UPSTREAM_KIND.eq(StaticUpstreamKind.ID.toString())).all()) {
            if (SiteFiles.servesOwnFiles(site)) {
                serving.add(site);
            }
        }
        return serving;
    }
}
