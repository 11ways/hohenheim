package be.elevenways.hohenheim.migration;

import be.elevenways.protoblast.common.platform.PlatformSeam;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hashes every password a Basic auth provider stored in plaintext, so verification can refuse
 * anything that is not an argon2 hash.
 *
 * AIDEV-NOTE: irreversible by nature: a hash cannot give the plaintext back. Every user keeps
 * the password they had, INCLUDING an empty one (it is hashed like any other value, since the
 * old plaintext compare accepted it). Both stored shapes are rewritten in place: the canonical
 * {@code credentials} username -> password map and the legacy list of {@code username} /
 * {@code password_hash} entries. The hashing itself is argon2 from zenit-auth's server side,
 * which this common source set cannot see, hence {@link #HASHING}.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public class M011_HashBasicProviderPasswords extends HohenheimMigration {

    /** The provider type whose config this rewrites, as production stored it. */
    static final String BASIC_TYPE = "hohenheim:basic";

    /** The server's password hashing, installed at class-load by the server source set. */
    public static final PlatformSeam<PasswordHashing> HASHING = PlatformSeam.required(PasswordHashing.class);

    /** Turns a stored password into its stored hash. */
    public interface PasswordHashing {

        /** @return {@code stored} unchanged when it already is a hash, else its argon2 hash */
        @NonNull String hashIfPlaintext(@NonNull String stored);
    }

    public M011_HashBasicProviderPasswords() {
        super("011", "Hash Basic auth provider passwords");
        irreversible("the plaintext passwords are replaced by their argon2 hashes, which cannot be reversed");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.data("hash every plaintext Basic auth provider password", "1",
            M011_HashBasicProviderPasswords::hashPlaintext);
    }

    /** Never run: the migration is declared irreversible, and the executor refuses the DOWN first. */
    @Override
    public void down(@NonNull MigrationBuilder schema) {
    }

    /** The data step: rewrite every Basic provider row that still holds a plaintext password. */
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

    /** @return the config with every password hashed, or the SAME instance when nothing changed */
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
}
