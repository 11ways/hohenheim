package be.elevenways.hohenheim.server.spamservice;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipException;

/**
 * The environment names a nested Spamservice build reads its database url from, as its own jar manifest declares.
 *
 * AIDEV-NOTE: Hohenheim nests whatever spamservice-server SNAPSHOT its build resolved, so a Hohenheim built against a
 * stale local repository still deploys a Spamservice from before database.url (spamservice d50311a). The two
 * spellings cannot both be written: a settings build REFUSES boot while ZENIT_DB_URL is set. So the build names its
 * own contract in the {@link #MANIFEST_ATTRIBUTE} manifest attribute (spamservice build.gradle), and a jar without it
 * or with a value this enum does not know refuses: Hohenheim's serverJar runs {@link #of} on the distribution it nests
 * (build.gradle), so no Hohenheim built from this code can carry one, and the launch asks the same question again.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
enum SpamserviceDatabaseEnvironment {

    /** database.url through the framework settings chain; the retired ZENIT_DB_* names refuse boot. */
    SETTINGS("settings", "ZENIT__DATABASE__URL");

    /** The serverJar manifest attribute a Spamservice build declares its database environment in. */
    static final String MANIFEST_ATTRIBUTE = "Spamservice-Database-Environment";

    private final @NonNull String declaration;
    private final String urlVariable;

    SpamserviceDatabaseEnvironment(@NonNull String declaration, @NonNull String urlVariable) {
        this.declaration = declaration;
        this.urlVariable = urlVariable;
    }

    /** Writes the database url under the one name this build reads. */
    void put(@NonNull Map<String, String> environment, @NonNull String url) {
        environment.put(this.urlVariable, url);
    }

    /**
     * @return the environment the jar declares
     * @throws IllegalStateException for a jar that declares none (or is no jar at all) and for a declaration this
     *         Hohenheim does not know: never guess a database
     */
    static @NonNull SpamserviceDatabaseEnvironment of(@NonNull Path jar) throws IOException {
        String declared;
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            declared = manifest == null ? null : manifest.getMainAttributes().getValue(MANIFEST_ATTRIBUTE);
        } catch (ZipException notAJar) {
            declared = null;
        }
        return ofDeclaration(declared);
    }

    /**
     * @throws IllegalStateException for no declaration and for one this Hohenheim does not know
     */
    static @NonNull SpamserviceDatabaseEnvironment ofDeclaration(@Nullable String declared) {
        if (declared == null) {
            throw new IllegalStateException("The nested Spamservice declares no " + MANIFEST_ATTRIBUTE
                + "; it predates the settings contract, so build Hohenheim against a current spamservice-server");
        }
        for (SpamserviceDatabaseEnvironment environment : values()) {
            if (declared.equals(environment.declaration)) {
                return environment;
            }
        }
        throw new IllegalStateException("The nested Spamservice declares an unknown database environment '"
            + declared + "'; update Hohenheim before deploying it");
    }
}
