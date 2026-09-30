package be.elevenways.hohenheim.migration;

import be.elevenways.hohenheim.net.LegacyIpSpellings;
import be.elevenways.protoblast.common.platform.PlatformSeam;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.ForeignKeyAction;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The schema and stored-data changes of the 2026-09-24 review fixes: trusted upstreams, install-media fetches,
 * the observed workload kill, hashed Basic auth passwords and canonical access-rule networks.
 *
 * AIDEV-NOTE: this is ONE migration on purpose (2026-09-30). It replaced M011, M012, M015, M016 and M017,
 * which no production install (kuifje at 009, robbedoes at 010) had applied; the two test installs that
 * had were re-recorded by hand (docs/deploy-starfleet.md, 2026-09-30). The next change a production
 * install has not applied yet edits this class instead of appending a new one.
 *
 * AIDEV-NOTE: irreversible: a hash cannot give a plaintext password back, and the original network
 * spellings are not kept. Each data step is idempotent and reads through FrozenModel.
 *
 * AIDEV-NOTE: the backfill of {@code trusted_upstream} keeps production serving. Until the tenant
 * upstream gates a delegated tenant could never author an upstream (the /manage site form offered name,
 * enabled and description only), so every address, TLS passthrough and static setting stored on a
 * tenant-owned site was written by an OPERATOR. Ownership is read the way HohenheimAccess.manageSubjectsOf
 * reads it: a live manage grant row with value true, straight off zenit-auth's grant table.
 *
 * AIDEV-NOTE: zenit's IpRanges.parseLiteral used to accept a zone id (dropped), IPv4 octets with leading
 * zeros (read as DECIMAL), non-ASCII digits in IPv6 groups and a dotted quad in any IPv6 group. A rule
 * stored in such a spelling now parses to nothing, which AccessRuleTree refuses. {@link LegacyIpSpellings}
 * is the reading of production build 91191333; a value it also refused matched nothing before and is
 * left untouched.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public class M011_ReviewHardening extends HohenheimMigration {

    /** The provider type whose config holds Basic passwords, as production stored it. */
    static final String BASIC_TYPE = "hohenheim:basic";

    /** The grant row's model spelling for a site, as zenit-auth stores it. */
    static final String SITE_MODEL = "hohenheim:site";

    /** The ownership capability, as HohenheimAccess.MANAGE spells it. */
    static final String MANAGE = "manage";

    /** The upstream kinds that dial something a tenant-owned site is refused, as production stored them. */
    static final Set<String> DIALING_KINDS = Set.of(
        "hohenheim:address", "hohenheim:tls_passthrough", "hohenheim:static");

    /** The rule types that carry a network, as production stored them. */
    static final Set<String> NETWORK_TYPES = Set.of("ip_allow", "ip_deny");

    /** The data key holding the network, as production stored it. */
    static final String NETWORK_KEY = "network";

    /** The server's password hashing (argon2 lives in zenit-auth's server side), installed at class-load. */
    public static final PlatformSeam<PasswordHashing> HASHING = PlatformSeam.required(PasswordHashing.class);

    /** Turns a stored password into its stored hash. */
    public interface PasswordHashing {

        /** @return {@code stored} unchanged when it already is a hash, else its argon2 hash */
        @NonNull String hashIfPlaintext(@NonNull String stored);
    }

    public M011_ReviewHardening() {
        super("011", "Review hardening");
        // The trusted-upstream backfill reads zenit-auth's grant table including its expires_at column.
        dependsOn("be.elevenways.zenit.auth.server.migration.M007_HardenGrantSchemas");
        irreversible("plaintext Basic auth passwords are replaced by their argon2 hashes and access-rule"
            + " networks by their canonical spelling; neither original can be restored");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        // Nullable with a false default, so a row written by the previous build reads as untrusted.
        schema.alterTable("sites", table ->
            table.addColumn("trusted_upstream", ColumnType.BOOLEAN, column -> column
                .nullable(true)
                .defaultValue(false)));
        schema.createTable("install_media_fetches", table -> {
            table.id();
            table.addColumn("server_id", ColumnType.INTEGER, column -> column.nullable(false)
                .references("servers", "id").onDelete(ForeignKeyAction.CASCADE));
            table.addColumn("name", ColumnType.STRING, column -> column.nullable(false).maxLength(64));
            table.addColumn("state", ColumnType.STRING, column -> column.nullable(false).maxLength(16));
            table.addColumn("progress", ColumnType.DOUBLE, column -> column.nullable(true));
            table.addColumn("error", ColumnType.TEXT, column -> column.nullable(true));
            table.addColumn("finished_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.timestamps();
            table.addIndex("install_media_fetches_server", List.of("server_id", "created_at"));
        });
        // No default and no backfill: "no kill observed" until the next sweep asks the daemon.
        schema.alterTable("instances", table ->
            table.addColumn("workload_killed_at", ColumnType.DATETIME, column -> column.nullable(true)));
        schema.data("hash every plaintext Basic auth provider password", "1",
            M011_ReviewHardening::hashPlaintext);
        schema.data("trust the operator-authored upstream of every tenant-owned site", "1",
            M011_ReviewHardening::trustExistingTenantUpstreams);
        schema.data("rewrite access-rule networks the strict parser refuses to their canonical spelling", "1",
            M011_ReviewHardening::canonicalizeNetworks);
    }

    /** Never run: the migration is declared irreversible, and the executor refuses the DOWN first. */
    @Override
    public void down(@NonNull MigrationBuilder schema) {
        throw new UnsupportedOperationException("M011 is irreversible");
    }

    /** The data step hashing every Basic provider password still stored in plaintext, empty ones included. */
    public static void hashPlaintext(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            StringField providerType = StringField.builder().name("provider_type").build();
            SchemaField config = SchemaField.builder("config").build();
            FrozenModel providers = new FrozenModel("site_auth_providers", id, providerType, config);
            for (Row row : providers.find().where(providerType.eq(BASIC_TYPE)).all()) {
                Object stored = row.get(config);
                Object rewritten = hashedConfig(stored);
                if (rewritten != stored) {
                    row.set(config, rewritten);
                    providers.save(row);
                }
            }
        });
    }

    /** The data step marking every tenant-owned site whose upstream kind dials a judged target. */
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

    /** The data step rewriting every stored network the strict parser refuses to its canonical spelling. */
    public static void canonicalizeNetworks(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            StringField type = StringField.builder().name("type").build();
            SchemaField data = SchemaField.builder("data").build();
            StringField searchText = StringField.builder().name("search_text").build();
            FrozenModel rules = new FrozenModel("access_rules", id, type, data, searchText);
            for (Row row : rules.find().where(type.in(List.copyOf(NETWORK_TYPES))).all()) {
                if (!(row.get(data) instanceof Map<?, ?> stored)
                        || !(stored.get(NETWORK_KEY) instanceof String network)) {
                    continue;
                }
                String canonical = LegacyIpSpellings.canonicalNetwork(network);
                if (canonical == null || canonical.equals(network)) {
                    continue;
                }
                Map<String, Object> rewritten = new LinkedHashMap<>();
                stored.forEach((key, value) -> rewritten.put(String.valueOf(key), value));
                rewritten.put(NETWORK_KEY, canonical);
                row.set(data, rewritten);
                String text = row.get(searchText);
                if (text != null) {
                    row.set(searchText, text.replace(network.trim(), canonical));
                }
                rules.save(row);
            }
        });
    }

    /**
     * Rewrites both stored shapes: the canonical {@code credentials} username to password map and the
     * legacy list of {@code username} / {@code password_hash} entries.
     *
     * @return the config with every password hashed, or the SAME instance when nothing changed
     */
    private static Object hashedConfig(Object stored) {
        if (!(stored instanceof Map<?, ?> config)) {
            return stored;
        }
        Object credentials = config.get("credentials");
        Object hashed;
        if (credentials instanceof Map<?, ?> map) {
            hashed = hashedMap(map);
        } else if (credentials instanceof List<?> list) {
            hashed = hashedLegacyList(list);
        } else {
            return stored;
        }
        if (hashed == credentials) {
            return stored;
        }
        Map<Object, Object> copy = new LinkedHashMap<>(config);
        copy.put("credentials", hashed);
        return copy;
    }

    private static Object hashedMap(Map<?, ?> credentials) {
        Map<Object, Object> out = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<?, ?> entry : credentials.entrySet()) {
            Object value = entry.getValue();
            Object hashed = value == null ? null : hash(String.valueOf(value));
            changed |= hashed != null && !hashed.equals(value);
            out.put(entry.getKey(), hashed);
        }
        return changed ? out : credentials;
    }

    private static Object hashedLegacyList(List<?> credentials) {
        List<Object> out = new ArrayList<>();
        boolean changed = false;
        for (Object item : credentials) {
            if (item instanceof Map<?, ?> entry && entry.get("password_hash") != null) {
                Object value = entry.get("password_hash");
                String hashed = hash(String.valueOf(value));
                if (!hashed.equals(value)) {
                    Map<Object, Object> copy = new LinkedHashMap<>(entry);
                    copy.put("password_hash", hashed);
                    out.add(copy);
                    changed = true;
                    continue;
                }
            }
            out.add(item);
        }
        return changed ? out : credentials;
    }

    private static String hash(String stored) {
        return HASHING.require().hashIfPlaintext(stored);
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
