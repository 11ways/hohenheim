package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The vendored community-scripts app catalog and its vocabulary gate. The shim library
 * ({@code hohenheim-functions.sh}) DECLARES the helper vocabulary it implements; the
 * pinned upstream namespace ({@code upstream-vocabulary.txt}) names every helper that
 * exists at the vendored revision; and {@link #requireVocabularyImplemented} refuses --
 * BY NAME -- any script calling an upstream helper the library lacks. Import copies
 * script CONTENT into template rows (checksummed, revision recorded), so a later
 * upstream edit provably cannot change what an approved template installs.
 *
 * AIDEV-NOTE: the static scan looks at COMMAND POSITIONS (first token of each line and
 * of each {@code && || ; |}-separated segment, plus the token after {@code $STD}), not
 * at every word -- upstream vocabulary contains generic names ("start", "variables")
 * that would false-positive inside strings. Anything the scan cannot see is caught at
 * runtime by the shim's command_not_found_handle; the two lanes are stated as layered
 * in the tests, not pretended to be one.
 */
public final class CommunityScripts {

    /** The one marker that makes a script a function-library script. */
    public static final String LIBRARY_MARKER = "FUNCTIONS_FILE_PATH";

    private static final String RESOURCE_ROOT = "community-scripts/";

    private static volatile @Nullable Path catalogOverride;

    private static volatile @Nullable String cachedLibrary;
    private static volatile @Nullable Set<String> cachedImplemented;
    private static volatile @Nullable Set<String> cachedUpstream;

    private CommunityScripts() {
    }

    /** Test seam: read catalog/library files from a directory instead of the classpath. */
    public static void overrideCatalogRootForTest(@Nullable Path root) {
        catalogOverride = root;
        cachedLibrary = null;
        cachedImplemented = null;
        cachedUpstream = null;
    }

    // -- the function library -------------------------------------------------

    /** The full shim library text, the value {@code FUNCTIONS_FILE_PATH} carries. */
    public static @NonNull String functionsLibrary() {
        String library = cachedLibrary;
        if (library == null) {
            library = readResource("hohenheim-functions.sh");
            cachedLibrary = library;
        }
        return library;
    }

    /** The helper names the shim library declares it implements. */
    public static @NonNull Set<String> implementedVocabulary() {
        Set<String> implemented = cachedImplemented;
        if (implemented == null) {
            Matcher matcher = Pattern.compile("^HOHENHEIM_FUNCS_VOCABULARY=\"([^\"]+)\"",
                Pattern.MULTILINE).matcher(functionsLibrary());
            if (!matcher.find()) {
                throw new IllegalStateException("hohenheim-functions.sh declares no"
                    + " HOHENHEIM_FUNCS_VOCABULARY line; the vocabulary gate cannot run");
            }
            implemented = Set.copyOf(List.of(matcher.group(1).trim().split("\\s+")));
            cachedImplemented = implemented;
        }
        return implemented;
    }

    /** Every helper name that exists upstream at the pinned revision. */
    public static @NonNull Set<String> upstreamVocabulary() {
        Set<String> upstream = cachedUpstream;
        if (upstream == null) {
            Set<String> names = new LinkedHashSet<>();
            for (String line : readResource("upstream-vocabulary.txt").split("\n")) {
                String name = line.trim();
                if (!name.isEmpty() && !name.startsWith("#")) {
                    names.add(name);
                }
            }
            upstream = Set.copyOf(names);
            cachedUpstream = upstream;
        }
        return upstream;
    }

    /** Whether this script sources the injected function library. */
    public static boolean requiresFunctionLibrary(@Nullable String script) {
        return script != null && script.contains(LIBRARY_MARKER);
    }

    /**
     * The upstream helpers this script calls that the shim library does not implement
     * (command-position scan; sorted for stable refusal messages).
     */
    public static @NonNull Set<String> unimplementedHelpers(@NonNull String script) {
        Set<String> implemented = implementedVocabulary();
        Set<String> upstream = upstreamVocabulary();
        Set<String> missing = new TreeSet<>();
        for (String token : commandTokens(script)) {
            if (upstream.contains(token) && !implemented.contains(token)) {
                missing.add(token);
            }
        }
        return missing;
    }

    /**
     * The static half of the vocabulary gate, run at import, approval and install.
     *
     * @throws Violations {@code helper_not_implemented} naming every missing helper
     */
    public static void requireVocabularyImplemented(@Nullable String script,
                                                    @NonNull String what) {
        if (script == null || !requiresFunctionLibrary(script)) {
            return;
        }
        Set<String> missing = unimplementedHelpers(script);
        if (!missing.isEmpty()) {
            throw Violations.ofForm(Microcopy.of("helper_not_implemented")
                .withFilter("scope", "violations")
                .withArg("what", what)
                .withArg("helpers", String.join(", ", missing)));
        }
    }

    /** Command-position tokens of a shell script (see the class AIDEV-NOTE). */
    static @NonNull List<String> commandTokens(@NonNull String script) {
        List<String> tokens = new ArrayList<>();
        for (String rawLine : script.split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            for (String segment : line.split("&&|\\|\\||;|\\|")) {
                String[] words = segment.strip().split("\\s+");
                if (words.length == 0) {
                    continue;
                }
                String first = words[0];
                if (("$STD".equals(first) || "\"$STD\"".equals(first)) && words.length > 1) {
                    first = words[1];
                }
                if (first.matches("[a-z_][a-z0-9_]*")) {
                    tokens.add(first);
                }
            }
        }
        return tokens;
    }

    // -- the vendored catalog -------------------------------------------------

    /** One vendored app's parsed {@code ct/*.sh} manifest. */
    public record Manifest(@NonNull String app, @NonNull String appKey,
                           @Nullable Integer cpu, @Nullable Integer ramMb,
                           @NonNull String os, @NonNull String osVersion,
                           boolean unprivileged, @NonNull String description,
                           @NonNull String updateScript) {

        /** The images: simplestreams alias this manifest maps onto. */
        public @NonNull String imageAlias() {
            return this.os + "/" + this.osVersion;
        }
    }

    /** The vendored app keys, in catalog order. */
    public static @NonNull List<String> catalogApps() {
        List<String> apps = new ArrayList<>();
        for (String line : readResource("catalog/REVISION").split("\n")) {
            Matcher matcher = Pattern.compile("^vendored: catalog/([a-z0-9-]+) ").matcher(line);
            if (matcher.find()) {
                apps.add(matcher.group(1));
            }
        }
        return apps;
    }

    /** The pinned upstream revision line of the vendored catalog. */
    public static @NonNull String catalogRevision() {
        for (String line : readResource("catalog/REVISION").split("\n")) {
            if (line.startsWith("revision: ")) {
                return line.substring("revision: ".length()).trim();
            }
        }
        throw new IllegalStateException("catalog/REVISION declares no revision line");
    }

    /** Parse one vendored app's manifest ({@code ct.sh}). */
    public static @NonNull Manifest manifestOf(@NonNull String appKey) {
        String ct = readResource("catalog/" + appKey + "/ct.sh");
        String app = firstGroup(ct, "^APP=\"([^\"]+)\"");
        if (app == null) {
            throw new IllegalStateException("catalog app '" + appKey
                + "' declares no APP name in its ct.sh");
        }
        Integer cpu = intVar(ct, "var_cpu");
        Integer ram = intVar(ct, "var_ram");
        String os = stringVar(ct, "var_os");
        String version = stringVar(ct, "var_version");
        if (os == null || version == null) {
            throw new IllegalStateException("catalog app '" + appKey
                + "' declares no var_os/var_version; no image can be derived");
        }
        String unprivileged = stringVar(ct, "var_unprivileged");
        String source = firstGroup(ct, "^# Source: (.+)$");
        return new Manifest(app, appKey, cpu, ram, os, version,
            !"0".equals(unprivileged),
            source == null ? "" : "Source: " + source.trim(),
            updateScriptOf(ct));
    }

    /**
     * Import one vendored app as a NEW, UNAPPROVED instance template: the pinned
     * install/update script content is COPIED into the row (the pin), the source names
     * repo@revision, and the checksum covers the install script bytes. Re-importing
     * always creates another row -- an approved template is never mutated by an import.
     *
     * @return the created template row id
     * @throws Violations {@code helper_not_implemented} when the scripts call helpers
     *         the shim library lacks
     */
    public static int importApp(@NonNull String appKey) {
        if (!catalogApps().contains(appKey)) {
            throw Violations.ofField("catalog_app", appKey,
                Microcopy.of("catalog_app_unknown").withFilter("scope", "violations")
                    .withArg("app", appKey));
        }
        Manifest manifest = manifestOf(appKey);
        String install = readResource("catalog/" + appKey + "/install.sh");
        requireVocabularyImplemented(install, appKey + " install script");
        requireVocabularyImplemented(manifest.updateScript(), appKey + " update script");

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", manifest.imageAlias());
        if (manifest.ramMb() != null) {
            settings.put("memory_limit_mb", manifest.ramMb());
        }
        if (manifest.cpu() != null) {
            settings.put("cpu_limit", manifest.cpu().doubleValue());
        }
        settings.put("privileged", !manifest.unprivileged());
        // AIDEV-NOTE: var_disk is DROPPED BY NAME: the incus kind has no per-instance
        // root-disk quota mechanism yet; mapping it onto nothing would be a limit that
        // exists only on paper. Grow it together with the enforcement.

        InstanceTemplateModel templates = Models.get(InstanceTemplateModel.class);
        Row template = templates.createEmptyRow();
        template.set(InstanceTemplateModel.NAME, manifest.app());
        template.set(InstanceTemplateModel.DESCRIPTION, manifest.description());
        template.set(InstanceTemplateModel.KIND, SystemContainerKind.ID.toString());
        template.set(InstanceTemplateModel.SETTINGS, settings);
        template.set(InstanceTemplateModel.INSTALL_SCRIPT, install);
        template.set(InstanceTemplateModel.UPDATE_SCRIPT, manifest.updateScript());
        template.set(InstanceTemplateModel.REINSTALL_POLICY,
            InstanceTemplateModel.REINSTALL_PRESERVE);
        template.set(InstanceTemplateModel.SOURCE, "community-scripts/ProxmoxVE@"
            + catalogRevision() + " " + appKey);
        template.set(InstanceTemplateModel.SOURCE_CHECKSUM, SecureTokens.sha256Hex(install));
        template.set(InstanceTemplateModel.IMPORTED_AT, Instant.now());
        // NEVER approved by import: the operator act is the trust decision.
        templates.save(template);
        return template.get(InstanceTemplateModel.ID);
    }

    // -- plumbing -------------------------------------------------------------

    /** The {@code update_script()} function body of a ct script, or empty. */
    static @NonNull String updateScriptOf(@NonNull String ct) {
        String[] lines = ct.split("\n");
        StringBuilder body = new StringBuilder();
        boolean inside = false;
        for (String line : lines) {
            if (!inside && line.matches("^\\s*(function\\s+)?update_script\\(\\)\\s*\\{\\s*$")) {
                inside = true;
                continue;
            }
            if (inside) {
                if (line.equals("}")) {
                    return body.toString();
                }
                body.append(line).append('\n');
            }
        }
        return "";
    }

    private static @Nullable String firstGroup(@NonNull String text, @NonNull String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.MULTILINE).matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** The default of a {@code var_x="${var_x:-default}"} manifest line. */
    private static @Nullable String stringVar(@NonNull String ct, @NonNull String name) {
        return firstGroup(ct, "^" + name + "=\"\\$\\{" + name + ":-([^}]*)\\}\"");
    }

    private static @Nullable Integer intVar(@NonNull String ct, @NonNull String name) {
        String value = stringVar(ct, name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static @NonNull String readResource(@NonNull String relative) {
        Path override = catalogOverride;
        if (override != null) {
            try {
                return Files.readString(override.resolve(relative), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Unreadable catalog file " + relative
                    + " under test override " + override, e);
            }
        }
        try (InputStream in = CommunityScripts.class.getClassLoader()
                .getResourceAsStream(RESOURCE_ROOT + relative)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled resource "
                    + RESOURCE_ROOT + relative);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable bundled resource "
                + RESOURCE_ROOT + relative, e);
        }
    }
}
