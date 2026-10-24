package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.render.table.EnumBadgeState;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.panel.RecordTabGate;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The stored connection facts of a managed database, as the {@code hohenheim:cms/database-connection} card both the
 * admin Restore tab and the tenant Credentials tab draw: the engine and the database's state as badges, where it runs
 * (the Overview's own words, {@link DatabaseOverview#runsOn}), its names, and the password's reveal control.
 *
 * AIDEV-NOTE: this never reads the plaintext password. The card names the record's secret
 * ({@link HohenheimSecrets#DATABASE_PASSWORD}); the reveal fetches it per press through core's reveal route, which
 * asks its own gate (the operator or the {@code credentials} capability) and records the reveal. A secret in the page
 * HTML or widget state is readable by anyone who can see or cache the page, pressed or not.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
final class DatabaseConnectionCard {

    private DatabaseConnectionCard() {
    }

    /**
     * @param operator whether the reader is the operator, who is told where the database runs; a tenant never is (the
     *                 host is operator inventory)
     * @return the card's variables, a missing name as empty text
     */
    static @NonNull Map<String, Object> facts(@NonNull Row database, boolean operator) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("name", Objects.toString(database.get(DatabaseModel.NAME), ""));
        facts.put("engine", EnumBadgeState.ofNullable(DatabaseModel.ENGINE, database.get(DatabaseModel.ENGINE)));
        // What the database does (DatabaseVerdict, the badge its list and host page draw), never the stored token.
        facts.put("status", DatabaseVerdict.ofDatabase(database).badge());
        if (operator) {
            facts.put("runsOn", DatabaseOverview.runsOn(database));
        }
        facts.put("dbName", Objects.toString(database.get(DatabaseModel.DB_NAME), ""));
        facts.put("dbUser", Objects.toString(database.get(DatabaseModel.DB_USER), ""));
        facts.put("passwordSecret", HohenheimSecrets.DATABASE_PASSWORD.ref(database.get(DatabaseModel.ID)));
        return facts;
    }

    /**
     * Whether the reader may follow the Overview's password row to the Restore tab: that tab's record gate.
     *
     * AIDEV-NOTE: the gate names its panel instead of reading the request's panel parameter (CmsSupport.panelSlug falls
     * back to admin), because only the admin Databases resource declares the Overview. It judges the record gate
     * alone and relies on the admin panel's admission in front of it, so it is never a gate on its own: the reveal
     * asks HohenheimSecrets' gate.
     */
    @SuppressWarnings("unchecked")
    static boolean passwordReadableBy(@NonNull Row database, @NonNull AccessContext access) {
        Panel panel = PanelRegistry.getBySlug(HohenheimSlugs.ADMIN);
        if (panel == null || !(panel.entryBySlug(HohenheimSlugs.DATABASES) instanceof PanelResource<?> resource)) {
            return false;
        }
        return RecordTabGate.judge(new PanelRequest(panel, access.conduit(), access, null),
            (PanelResource<Row>) resource, database, HohenheimSlugs.Tab.RESTORE)
            instanceof RecordTabGate.Verdict.Open<Row>;
    }
}
