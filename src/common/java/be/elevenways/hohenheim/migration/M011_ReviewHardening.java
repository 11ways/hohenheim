package be.elevenways.hohenheim.migration;

import be.elevenways.hohenheim.net.Hostnames;
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
import be.elevenways.zenit.common.orm.field.LongField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.migration.ForeignKeyAction;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.common.orm.migration.MigrationCapableDatasource;
import be.elevenways.zenit.common.orm.migration.MigrationKey;
import be.elevenways.zenit.common.task.record.M006_RetireLegacyStepResults;
import be.elevenways.zenit.common.orm.migration.PrincipalColumns;
import be.elevenways.zenit.common.orm.migration.operation.AddIndexOperation;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The schema and stored-data changes of the 2026-09-24 review fixes: trusted upstreams, install-media fetches,
 * the observed workload kill, hashed Basic auth passwords and canonical access-rule networks; and module-fit's
 * instance operations: stored power, backup, snapshot, console command and app update schedule steps name the
 * operations that replaced them;
 * and a certificate's requester stored as its principal reference ({@code requested_by_kind} beside the id);
 * and a host's posture acknowledger stored the same way ({@code acknowledged_by_kind} beside the id);
 * and every stored host wildcard respelled into zenit's HostPattern grammar;
 * and the provenance mark of every operator-trustable target, set on the rows stored before it;
 * and the lock version of every game-domain mapping, whose writes became operations;
 * and the access-rule tree on core's TreeBehaviour: nullable positions, one tree index and dense sibling runs.
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
 * AIDEV-NOTE: Hohenheim's glob grammar read a leading {@code *.} as one or more labels and any other star run as
 * any characters inside a label; HostPattern, which the dispatcher now matches with, spells those {@code **.} and
 * one {@code *}. The translation keeps every stored route's hosts and its tie order, and rewrites the claim key
 * beside it, which opens with the hostname (RouteClaims.keyOf); what it cannot carry exactly fails the migration by
 * name. A regex row is not a host pattern and is left alone. {@link Hostnames#PATTERNS} spells the rewrite: the
 * host-wildcards guard refuses the literal outside HostPattern.
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

    /** The legacy schedule actions, as production stored them. */
    static final String POWER_ACTION = "hohenheim:power";
    static final String BACKUP_ACTION = "hohenheim:backup";
    static final String SNAPSHOT_ACTION = "hohenheim:snapshot";
    static final String CONSOLE_ACTION = "hohenheim:console_command";
    static final String APP_UPDATE_ACTION = "hohenheim:app_update";

    /** The operations replacing the legacy actions that take no power-style choice, by legacy id. */
    static final String CONSOLE_OPERATION = "hohenheim:console_command_instance";
    static final String APP_UPDATE_OPERATION = "hohenheim:app_update_instance";

    /** A power step's stored operation, by the operation that replaces it; a missing or blank one meant restart. */
    static final Map<String, String> POWER_OPERATIONS = Map.of(
        "start", "hohenheim:start_instance",
        "stop", "hohenheim:stop_instance",
        "restart", "hohenheim:restart_instance");

    /** A step-run's recorded legacy id, by the operation that replaced the same verb; power names no single one. */
    static final Map<String, String> RECORDED_OPERATIONS = Map.of(
        BACKUP_ACTION, "hohenheim:backup_instance",
        SNAPSHOT_ACTION, "hohenheim:snapshot_instance",
        CONSOLE_ACTION, CONSOLE_OPERATION,
        APP_UPDATE_ACTION, APP_UPDATE_OPERATION);

    /** The match type of a regex route, as production stored it; every other row holds a host pattern. */
    static final String REGEX_MATCH = "regex";

    /** The match type of a wildcard route, as production stored it. */
    static final String WILDCARD_MATCH = "wildcard";

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
        // The schedule-step rewrite moves payloads into the stored-input column zenit's M004 adds.
        dependsOn("be.elevenways.zenit.common.task.record.M004_AddRecordScheduleStepInput");
        // The step-run respelling reads the rows zenit's M005 converts from each legacy run's step_results.
        dependsOn("be.elevenways.zenit.common.task.record.M005_ConvertLegacyStepResults");
        irreversible("plaintext Basic auth passwords are replaced by their argon2 hashes and access-rule"
            + " networks by their canonical spelling; neither original can be restored");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.normalizeSqliteInstants(legacyInstantScopes());
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
        // A stored domain keeps the force_ssl it has: the latch that forces HTTPS once a certificate works is armed for
        // rows written from here on only.
        schema.alterTable("site_domains", table ->
            table.addColumn("force_ssl_auto", ColumnType.BOOLEAN, column -> column
                .nullable(true)
                .defaultValue(false)));
        // No default and no backfill: "no kill observed" until the next sweep asks the daemon.
        schema.alterTable("instances", table ->
            table.addColumn("workload_killed_at", ColumnType.DATETIME, column -> column.nullable(true)));
        // Every requester id stored before kinds existed was an account's: the stamp step reads it so.
        schema.alterTable("certificates", table -> PrincipalColumns.addKindColumn(table, "requested_by_kind"));
        // Every decimal acknowledger stored before kinds existed was an account's; any other token stays kindless.
        schema.alterTable("servers", table -> PrincipalColumns.addKindColumn(table, "acknowledged_by_kind"));
        schema.data("hash every plaintext Basic auth provider password", "1",
            M011_ReviewHardening::hashPlaintext);
        schema.data("trust the operator-authored upstream of every tenant-owned site", "1",
            M011_ReviewHardening::trustExistingTenantUpstreams);
        schema.data("rewrite access-rule networks the strict parser refuses to their canonical spelling", "1",
            M011_ReviewHardening::canonicalizeNetworks);
        schema.data("name the instance operations on stored power, backup, snapshot, console and app update steps",
            "2", M011_ReviewHardening::renameInstanceScheduleSteps);
        schema.data("store every certificate requester with its kind", "1", PrincipalColumns.stampAccountKinds(
            "certificates", () -> IntegerField.builder().name("id").build(),
            () -> IntegerField.builder().name("requested_by_user_id").build(), "requested_by_kind", true));
        schema.data("store every posture acknowledger with its kind", "1", PrincipalColumns.stampAccountKinds(
            "servers", () -> IntegerField.builder().name("id").build(),
            () -> StringField.builder().name("acknowledged_by").build(), "acknowledged_by_kind", true));
        schema.data("translate every stored legacy host wildcard into the HostPattern grammar", "1",
            M011_ReviewHardening::respellHostWildcards);
        // A port claim's controller fence was written null by every caller and read by none.
        schema.alterTable("port_allocations", table -> table.dropColumn("controller_fence"));
        schema.data("start every instance's claim fence over in its own claim lease's generations", "1",
            M011_ReviewHardening::clearInstanceClaimFences);
        // Who set each operator-trustable target: false for a row the next build writes without the system tier.
        for (String table : TARGET_TABLES) {
            schema.alterTable(table, t -> t.addColumn("target_trusted", ColumnType.BOOLEAN, column -> column
                .nullable(true)
                .defaultValue(false)));
        }
        schema.data("mark every stored site upstream, provider base URL and instance source as operator-set", "1",
            M011_ReviewHardening::trustExistingTargets);
        // A game-domain mapping is written through operations now, and an update is reviewed against its version.
        schema.alterTable("game_domains", table -> table.version());
        schema.data("start every stored game-domain mapping's lock version at 0", "1",
            M011_ReviewHardening::startGameDomainVersions);
        schema.alterTable("instance_volumes", table -> table.version());
        schema.data("start every stored volume declaration's lock version at 0", "1", datasource -> {
            Db.run(datasource, () -> {
                IntegerField id = IntegerField.builder().name("id").build();
                IntegerField version = IntegerField.builder().name("version").build();
                new FrozenModel("instance_volumes", id, version).find()
                    .where(version.isNull()).assign(version, 0).updateAll();
            });
        });
        // The access-rule tree is core's TreeBehaviour now: a position is null until the tree places the rule (no 0
        // default, which would place every new rule first), one index serves the sibling reads, and every stored
        // sibling run is renumbered dense from 0 in its stored order.
        schema.alterTable("access_rules", table -> {
            table.changeColumn("sort", ColumnType.INTEGER, column -> column.nullable(true));
            table.addIndex("access_rules_tree_index", List.of("access_list_id", "parent_id", "sort"),
                AddIndexOperation::overUpdatedColumns);
        });
        schema.data("renumber every access-rule sibling run dense from 0 in its stored order", "1",
            M011_ReviewHardening::densifyRulePositions);
    }

    /** The M010 control-plane shape, frozen independently of the consolidated or removed migration classes. */
    static FrozenModel[] legacyInstantScopes() {
        return new FrozenModel[] {
            instantScope("sites", "created_at", "updated_at", "deleted_at"),
            instantScope("site_domains", "created_at", "updated_at", "generated_at"),
            instantScope("certificates", "expires_on", "created_at", "updated_at", "issued_on", "next_attempt_at", "expiry_notified_at"),
            instantScope("access_lists", "created_at", "updated_at"),
            instantScope("protected_paths", "created_at", "updated_at"),
            instantScope("access_rules", "created_at", "updated_at"),
            instantScope("system_users", "last_seen_at", "created_at", "updated_at"),
            instantScope("servers", "created_at", "updated_at", "probed_at", "last_seen_at", "host_key_pinned_at",
                "incus_server_cert_pinned_at", "quarantined_at", "acknowledged_at", "volume_probed_at"),
            instantScope("managed_databases", "created_at", "updated_at"),
            instantScope("notification_channels", "created_at", "updated_at"),
            instantScope("site_auth_providers", "created_at", "updated_at"),
            instantScope("site_sessions", "created_at", "expires_at"),
            instantScope("dns_zones", "created_at", "updated_at", "last_checked_at", "last_transfer_at", "delegation_checked_at"),
            instantScope("dns_records", "created_at", "updated_at", "generated_at"),
            instantScope("dns_peers", "created_at", "updated_at"),
            instantScope("dns_zone_peers", "created_at", "updated_at", "probed_at", "behind_since", "stale_alerted_at", "last_axfr_at", "last_notify_at"),
            instantScope("bans", "expires_at", "lifted_at", "created_at", "updated_at"),
            instantScope("spamservice_installations", "created_at", "updated_at"),
            instantScope("stacks", "created_at", "updated_at"),
            instantScope("stack_services", "created_at", "updated_at"),
            instantScope("stack_services_mounts", "created_at", "updated_at"),
            instantScope("stack_services_ports", "created_at", "updated_at"),
            instantScope("stack_services_depends_on", "created_at", "updated_at"),
            instantScope("stack_files", "created_at", "updated_at"),
            instantScope("stack_deployments", "started_at", "finished_at", "created_at", "updated_at"),
            instantScope("reconcile_findings", "created_at", "updated_at"),
            instantScope("port_allocations", "created_at", "updated_at"),
            instantScope("released_route_claims", "released_at"),
            instantScope("instance_quotas", "created_at", "updated_at"),
            instantScope("backup_targets", "created_at", "updated_at"),
            instantScope("instance_templates", "approved_at", "imported_at", "created_at", "updated_at"),
            instantScope("runtime_images", "created_at", "updated_at"),
            instantScope("instance_template_volumes", "created_at", "updated_at"),
            instantScope("instances", "created_at", "updated_at", "deleted_at", "generated_at", "disk_observed_at", "status_observed_at"),
            instantScope("instance_volumes", "observed_at", "created_at", "updated_at"),
            instantScope("instance_snapshots", "created_at", "updated_at"),
            instantScope("instance_backups", "created_at", "updated_at"),
            instantScope("instance_template_variables", "created_at", "updated_at"),
            instantScope("instance_template_files", "created_at", "updated_at"),
            instantScope("instance_variables", "created_at", "updated_at"),
            instantScope("instance_files", "created_at", "updated_at", "generated_at"),
            instantScope("game_domains", "created_at", "updated_at"),
            instantScope("build_operations", "started_at", "finished_at", "created_at", "updated_at"),
            instantScope("release_operations", "started_at", "finished_at", "created_at", "updated_at"),
            instantScope("projects", "created_at", "updated_at"),
            instantScope("environments", "created_at", "updated_at"),
            instantScope("git_providers", "created_at", "updated_at"),
            instantScope("webhook_deliveries", "received_at", "created_at", "updated_at"),
            instantScope("preview_deployments", "expires_at", "deleted_at", "created_at", "updated_at"),
            instantScope("instance_devices", "created_at", "updated_at"),
            instantScope("controller_identity", "created_at", "updated_at"),
            instantScope("instance_logs", "saved_at", "created_at", "updated_at"),
            instantScope("instance_databases", "created_at", "updated_at"),
            instantScope("dns_dyndns_credentials", "created_at", "updated_at"),
            instantScope("instance_template_databases", "created_at", "updated_at"),
            instantScope("database_engines", "created_at", "updated_at"),
            instantScope("artifact_operations", "finished_at", "created_at", "updated_at"),
            instantScope("artifact_sources", "created_at", "updated_at")
        };
    }

    private static FrozenModel instantScope(String table, String... columns) {
        Field<?, ?> key = table.equals("site_sessions") ? StringField.builder("id").build() : IntegerField.builder("id").build();
        Field<?, ?>[] dates = Arrays.stream(columns).map(name -> DateTimeField.builder(name).build()).toArray(Field[]::new);
        return new FrozenModel(table, key, dates);
    }

    /**
     * The data step starting every stored game-domain mapping's lock version at 0: a null version saves unguarded, so
     * an edit reviewed on a pre-migration row would never refuse as stale.
     */
    public static void startGameDomainVersions(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            IntegerField version = IntegerField.builder().name("version").build();
            new FrozenModel("game_domains", id, version).find()
                .where(version.isNull())
                .assign(version, 0)
                .updateAll();
        });
    }

    /** The tables whose rows carry an operator-trustable target, as production names them. */
    static final List<String> TARGET_TABLES = List.of("sites", "git_providers", "instances");

    /**
     * The data step marking every stored target as set by the operator: before the rule existed a site's upstream, a
     * provider's base URL and an instance's source could be authored by the operator alone among those whose
     * targets are dialled with any-address reach. Trashed rows included, so one restored later keeps its reach.
     */
    public static void trustExistingTargets(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            for (String table : TARGET_TABLES) {
                IntegerField id = IntegerField.builder().name("id").build();
                BooleanField trusted = BooleanField.builder("target_trusted").build();
                new FrozenModel(table, id, trusted).find()
                    .where(id.isNotNull())
                    .assign(trusted, true)
                    .updateAll();
            }
        });
    }

    /**
     * The data step clearing {@code instances.claim_fence}: the fences stored so far are HOST lease generations, and
     * the record's claim (InstanceOperationLock over core ClaimedRows) now stamps its own lease's, which share no
     * sequence with them. A stale host-domain value above the record's first claim would refuse that claim forever;
     * cleared, the first claim stamps the record's own generation. Trashed rows included, so one restored later
     * starts clean.
     */
    public static void clearInstanceClaimFences(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            LongField claimFence = LongField.builder().name("claim_fence").build();
            new FrozenModel("instances", id, claimFence).find()
                .where(claimFence.isNotNull())
                .assign(claimFence, (Object) null)
                .updateAll();
        });
    }

    /** Never run: the migration is declared irreversible, and the executor refuses the DOWN first. */
    @Override
    public void down(@NonNull MigrationBuilder schema) {
        throw new UnsupportedOperationException("M011 is irreversible");
    }

    /**
     * The data step renumbering each access list's sibling runs (one per parent) 0..n-1, ordered by the stored
     * position and then the key, the order every reader used; idempotent, a dense run is left as stored.
     */
    public static void densifyRulePositions(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            IntegerField listId = IntegerField.builder().name("access_list_id").build();
            IntegerField parentId = IntegerField.builder().name("parent_id").build();
            IntegerField sort = IntegerField.builder().name("sort").build();
            FrozenModel rules = new FrozenModel("access_rules", id, listId, parentId, sort);
            Map<List<Object>, Integer> next = new HashMap<>();
            for (Row row : rules.find().orderBy(listId, SortOrder.ASC).orderBy(sort, SortOrder.ASC)
                    .orderBy(id, SortOrder.ASC).all()) {
                List<Object> run = Arrays.asList(row.get(listId), row.get(parentId));
                int position = next.merge(run, 1, Integer::sum) - 1;
                if (!Integer.valueOf(position).equals(row.get(sort))) {
                    rules.find().where(id.eq(row.get(id))).assign(sort, position).updateAll();
                }
            }
        });
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
     * The data step translating every routed and every released hostname of the legacy glob grammar into the
     * HostPattern spelling of the same hosts, claim keys included.
     *
     * AIDEV-NOTE: nothing is written until every row is known to translate exactly, and one that cannot fails the
     * migration naming every such row: a pattern the grammar refuses (the dispatcher would drop it and take the site
     * offline), and both rows of an equally specific, overlapping pair of routes whose old tie order the translation
     * would flip (a collapsed star run changes the tie key, review 5 D02/D03), since live routing consults the first
     * match. Silently dropping, widening or reordering a route is never an outcome. The operator corrects or deletes
     * the rows and migrates again; the runtime log in RouteTableBuilder stays the second line of defence.
     *
     * @throws IllegalStateException listing table, id, site, pattern and reason of every refused row
     */
    public static void respellHostWildcards(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            Hostnames.PatternGrammar grammar = Hostnames.PATTERNS.require();
            PatternTable domains = new PatternTable("site_domains", "live_route_key", "site_id");
            PatternTable released = new PatternTable("released_route_claims", "claim_key", "former_site_id");
            List<String> refused = new ArrayList<>();
            List<Translation> routed = domains.translate(grammar, refused);
            List<Translation> ledger = released.translate(grammar, refused);
            refuseFlippedTies(grammar, routed, refused);
            if (!refused.isEmpty()) {
                throw new IllegalStateException("M011 cannot carry " + refused.size() + " stored host pattern(s)"
                    + " into the HostPattern grammar; correct or delete them, then migrate again:\n  "
                    + String.join("\n  ", refused));
            }
            domains.write(routed);
            released.write(ledger);
        });
    }

    /** One stored wildcard and the pattern naming the same hosts. */
    private record Translation(@NonNull PatternTable table, @NonNull Row row, @NonNull String legacy,
                               @NonNull String translated) {

        @NonNull String describe() {
            return this.table.describe(this.row, this.legacy);
        }
    }

    /**
     * Refuse both rows of every pair of routes that are equally specific and share a host, when their old tie keys and
     * their translated ones order them differently.
     */
    private static void refuseFlippedTies(Hostnames.@NonNull PatternGrammar grammar,
                                          @NonNull List<Translation> routed, @NonNull List<String> refused) {
        Set<Translation> flipped = new LinkedHashSet<>();
        for (int i = 0; i < routed.size(); i++) {
            for (int j = i + 1; j < routed.size(); j++) {
                Translation first = routed.get(i);
                Translation second = routed.get(j);
                if (grammar.specificity(first.translated()) != grammar.specificity(second.translated())
                        || !grammar.overlaps(first.translated(), second.translated())) {
                    continue;
                }
                int before = Integer.signum(grammar.legacyTieKey(first.legacy())
                    .compareTo(grammar.legacyTieKey(second.legacy())));
                int after = Integer.signum(grammar.tieKey(first.translated())
                    .compareTo(grammar.tieKey(second.translated())));
                if (before != after) {
                    flipped.add(first);
                    flipped.add(second);
                }
            }
        }
        for (Translation translation : flipped) {
            refused.add(translation.describe() + " -- its route order against an equally specific wildcard that"
                + " matches the same hosts would change; respell one of them");
        }
    }

    /** One table of stored host patterns: its claim key and the site column naming who holds the row. */
    private static final class PatternTable {

        // A field belongs to one schema, so every table builds its own.
        private final IntegerField id = IntegerField.builder().name("id").build();
        private final StringField hostname = StringField.builder().name("hostname").build();
        private final StringField matchType = StringField.builder().name("match_type").build();
        private final String name;
        private final StringField key;
        private final IntegerField site;
        private final FrozenModel model;

        PatternTable(@NonNull String name, @NonNull String keyName, @NonNull String siteName) {
            this.name = name;
            this.key = StringField.builder().name(keyName).build();
            this.site = IntegerField.builder().name(siteName).build();
            this.model = new FrozenModel(name, this.id, this.hostname, this.matchType, this.key, this.site);
        }

        @NonNull String describe(@NonNull Row row, @NonNull String legacy) {
            return this.name + " #" + row.get(this.id) + " (site " + row.get(this.site) + "): '" + legacy + "'";
        }

        /**
         * @param refused collects one line per wildcard-tier row the grammar cannot carry
         * @return every wildcard-tier row with its translation, not yet written
         */
        @NonNull List<Translation> translate(Hostnames.@NonNull PatternGrammar grammar,
                                             @NonNull List<String> refused) {
            List<Translation> translations = new ArrayList<>();
            for (Row row : this.model.find().all()) {
                String stored = row.get(this.hostname);
                String matchType = row.get(this.matchType);
                // A regex is no glob, and an exact host spells no wildcard to translate.
                if (stored == null || REGEX_MATCH.equals(matchType)
                        || !(WILDCARD_MATCH.equals(matchType) || Hostnames.hasGlobCharacters(stored))) {
                    continue;
                }
                try {
                    translations.add(new Translation(this, row, stored, grammar.fromLegacyGlob(stored)));
                } catch (IllegalArgumentException outsideTheGrammar) {
                    refused.add(this.describe(row, stored) + " -- " + outsideTheGrammar.getMessage());
                }
            }
            return translations;
        }

        /** Writes every changed translation, its claim key's hostname head with it. */
        void write(@NonNull List<Translation> translations) {
            for (Translation translation : translations) {
                if (translation.translated().equals(translation.legacy())) {
                    continue;
                }
                Row row = translation.row();
                row.set(this.hostname, translation.translated());
                String claim = row.get(this.key);
                if (claim != null && claim.startsWith(translation.legacy())) {
                    row.set(this.key, translation.translated() + claim.substring(translation.legacy().length()));
                }
                this.model.save(row);
            }
        }
    }

    /**
     * The data step naming the instance operations on stored schedule steps: a power step by its stored
     * {@code operation} (missing or blank is restart, the old default), a backup and a snapshot step by the operation
     * that replaced them, a snapshot's payload moved into the stored input. Open step-runs of a rewritten step follow.
     *
     * AIDEV-NOTE: a data step, never a renameType in the stored-id holder: the target of a power step depends on its
     * payload, and the holder's chains are global, so {@code hohenheim:backup -> hohenheim:backup_instance} would also
     * carry the activity value {@code backup} on to an operation id (stage 2 contract 6.10).
     *
     * @throws IllegalStateException naming the step when a power step stores an operation no power operation replaces
     */
    public static void renameInstanceScheduleSteps(@NonNull Datasource datasource) {
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            StringField action = StringField.builder().name("action").build();
            SchemaField payload = SchemaField.builder("payload").build();
            SchemaField input = SchemaField.builder("input").build();
            FrozenModel steps = new FrozenModel("zenit_record_schedule_steps", id, action, payload, input);
            for (Row step : steps.find()
                    .where(action.in(List.of(POWER_ACTION, BACKUP_ACTION, SNAPSHOT_ACTION, CONSOLE_ACTION,
                        APP_UPDATE_ACTION))).all()) {
                String stored = step.get(action);
                Map<String, Object> values = payloadOf(step.get(payload));
                Map<String, Object> moved = null;
                String operation;
                if (POWER_ACTION.equals(stored)) {
                    Object raw = values.get("operation");
                    String op = raw == null || String.valueOf(raw).isBlank() ? "restart" : String.valueOf(raw).trim();
                    operation = POWER_OPERATIONS.get(op);
                    if (operation == null) {
                        throw new IllegalStateException("Schedule step " + step.get(id) + " stores the power operation '"
                            + op + "', which no instance operation replaces");
                    }
                } else if (BACKUP_ACTION.equals(stored)) {
                    operation = "hohenheim:backup_instance";
                } else if (APP_UPDATE_ACTION.equals(stored)) {
                    operation = APP_UPDATE_OPERATION;
                } else if (CONSOLE_ACTION.equals(stored)) {
                    operation = CONSOLE_OPERATION;
                    Object command = values.get("command");
                    if (command != null) {
                        moved = new LinkedHashMap<>();
                        moved.put("command", String.valueOf(command));
                    }
                } else {
                    operation = "hohenheim:snapshot_instance";
                    Object note = values.get("note");
                    if (note != null && !String.valueOf(note).isBlank()) {
                        moved = new LinkedHashMap<>();
                        moved.put("note", String.valueOf(note));
                    }
                }
                step.set(action, operation);
                step.set(payload, null);
                step.set(input, moved);
                steps.save(step);
            }
            // Each step-run keeps what IT recorded, never its step's current operation: the step may have been
            // edited since. A recorded backup or snapshot is respelled to the operation that replaced that same
            // verb; a recorded power run keeps hohenheim:power, since its own record never named start, stop or
            // restart (a still-open one the sweep refuses as a recorded operation gone). Inputs are never touched.
            IntegerField runId = IntegerField.builder().name("id").build();
            StringField operation = StringField.builder().name("operation").build();
            FrozenModel stepRuns = new FrozenModel("zenit_record_schedule_step_runs", runId, operation);
            for (Map.Entry<String, String> respelled : RECORDED_OPERATIONS.entrySet()) {
                stepRuns.find().where(operation.eq(respelled.getKey()))
                    .assign(operation, respelled.getValue())
                    .updateAll();
            }
            respellRetainedRecordedOperationMaps(datasource);
        });
    }

    private static void respellRetainedRecordedOperationMaps(Datasource datasource) {
        if (((MigrationCapableDatasource) datasource).getMigrationRecord(
                MigrationKey.of(new M006_RetireLegacyStepResults())) != null) return;
        IntegerField id = IntegerField.builder("id").build();
        SchemaField results = SchemaField.builder("step_results").build();
        FrozenModel runs = new FrozenModel("zenit_record_schedule_runs", id, results);
        for (Row run : runs.find().where(results.isNotNull()).all()) {
            Object original = run.get(results);
            if (!(original instanceof Map<?, ?> map) || !(map.get("steps") instanceof List<?> entries)) continue;
            List<Object> moved = new ArrayList<>(entries.size());
            boolean changed = false;
            for (Object member : entries) {
                if (member instanceof Map<?, ?> entry && entry.get("action") instanceof String recorded
                        && RECORDED_OPERATIONS.containsKey(recorded)) {
                    Map<Object, Object> rewritten = new LinkedHashMap<>(entry);
                    rewritten.put("action", RECORDED_OPERATIONS.get(recorded));
                    moved.add(rewritten);
                    changed = true;
                } else {
                    moved.add(member);
                }
            }
            if (!changed) continue;
            Map<Object, Object> rewritten = new LinkedHashMap<>(map);
            rewritten.put("steps", moved);
            long updated = runs.find().where(id.eq(run.get(id))).and(results.eq(original))
                .assign(results, rewritten).updateAll();
            if (updated != 1) throw new IllegalStateException("Record schedule run " + run.get(id)
                + " changed while its retained operation names were being respelled");
        }
    }

    /** @return a stored payload as a map, empty when it holds none */
    private static Map<String, Object> payloadOf(Object stored) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (stored instanceof Map<?, ?> map) {
            map.forEach((key, value) -> values.put(String.valueOf(key), value));
        }
        return values;
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
