package be.elevenways.hohenheim.server.process;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.security.SecurityReportEnv;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * The reserved control variables of a managed child's environment: names the parent
 * generates per spawn and an operator must never be able to override, because each one
 * is load-bearing for a security or bookkeeping boundary (the IPC secret, the allocated
 * port, the security-event reporting destination).
 *
 * Deliberately NOT reserved: PATH and LANG (the child's own execution environment, an
 * operator may legitimately shape them) and the injected DATABASE_* variables (a site may
 * point at an external database on purpose) -- overriding those logs a warning at spawn
 * time instead of being refused.
 */
public final class ReservedEnv {

    /** Exact reserved names; the HOHENHEIM_ prefix is reserved as a whole besides these. */
    public static final Set<String> RESERVED_NAMES = Set.of(
        "HOHENHEIM_IPC_TOKEN",      // the per-child channel secret
        "HOHENHEIM_IPC_PORT",       // where that secret must be presented
        "PORT",                     // overriding it squats a port outside PortAllocator's bookkeeping
        "PATH_TO_SOCKET",           // the unix-socket equivalent of PORT
        SecurityReportEnv.URL_VAR,  // an override redirects the security event stream
        SecurityReportEnv.TOKEN_VAR,
        "HOME");                    // derived from the run-as identity, never operator data

    public static final String RESERVED_PREFIX = "HOHENHEIM_";

    private static volatile boolean installed = false;

    private ReservedEnv() {
    }

    /** @return true when an operator must not supply this variable name */
    public static boolean isReserved(@Nullable String name) {
        if (name == null) {
            return false;
        }
        return RESERVED_NAMES.contains(name) || name.startsWith(RESERVED_PREFIX);
    }

    /**
     * Stamp the generated control variables over the fully-built environment. This is
     * the load-bearing half of the reservation: whatever the operator map, the injected
     * map or a runtime hook wrote, the generated value wins and stray HOHENHEIM_*
     * variables are dropped.
     *
     * AIDEV-NOTE: reserved variables are stamped LAST -- after every putAll -- because
     * the previous order wrote them first "so an explicit value always wins", which
     * meant a site-configured HOHENHEIM_IPC_TOKEN replaced the generated channel secret
     * and a configured ZENIT_SECURITY_REPORT_URL redirected the site's security events
     * to an attacker-chosen collector. Save-time validation alone cannot carry this:
     * settings also arrive via API key write-back, clone, seeds and imports.
     *
     * @param env      the child environment, mutated in place
     * @param reserved generated values per reserved name; a null value REMOVES the name
     * @param siteName for the log line when an operator value is discarded
     */
    public static void stamp(@NonNull Map<String, String> env,
                             @NonNull Map<String, @Nullable String> reserved,
                             @NonNull String siteName) {
        for (Map.Entry<String, String> entry : reserved.entrySet()) {
            String name = entry.getKey();
            String generated = entry.getValue();
            String present = env.get(name);
            if (present != null && !present.equals(generated)) {
                Blast.log("PROCESS: site", siteName, "configured reserved variable", name,
                    "- the generated value is used instead");
            }
            if (generated != null) {
                env.put(name, generated);
            } else {
                env.remove(name);
            }
        }
        env.keySet().removeIf(name -> {
            boolean stray = name.startsWith(RESERVED_PREFIX) && !reserved.containsKey(name);
            if (stray) {
                Blast.log("PROCESS: site", siteName, "configured reserved variable", name,
                    "- dropped (the", RESERVED_PREFIX, "prefix is reserved)");
            }
            return stray;
        });
    }

    /** Logs (never refuses) operator overrides of injected non-reserved values like DATABASE_*. */
    public static void warnOverriddenInjected(@NonNull Map<String, String> injected,
                                              @NonNull Map<String, String> operator,
                                              @NonNull String siteName) {
        for (String name : operator.keySet()) {
            if (injected.containsKey(name) && !isReserved(name)) {
                Blast.log("PROCESS: site", siteName, "overrides injected variable", name,
                    "with an operator value (allowed; verify this is intentional)");
            }
        }
    }

    /**
     * Save-time diagnostic: refuse persisting a managed-process site whose configured
     * environment variables use a reserved name. Registered as a MODEL hook (not a
     * resource-layer check) so API-key write-back, clone, seeds and imports are covered.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        SiteModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || !row.has(SiteModel.SETTINGS.getName())) {
                return;
            }
            Object settings = row.get(SiteModel.SETTINGS.getName());
            if (!(settings instanceof Map<?, ?> map) || !map.containsKey("environment_variables")) {
                return;
            }
            String siteType = effectiveSiteType(row);
            UpstreamKindHandler type = UpstreamKindHandlers.getHandler(siteType);
            if (type == null || !type.managedProcessEnvironment()) {
                return;
            }
            for (String name : EnvVars.toMap(map.get("environment_variables")).keySet()) {
                if (isReserved(name)) {
                    throw Violations.ofField("settings.environment_variables", name,
                        Microcopy.of("reserved_env_var")
                            .withFilter("scope", "violations")
                            .withArg("name", name));
                }
            }
        });
    }

    private static @Nullable String effectiveSiteType(@NonNull Row row) {
        if (row.has(SiteModel.UPSTREAM_KIND.getName())) {
            return row.get(SiteModel.UPSTREAM_KIND);
        }
        if (!row.has(SiteModel.ID.getName())) {
            return null;
        }
        Row stored = Models.get(SiteModel.class).findById(row.get(SiteModel.ID));
        return stored != null ? stored.get(SiteModel.UPSTREAM_KIND) : null;
    }
}
