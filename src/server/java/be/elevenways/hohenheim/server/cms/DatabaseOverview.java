package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.text.ByteText;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import be.elevenways.zenit.widget.common.builtin.FactListWidget;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A managed database's front door (the right-hand column of the Databases page): how it is reached, which apps use it
 * and the dumps it has on disk. Everything here is a stored fact; nothing dials the engine per render.
 *
 * AIDEV-NOTE: the password is deliberately not on this tab. The Restore tab's connection card shows it to the operator
 * and the tenant's Credentials tab to a holder of the credentials capability; a fact list has no reveal control, so
 * repeating it here would print the secret on every visit to the front door.
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
        main.add(card(HohenheimMicrocopy.DATABASE_TAB.of("connection"),
            connectionFacts(database, panelSlug, locales, resolver)));
        main.add(card(HohenheimMicrocopy.DATABASE_TAB.of("used_by"), usedByFacts(id, panelSlug, locales, resolver)));

        List<WidgetInstance> side = new ArrayList<>();
        side.add(card(HohenheimMicrocopy.DATABASE_OVERVIEW.of("backups"), backupFacts(database, locales, resolver)));
        if (id != null) {
            side.add(AppOverview.recent(Models.get(DatabaseModel.class), id));
        }
        return AppOverview.compose(List.of(), main, side);
    }

    /** Engine, where it runs, and the names an app connects with. */
    private static @NonNull List<WidgetFact> connectionFacts(@NonNull Row database, @NonNull String panelSlug,
                                                             @NonNull LocaleChain locales,
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
        facts.add(WidgetFact.of(HohenheimMicrocopy.DATABASE_TAB.of("database")
            .resolve(locales, resolver), database.get(DatabaseModel.DB_NAME)));
        facts.add(WidgetFact.of(HohenheimMicrocopy.DATABASE_TAB.of("user")
            .resolve(locales, resolver), database.get(DatabaseModel.DB_USER)));
        facts.add(WidgetFact.of(text("how_apps_connect", locales, resolver),
            text("how_apps_connect_value", locales, resolver)));
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
            facts.add(WidgetFact.instant(ByteText.human(dump.bytes()), dump.at().toString()));
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

    private static @NonNull WidgetInstance card(@NonNull Microcopy title, @NonNull List<WidgetFact> facts) {
        return CardWidget.of(title, new WidgetTree(List.of(
            new WidgetInstance(FactListWidget.ID, Map.of()).withData(facts))));
    }

    private static @NonNull String text(@NonNull String key, @NonNull LocaleChain locales,
                                        @Nullable MessageResolver resolver) {
        return HohenheimMicrocopy.DATABASE_OVERVIEW.of(key).resolve(locales, resolver);
    }
}
