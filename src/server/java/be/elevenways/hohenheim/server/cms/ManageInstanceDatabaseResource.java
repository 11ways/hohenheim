package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The /manage view over instance-database attachments: the same editor, narrowed to the
 * instances the principal manages. THE tenant path for the Pterodactyl shape -- allocate a
 * database, attach it to your own server, receive its credentials as environment variables.
 *
 * AIDEV-NOTE: the base resource's accessFunction does not scope by owner, which is correct
 * in an admin-gated panel and a cross-tenant leak in a delegated one (the
 * ManageInstanceDeviceResource lesson, third occurrence). Every WRITE is re-decided by
 * TenantWrites' two-sided rule, but a READ would have listed which databases every other
 * tenant's workloads are wired to.
 */
public final class ManageInstanceDatabaseResource extends InstanceDatabaseResource {

    @Override
    public @NonNull Identifier id() {
        return Identifier.of("hohenheim", "manage_instance_database");
    }

    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return TenantScopes.INSTANCE_DATABASES.accessFunction();
    }

    /**
     * The contributed pages only (the generic access matrix, which gates itself per record).
     * Deliberately NOT frameworkSubpages(): the admin activity/revision history stays off the
     * delegated surface, and dropping the page here also 404s its routes.
     */
    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        return this.contributedSubpages();
    }
}
