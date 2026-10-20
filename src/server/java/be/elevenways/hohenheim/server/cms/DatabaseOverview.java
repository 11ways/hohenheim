package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.i18n.wording.Reader;
import be.elevenways.protoblast.common.i18n.wording.Wording;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A managed database's front door (the right-hand column of the Databases page, and the Databases list's selected
 * record beside it): how it is reached, which apps use it and the dumps it has on disk. Everything here is a stored
 * fact; nothing dials the engine per render.
 *
 * AIDEV-NOTE: the password is a secret fact (plumage's masked reveal with its copy button), added only for a reader
 * the Restore tab's own gate admits ({@link DatabaseConnectionCard#passwordReadableBy}); anyone else sees the card
 * without it. It ships in the page masked, exactly as the Restore tab's connection card ships it.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
final class DatabaseOverview {

    private DatabaseOverview() {
    }

    /** The database's landing tab. */
    static @NonNull RecordOverview<Row> tab() {
        return RecordOverview.<Row>fields(RecordOverview.SLUG, HohenheimMicrocopy.INSTANCE.of("overview"))
            .withoutFields()
            .widgets(DatabaseOverview::widgets);
    }

    private static @NonNull WidgetTree widgets(@NonNull Row database, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        LocaleChain locales = conduit.getLocales();
        MessageResolver resolver = conduit.getMessageResolver();
        String panelSlug = CmsSupport.panelSlug(conduit);
        Integer id = database.get(DatabaseModel.ID);

        List<WidgetInstance> main = new ArrayList<>();
        main.add(CardWidget.ofFacts(HohenheimMicrocopy.DATABASE_TAB.of("connection"),
            HohenheimMicrocopy.DATABASE_OVERVIEW.of("how_apps_connect_value"),
            connectionFacts(database, access, panelSlug, locales, resolver)));
        main.add(CardWidget.ofFacts(HohenheimMicrocopy.DATABASE_TAB.of("used_by"),
            usedByFacts(id, panelSlug, locales, resolver)));

        List<WidgetInstance> side = new ArrayList<>();
        side.add(CardWidget.ofFacts(HohenheimMicrocopy.DATABASE_OVERVIEW.of("backups"),
            backupFacts(database, locales, resolver)));
        if (id != null) {
            side.add(AppOverview.recent(Models.get(DatabaseModel.class), id, access));
        }
        return AppOverview.compose(List.of(), main, side);
    }

    /** Engine, where it runs, the address and names an app connects with, and the password for a reader it admits. */
    private static @NonNull List<WidgetFact> connectionFacts(@NonNull Row database, @NonNull AccessContext access,
                                                             @NonNull String panelSlug, @NonNull LocaleChain locales,
                                                             @Nullable MessageResolver resolver) {
        List<WidgetFact> facts = new ArrayList<>();
        Object engine = database.get(DatabaseModel.ENGINE);
        if (engine != null) {
            facts.add(WidgetFact.badge(HohenheimMicrocopy.DATABASE_TAB.of("engine").resolve(locales, resolver),
                WidgetBadge.of(DatabaseModel.ENGINE, engine, locales, resolver)));
        }
        Integer engineId = database.get(DatabaseModel.ENGINE_ID);
        String runsOn = runsOn(database).resolve(locales, resolver);
        facts.add(DatabaseModel.isShared(database) && engineId != null
            ? WidgetFact.link(text("runs_on", locales, resolver), runsOn,
                CmsRoutes.detail(panelSlug, HohenheimSlugs.DATABASE_ENGINES, engineId).toUrl())
            : WidgetFact.of(text("runs_on", locales, resolver), runsOn));
        // The name an attached app dials over its link network, at the engine's own port; nothing while none serves.
        Integer id = database.get(DatabaseModel.ID);
        String handle = id == null ? null : DatabaseInstances.handleOf(id);
        ManagedDatabase.Engine kind = ManagedDatabase.Engine.forToken(database.get(DatabaseModel.ENGINE));
        facts.add(WidgetFact.of(text("connect_with", locales, resolver),
            handle == null || kind == null ? null : handle + ":" + kind.port()));
        facts.add(WidgetFact.of(HohenheimMicrocopy.DATABASE_TAB.of("database")
            .resolve(locales, resolver), database.get(DatabaseModel.DB_NAME)));
        facts.add(WidgetFact.of(HohenheimMicrocopy.DATABASE_TAB.of("user")
            .resolve(locales, resolver), database.get(DatabaseModel.DB_USER)));
        if (DatabaseConnectionCard.passwordReadableBy(database, access)) {
            facts.add(WidgetFact.secret(HohenheimMicrocopy.DATABASE_TAB.of("password").resolve(locales, resolver),
                database.get(DatabaseModel.DB_PASSWORD)));
        }
        return facts;
    }

    /** The live workloads holding this database's credentials, each linking to its own front door. */
    private static @NonNull List<WidgetFact> usedByFacts(@Nullable Integer id, @NonNull String panelSlug,
                                                         @NonNull LocaleChain locales,
                                                         @Nullable MessageResolver resolver) {
        List<WidgetFact> facts = new ArrayList<>();
        List<Row> instances = id == null ? List.of() : InstanceDatabaseLinks.liveInstances(id);
        for (Row instance : instances) {
            Object kind = instance.get(InstanceModel.KIND);
            facts.add(WidgetFact.link(
                kind == null ? "" : WidgetBadge.of(InstanceModel.KIND, kind, locales, resolver).label(),
                String.valueOf((Object) instance.get(InstanceModel.NAME)),
                InstanceParts.recordRoute(panelSlug, instance, null).toUrl()));
        }
        if (facts.isEmpty()) {
            facts.add(WidgetFact.of(text("used_by_none", locales, resolver),
                text("used_by_none_detail", locales, resolver)));
        }
        return facts;
    }

    /** The dumps on disk, newest first, under the retention they are kept to. */
    private static @NonNull List<WidgetFact> backupFacts(@NonNull Row database, @NonNull LocaleChain locales,
                                                         @Nullable MessageResolver resolver) {
        List<WidgetFact> facts = new ArrayList<>();
        if (Boolean.TRUE.equals(database.get(DatabaseModel.EPHEMERAL))) {
            facts.add(WidgetFact.of(text("backup_temporary", locales, resolver),
                text("backup_temporary_detail", locales, resolver)));
            return facts;
        }
        facts.add(WidgetFact.of(text("kept", locales, resolver), HohenheimMicrocopy.DATABASE_OVERVIEW.of("kept_value")
            .withArg("count", Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.BACKUP_RETENTION))
            .resolve(locales, resolver)));
        String name = database.get(DatabaseModel.NAME);
        List<DatabaseBackups.Stored> dumps = name == null ? List.of() : DatabaseBackups.stored(name);
        for (DatabaseBackups.Stored dump : dumps) {
            facts.add(WidgetFact.instant(Wording.bytes(Reader.of(locales, resolver), dump.bytes(), resolver),
                dump.at().toString()));
        }
        if (dumps.isEmpty()) {
            facts.add(WidgetFact.of(text("backup_never", locales, resolver),
                DatabaseParts.neverBackedUpDetail(database).resolve(locales, resolver)));
        }
        return facts;
    }

    /**
     * Where a database runs, in words: its shared engine and host (an engine row that is gone reads as its id), or
     * its own container's host.
     */
    static @NonNull Microcopy runsOn(@NonNull Row database) {
        String host = ServerModel.canonicalNameOf(database.get(DatabaseModel.SERVER_ID));
        if (!DatabaseModel.isShared(database)) {
            return HohenheimMicrocopy.DATABASE_OVERVIEW.of("runs_on_dedicated").withArg("host", host);
        }
        Integer engineId = database.get(DatabaseModel.ENGINE_ID);
        Row engine = Models.get(DatabaseEngineModel.class).findById(engineId);
        return HohenheimMicrocopy.DATABASE_OVERVIEW.of("runs_on_shared").withArg("host", host)
            .withArg("engine", engine != null ? String.valueOf((Object) engine.get(DatabaseEngineModel.NAME))
                : "#" + engineId);
    }

    private static @NonNull String text(@NonNull String key, @NonNull LocaleChain locales,
                                        @Nullable MessageResolver resolver) {
        return HohenheimMicrocopy.DATABASE_OVERVIEW.of(key).resolve(locales, resolver);
    }
}
