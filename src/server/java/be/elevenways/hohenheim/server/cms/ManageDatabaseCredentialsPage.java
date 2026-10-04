package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * THE surface the {@code credentials} capability gates: a managed database's plaintext
 * connection details, and the ONLY delegated place they are readable.
 *
 * The record form cannot be that place -- {@code db_password} is a {@code .secret()}
 * column, so the framework's FormSecrets pipeline masks it on every form render -- which
 * is why the verb needs a page of its own rather than a field binding.
 *
 * AIDEV-NOTE: the gate is {@code visibleFor(record, accessContext)}, which the subpage
 * dispatch enforces as hide AND 404. A view-only teammate does not get a tab they cannot
 * open, and a guessed URL is indistinguishable from a database that does not exist.
 */
public final class ManageDatabaseCredentialsPage implements RecordTab.Rendered<Row> {

    @Override public @NonNull Identifier id() { return HohenheimIds.id("database_credentials"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("credentials").withFilter("scope", "database"); }
    @Override public @NonNull String slug() { return "credentials"; }
    @Override public @NonNull Icon icon() { return Icon.of("key"); }

    @Override
    public boolean visibleFor(@NonNull Row record, @NonNull AccessContext accessContext) {
        Integer id = record.get(DatabaseModel.ID);
        return id != null && HohenheimAccess.hasDatabaseCapability(accessContext, id,
            HohenheimAccess.CREDENTIALS);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row record) {
        Conduit conduit = request.conduit();
        int id = record.get(DatabaseModel.ID);
        ManagedDatabase.LiveStatus live = DatabaseInstances.liveStatus(id);
        String handle = DatabaseInstances.handleOf(id);
        Map<String, Object> vars = new HashMap<>(DatabaseConnectionCard.facts(record));
        // The container hostname is what an attached workload dials over the shared link
        // network; the loopback port is what a host process dials. Both are resolved live,
        // never stored, so a redeployed engine never hands out a stale address.
        vars.put("containerHost", handle == null ? "" : handle);
        vars.put("port", live.port() == null ? "" : String.valueOf(live.port()));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(
            HohenheimTemplateIds.DATABASE_CREDENTIALS, vars);
    }
}
