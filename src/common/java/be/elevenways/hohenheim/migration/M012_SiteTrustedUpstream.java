package be.elevenways.hohenheim.migration;

import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Adds {@code sites.trusted_upstream}, the operator's declaration that a tenant-owned site may
 * reach the upstream the operator configured even when it is loopback, LAN or a host path, and
 * sets it on every existing tenant-owned site that dials one.
 *
 * AIDEV-NOTE: the backfill is what keeps production serving. Until the tenant upstream gates
 * (2026-09-24) a delegated tenant could never author an upstream: the /manage site form offered
 * name, enabled and description only. So every address, TLS passthrough and static setting
 * stored on a tenant-owned site was written by an OPERATOR, and refusing it at dial time would
 * take down sites an operator deliberately pointed at a LAN backend. Ownership is read the way
 * HohenheimAccess.manageSubjectsOf reads it (a LIVE manage grant row with value true, any
 * subject), straight off zenit-auth's grant table, because a data step never reads through a
 * live model. The column is nullable with a false default so a row written by the previous
 * build reads as untrusted.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public class M012_SiteTrustedUpstream extends HohenheimMigration {

    /** The grant row's model spelling for a site, as zenit-auth stores it. */
    static final String SITE_MODEL = "hohenheim:site";

    /** The ownership capability, as HohenheimAccess.MANAGE spells it. */
    static final String MANAGE = "manage";

    /** The upstream kinds that dial something a tenant-owned site is refused, as production stored them. */
    static final Set<String> DIALING_KINDS = Set.of(
        "hohenheim:address", "hohenheim:tls_passthrough", "hohenheim:static");

    public M012_SiteTrustedUpstream() {
        super("012", "Site trusted upstream");
        // The backfill reads zenit-auth's grant table including its expires_at column.
        dependsOn("be.elevenways.zenit.auth.server.migration.M007_HardenGrantSchemas");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.alterTable("sites", table ->
            table.addColumn("trusted_upstream", ColumnType.BOOLEAN, column -> column
                .nullable(true)
                .defaultValue(false)));
        schema.data("trust the operator-authored upstream of every tenant-owned site", "1",
            M012_SiteTrustedUpstream::trustExistingTenantUpstreams);
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.alterTable("sites", table -> table.dropColumn("trusted_upstream"));
    }

    /** The data step: mark every tenant-owned site whose upstream kind dials a judged target. */
    public static void trustExistingTenantUpstreams(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            List<Integer> owned = tenantOwnedSiteIds();
            if (owned.isEmpty()) {
                return;
            }
            IntegerField id = IntegerField.builder().name("id").build();
            StringField upstreamKind = StringField.builder().name("upstream_kind").build();
            BooleanField trusted = BooleanField.builder("trusted_upstream").build();
            FrozenModel sites = new FrozenModel("sites", id, upstreamKind, trusted);
            sites.find()
                .where(id.in(owned))
                .and(upstreamKind.in(List.copyOf(DIALING_KINDS)))
                .assign(trusted, true)
                .updateAll();
        });
    }

    /** @return the ids of every site at least one live manage grant names */
    private static @NonNull List<Integer> tenantOwnedSiteIds() {
        StringField id = StringField.builder().name("id").build();
        StringField model = StringField.builder().name("model").build();
        StringField recordId = StringField.builder().name("record_id").build();
        StringField capability = StringField.builder().name("capability").build();
        BooleanField value = BooleanField.builder("value").build();
        DateTimeField expiresAt = DateTimeField.builder().name("expires_at").build();
        FrozenModel grants = new FrozenModel("auth_record_grants", id, model, recordId, capability,
            value, expiresAt);
        Instant now = Now.instant();
        Set<Integer> siteIds = new TreeSet<>();
        for (Row grant : grants.find().where(model.eq(SITE_MODEL)).and(capability.eq(MANAGE)).all()) {
            Instant expiry = grant.get(expiresAt);
            if (!Boolean.TRUE.equals(grant.get(value)) || (expiry != null && !expiry.isAfter(now))) {
                continue;
            }
            try {
                siteIds.add(Integer.valueOf(String.valueOf(grant.get(recordId)).trim()));
            } catch (NumberFormatException notASiteId) {
                // A record id no site can carry names no site: nothing to trust.
            }
        }
        return new ArrayList<>(siteIds);
    }
}
