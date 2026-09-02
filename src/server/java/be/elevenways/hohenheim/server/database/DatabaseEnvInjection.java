package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Derives the environment variables a WORKLOAD receives for each attached managed
 * database ({@link #envForInstance}), resolved at deploy time so the published port and the
 * credentials are always current (nothing is ever baked into stored settings).
 *
 * AIDEV-NOTE: there used to be a second owner here, the SITE, over a second link table.
 * Phase-0 brief 7 deleted both: a site no longer runs anything, and the record that does --
 * the application instance -- already had {@code instance_databases}. One owner means the
 * two lanes can no longer disagree about what a prefix normalizes to or when a database
 * counts as unresolved. A database that is not active-and-running
 * contributes NO variables: the skip is logged as {@code hohenheim.db_injection.unresolved}
 * and the dashboard attention panel surfaces it.
 *
 * @author  Jelle De Loecker
 * @since   0.2.0
 */
public final class DatabaseEnvInjection {

    /** Resolves the live container status for a database row (injectable for tests). */
    public interface LiveResolver {
        ManagedDatabase.@NonNull LiveStatus resolve(@NonNull Row databaseRow);
    }

    /**
     * The address shape the consuming runtime can actually reach: a caller on the host
     * dials the database's published loopback port; a container sits on its own private
     * network where 127.0.0.1 is ITSELF, so it gets the database's container hostname on
     * the shared link network and the engine's native port instead
     * ({@code InstanceDatabaseNetworks} joins the pair before the container starts).
     */
    public enum Style {
        PUBLISHED_LOOPBACK,
        CONTAINER_NETWORK
    }

    private DatabaseEnvInjection() {
    }

    /**
     * Which workload tier a set of links belongs to: only the LOOKUP and the log's owner
     * word differ, so the derivation below stays one code path.
     */
    private enum Owner {
        INSTANCE("instance_id");

        private final @NonNull String logKey;

        Owner(@NonNull String logKey) {
            this.logKey = logKey;
        }
    }

    /**
     * Injected environment for an INSTANCE: resolved at the same moment it is used and
     * never stored. Always the CONTAINER_NETWORK style -- an instance workload's 127.0.0.1
     * is itself, and {@code InstanceDatabaseNetworks} joins it to each attached database's
     * link network between container create and start.
     */
    public static @NonNull Map<String, String> envForInstance(int instanceId,
                                                              @Nullable LiveResolver resolver) {
        return envFor(Owner.INSTANCE, instanceId, resolver, Style.CONTAINER_NETWORK);
    }

    /**
     * The derivation: read the owner's links oldest-first, resolve each
     * database live, and emit its variable family. Fail-soft by design -- a spawn must
     * never die on injection plumbing, so any resolution error degrades to "no variables"
     * with a log.
     */
    private static @NonNull Map<String, String> envFor(@NonNull Owner owner, int ownerId,
                                                       @Nullable LiveResolver resolver,
                                                       @NonNull Style style) {
        try {
            DatabaseModel databases = Models.get(DatabaseModel.class);
            if (databases == null) {
                return Map.of();
            }
            List<Row> linkRows = linksOf(owner, ownerId);
            if (linkRows.isEmpty()) {
                return Map.of();
            }
            LiveResolver live = resolver != null ? resolver : serviceResolver();
            Map<String, String> env = new LinkedHashMap<>();
            // DATABASE_URL belongs to the FIRST link, resolved or not: an unavailable
            // primary must never silently hand the bare name to a different database.
            boolean primary = true;
            for (Row link : linkRows) {
                Integer databaseId = databaseIdOf(owner, link);
                Row database = databaseId == null ? null
                    : databases.find().where(DatabaseModel.ID.eq(databaseId)).first();
                appendLink(env, owner, ownerId, databaseId, prefixOf(owner, link),
                    database, live, primary, style);
                primary = false;
            }
            return env;
        } catch (Exception e) {
            Blast.log("DB-INJECT: env resolution failed for", owner.logKey, ownerId,
                "-", e.getMessage());
            return Map.of();
        }
    }

    private static @NonNull List<Row> linksOf(@NonNull Owner owner, int ownerId) {
        InstanceDatabaseModel links = Models.get(InstanceDatabaseModel.class);
        return links == null ? List.of() : links.findByInstanceId(ownerId);
    }

    private static @Nullable Integer databaseIdOf(@NonNull Owner owner, @NonNull Row link) {
        return link.get(InstanceDatabaseModel.DATABASE_ID);
    }

    private static @Nullable String prefixOf(@NonNull Owner owner, @NonNull Row link) {
        return link.get(InstanceDatabaseModel.ENV_PREFIX);
    }

    private static void appendLink(Map<String, String> env, Owner owner, int ownerId,
                                   @Nullable Integer databaseId, @Nullable String rawPrefix,
                                   @Nullable Row database, LiveResolver live, boolean primary,
                                   Style style) {
        if (database == null) {
            unresolved(owner, ownerId, databaseId, null, "record_missing");
            return;
        }
        String name = database.get(DatabaseModel.NAME);
        if (!DatabaseModel.STATUS_ACTIVE.equals(database.get(DatabaseModel.STATUS))) {
            unresolved(owner, ownerId, databaseId, name, "not_active");
            return;
        }
        ManagedDatabase.LiveStatus status = live.resolve(database);
        // The container-network style needs no published port (it dials the engine's own
        // port over the link network), but the engine must still actually be running:
        // credentials for a dead database are the silent-success shape, not a favour.
        if (!status.running()
                || (style == Style.PUBLISHED_LOOPBACK && status.port() == null)) {
            unresolved(owner, ownerId, databaseId, name, "container_not_running");
            return;
        }
        ManagedDatabase.Engine engine;
        try {
            engine = ManagedDatabase.engineOf(database);
        } catch (IllegalArgumentException e) {
            unresolved(owner, ownerId, databaseId, name, "unknown_engine");
            return;
        }
        String host = "127.0.0.1";
        if (style == Style.CONTAINER_NETWORK) {
            // The container SERVING the database (its own, or its shared engine's),
            // resolved from the owned instance -- never guessed from the record name. A
            // database nothing serves has no reachable address at all, which is a skip,
            // not a hostname that resolves to nothing inside the consumer's network.
            host = DatabaseInstances.handleOf(databaseId);
            if (host == null) {
                unresolved(owner, ownerId, databaseId, name, "no_engine_instance");
                return;
            }
        }
        int port = style == Style.CONTAINER_NETWORK ? engine.port() : status.port();
        String prefix = normalizedPrefix(rawPrefix);
        env.putAll(vars(engine, host, port, database.get(DatabaseModel.DB_USER),
            database.get(DatabaseModel.DB_PASSWORD), database.get(DatabaseModel.DB_NAME),
            authDatabaseOf(database), prefix, primary));
    }

    /**
     * The database a Mongo user authenticates against: a dedicated record's user is the
     * engine root created in {@code admin}; a shared record's user was created ON its own
     * logical database, so that database is where the credential lives.
     */
    public static @NonNull String authDatabaseOf(@NonNull Row database) {
        if (DatabaseModel.isShared(database)) {
            String name = database.get(DatabaseModel.DB_NAME);
            return name != null ? name : "";
        }
        return MONGO_ROOT_AUTH_DATABASE;
    }

    /** Where a dedicated Mongo record's root user lives. */
    public static final String MONGO_ROOT_AUTH_DATABASE = "admin";

    private static void unresolved(Owner owner, int ownerId, @Nullable Integer databaseId,
                                   @Nullable String name, String reason) {
        Blast.slog("hohenheim.db_injection.unresolved", Map.of(
            owner.logKey, String.valueOf(ownerId),
            "database_id", databaseId != null ? String.valueOf(databaseId) : "(none)",
            "database", name != null ? name : "(unknown)",
            "reason", reason));
    }

    /** Uppercased prefix, {@link InstanceDatabaseModel#DEFAULT_PREFIX} when blank. */
    public static @NonNull String normalizedPrefix(@Nullable String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return InstanceDatabaseModel.DEFAULT_PREFIX;
        }
        return prefix.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * The variable family for one resolved link: {@code {PREFIX}_HOST/PORT/USER/PASSWORD/NAME/URL},
     * plus the bare {@code DATABASE_URL} when this is the owner's primary link.
     */
    public static @NonNull Map<String, String> vars(ManagedDatabase.@NonNull Engine engine,
                                                    @NonNull String host, int port,
                                                    @Nullable String user, @Nullable String password,
                                                    @Nullable String database,
                                                    @NonNull String prefix, boolean primary) {
        return vars(engine, host, port, user, password, database, MONGO_ROOT_AUTH_DATABASE,
            prefix, primary);
    }

    /** {@link #vars} naming the Mongo authentication database (see {@link #authDatabaseOf}). */
    public static @NonNull Map<String, String> vars(ManagedDatabase.@NonNull Engine engine,
                                                    @NonNull String host, int port,
                                                    @Nullable String user, @Nullable String password,
                                                    @Nullable String database,
                                                    @NonNull String authDatabase,
                                                    @NonNull String prefix, boolean primary) {
        Map<String, String> env = new LinkedHashMap<>();
        String url = connectionUrl(engine, host, port, user, password, database, authDatabase);
        env.put(prefix + "_HOST", host);
        env.put(prefix + "_PORT", String.valueOf(port));
        env.put(prefix + "_USER", user != null ? user : "");
        env.put(prefix + "_PASSWORD", password != null ? password : "");
        env.put(prefix + "_NAME", database != null ? database : "");
        env.put(prefix + "_URL", url);
        if (primary) {
            env.put("DATABASE_URL", url);
        }
        return env;
    }

    /** Engine-appropriate connection URL with URL-encoded credentials. */
    public static @NonNull String connectionUrl(ManagedDatabase.@NonNull Engine engine,
                                                @NonNull String host, int port,
                                                @Nullable String user, @Nullable String password,
                                                @Nullable String database) {
        return connectionUrl(engine, host, port, user, password, database,
            MONGO_ROOT_AUTH_DATABASE);
    }

    /** {@link #connectionUrl} naming the Mongo authentication database. */
    public static @NonNull String connectionUrl(ManagedDatabase.@NonNull Engine engine,
                                                @NonNull String host, int port,
                                                @Nullable String user, @Nullable String password,
                                                @Nullable String database,
                                                @NonNull String authDatabase) {
        String encodedUser = encode(user);
        String encodedPassword = encode(password);
        String db = database != null ? database : "";
        return switch (engine) {
            case POSTGRES -> "postgres://" + encodedUser + ":" + encodedPassword
                + "@" + host + ":" + port + "/" + db;
            case MYSQL -> "mysql://" + encodedUser + ":" + encodedPassword
                + "@" + host + ":" + port + "/" + db;
            // Redis auths on password alone; there is no per-database path segment to add.
            case REDIS -> "redis://:" + encodedPassword + "@" + host + ":" + port;
            // A dedicated record's user is a root user in the admin database; a shared
            // record's user lives on its own logical database (authDatabaseOf).
            case MONGO -> "mongodb://" + encodedUser + ":" + encodedPassword
                + "@" + host + ":" + port + "/" + db + "?authSource=" + encode(authDatabase);
        };
    }

    private static @NonNull String encode(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        // URLEncoder is form encoding: '+' means space there, but must be %2B in a URL userinfo.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // The owned engine instance's live status, straight off the instance tier.
    private static LiveResolver serviceResolver() {
        return row -> {
            Integer id = row.get(DatabaseModel.ID);
            return id == null
                ? new ManagedDatabase.LiveStatus(
                    be.elevenways.hohenheim.server.runtime.ContainerState.ABSENT, null)
                : DatabaseInstances.liveStatus(id);
        };
    }
}
