package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.common.orm.field.Field;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;

/**
 * THE fields of each record model that name or select a host, which every /manage twin over that model withholds
 * ({@code PanelResource.Builder.withholds}): a tenant never filters, sorts, searches or reads by host.
 *
 * AIDEV-NOTE: the tenant pages censor the host everywhere they draw it (InstanceOverview, AppDirectory's delegated
 * rows), but the /manage instance list's rule filter once spoke the schema's {@code server_id}, so a tenant
 * could select their instances by host id. {@link ManagePanel#declareEntries} refuses to boot a twin that does not
 * withhold its model's fields, so a new twin cannot forget them; a new host field is one edit here, and
 * ManagePanelJourneyTest fails while a /manage model's relation to a host is not named here.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class HostFields {

    /**
     * Per model: the host foreign key, the host a migration reserved, and a database's engine (one engine per host and
     * engine kind, so its id selects a host as surely as the host's own).
     */
    private static final Map<Identifier, List<Field<?, ?>>> BY_MODEL = Map.of(
        InstanceModel.MODEL_ID, List.of(InstanceModel.SERVER_ID, InstanceModel.MIGRATE_TARGET_ID),
        DatabaseModel.MODEL_ID, List.of(DatabaseModel.SERVER_ID, DatabaseModel.ENGINE_ID));

    private HostFields() {
    }

    /** @return the model's host-naming fields, none for a model that carries no host */
    public static @NonNull Field<?, ?>[] of(@NonNull Identifier modelId) {
        return BY_MODEL.getOrDefault(modelId, List.of()).toArray(new Field<?, ?>[0]);
    }

    /**
     * Refuses a /manage entry over a model with host fields that does not withhold every one of them.
     *
     * @throws IllegalStateException naming the entry and the field it would let a tenant query
     */
    static void requireWithheld(@NonNull List<PanelEntry> entries) {
        for (PanelEntry entry : entries) {
            if (!(entry instanceof PanelResource<?> resource) || resource.subject().modelId() == null) {
                continue;
            }
            for (Field<?, ?> field : of(resource.subject().modelId())) {
                if (!resource.withheld().contains(field.getName())) {
                    throw new IllegalStateException("Manage panel entry " + resource.id()
                        + " does not withhold host field '" + field.getName()
                        + "' (HostFields): a tenant could select its records by host");
                }
            }
        }
    }
}
