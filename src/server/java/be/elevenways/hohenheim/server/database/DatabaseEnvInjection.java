package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
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
 * Derives the environment variables a site's processes receive for each attached managed
 * database, resolved at spawn time so the published port and credentials are always current
 * (nothing is baked into stored site settings). A database that is not active-and-running
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
     * The address shape the consuming runtime can actually reach: host processes dial the
     * database's published loopback port; a Docker site container sits on its own private
     * network where 127.0.0.1 is ITSELF, so it gets the database's container hostname on
     * the shared link network and the engine's native port instead (SiteDatabaseNetworks
     * joins the pair before the container starts).
     */
    public enum Style {
        PUBLISHED_LOOPBACK,
        CONTAINER_NETWORK
    }

    private DatabaseEnvInjection() {
    }

    /** Injected environment for a site using live Docker state; empty when nothing is attached. */
    public static @NonNull Map<String, String> envForSite(int siteId) {
        return envForSite(siteId, null, Style.PUBLISHED_LOOPBACK);
    }

    /** {@link #envForSite(int, LiveResolver, Style)} in the host-process (loopback) style. */
    public static @NonNull Map<String, String> envForSite(int siteId, @Nullable LiveResolver resolver) {
        return envForSite(siteId, resolver, Style.PUBLISHED_LOOPBACK);
    }

    /**
     * Injected environment for a site. Fail-soft by design: a spawn must never die on
     * injection plumbing, so any resolution error degrades to "no variables" with a log.
     */
    public static @NonNull Map<String, String> envForSite(int siteId, @Nullable LiveResolver resolver,
                                                          @NonNull Style style) {
        try {
            SiteDatabaseModel links = Models.get(SiteDatabaseModel.class);
            DatabaseModel databases = Models.get(DatabaseModel.class);
            if (links == null || databases == null) {
                return Map.of();
            }
            List<Row> linkRows = links.findBySiteId(siteId);
            if (linkRows.isEmpty()) {
                return Map.of();
            }
            LiveResolver live = resolver != null ? resolver : serviceResolver();
            Map<String, String> env = new LinkedHashMap<>();
            // DATABASE_URL belongs to the FIRST link, resolved or not: an unavailable
            // primary must never silently hand the bare name to a different database.
            boolean primary = true;
            for (Row link : linkRows) {
                Row database = databases.find()
                    .where(DatabaseModel.ID.eq(link.get(SiteDatabaseModel.DATABASE_ID)))
                    .first();
                appendLink(env, siteId, link, database, live, primary, style);
                primary = false;
            }
            return env;
        } catch (Exception e) {
            Blast.log("DB-INJECT: env resolution failed for site", siteId, "-", e.getMessage());
            return Map.of();
        }
    }

    private static void appendLink(Map<String, String> env, int siteId, Row link,
                                   @Nullable Row database, LiveResolver live, boolean primary,
                                   Style style) {
        Integer databaseId = link.get(SiteDatabaseModel.DATABASE_ID);
        if (database == null) {
            unresolved(siteId, databaseId, null, "record_missing");
            return;
        }
        String name = database.get(DatabaseModel.NAME);
        if (!DatabaseModel.STATUS_ACTIVE.equals(database.get(DatabaseModel.STATUS))) {
            unresolved(siteId, databaseId, name, "not_active");
            return;
        }
        ManagedDatabase.LiveStatus status = live.resolve(database);
        // The container-network style needs no published port (it dials the engine's own
        // port over the link network), but the engine must still actually be running:
        // credentials for a dead database are the silent-success shape, not a favour.
        if (!status.running()
                || (style == Style.PUBLISHED_LOOPBACK && status.port() == null)) {
            unresolved(siteId, databaseId, name, "container_not_running");
            return;
        }
        ManagedDatabase.Engine engine;
        try {
            engine = ManagedDatabase.engineOf(database);
        } catch (IllegalArgumentException e) {
            unresolved(siteId, databaseId, name, "unknown_engine");
            return;
        }
        String host = "127.0.0.1";
        if (style == Style.CONTAINER_NETWORK) {
            // The engine's OWN container handle, resolved from the owned instance -- never
            // guessed from the record name. A database that owns no instance has no
            // reachable address at all, which is a skip, not a hostname that resolves to
            // nothing inside the consumer's network.
            host = DatabaseInstances.handleOf(databaseId);
            if (host == null) {
                unresolved(siteId, databaseId, name, "no_engine_instance");
                return;
            }
        }
        int port = style == Style.CONTAINER_NETWORK ? engine.port() : status.port();
        String prefix = normalizedPrefix(link.get(SiteDatabaseModel.ENV_PREFIX));
        env.putAll(vars(engine, host, port, database.get(DatabaseModel.DB_USER),
            database.get(DatabaseModel.DB_PASSWORD), database.get(DatabaseModel.DB_NAME),
            prefix, primary));
    }

    private static void unresolved(int siteId, @Nullable Integer databaseId,
                                   @Nullable String name, String reason) {
        Blast.slog("hohenheim.db_injection.unresolved", Map.of(
            "site_id", String.valueOf(siteId),
            "database_id", databaseId != null ? String.valueOf(databaseId) : "(none)",
            "database", name != null ? name : "(unknown)",
            "reason", reason));
    }

    /** Uppercased prefix, {@link SiteDatabaseModel#DEFAULT_PREFIX} when blank. */
    public static @NonNull String normalizedPrefix(@Nullable String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return SiteDatabaseModel.DEFAULT_PREFIX;
        }
        return prefix.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * The variable family for one resolved link: {@code {PREFIX}_HOST/PORT/USER/PASSWORD/NAME/URL},
     * plus the bare {@code DATABASE_URL} when this is the site's primary link.
     */
    public static @NonNull Map<String, String> vars(ManagedDatabase.@NonNull Engine engine,
                                                    @NonNull String host, int port,
                                                    @Nullable String user, @Nullable String password,
                                                    @Nullable String database,
                                                    @NonNull String prefix, boolean primary) {
        Map<String, String> env = new LinkedHashMap<>();
        String url = connectionUrl(engine, host, port, user, password, database);
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
            // ManagedDatabase creates the user as a root user in the admin database.
            case MONGO -> "mongodb://" + encodedUser + ":" + encodedPassword
                + "@" + host + ":" + port + "/" + db + "?authSource=admin";
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
