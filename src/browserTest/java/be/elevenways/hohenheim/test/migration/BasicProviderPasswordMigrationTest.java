package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.M011_HashBasicProviderPasswords;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.hohenheim.server.auth.types.BasicPasswordHashing;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.auth.server.PasswordHasher;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M011 against the shapes production stored before Basic provider passwords were hashed: every
 * user keeps a working password, nothing is left in plaintext, and nothing else is touched.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class BasicProviderPasswordMigrationTest {

    @Test
    void everyPlaintextPasswordIsHashedAndKeepsWorking() throws Exception {
        SqlDatasource datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        assertThat(BasicPasswordHashing.LOADED).as("the server half of the seam is installed").isTrue();

        // 1. The rows as the pre-hashing build stored them: a plaintext map (one of them an empty
        //    password, which the old compare accepted), the legacy list shape, an already-hashed
        //    map, and a Proteus provider whose config must not be touched.
        String existingHash = PasswordHasher.hash("kept");
        Map<String, Object> plainCredentials = new LinkedHashMap<>();
        plainCredentials.put("alice", "s3cret");
        plainCredentials.put("empty", "");
        int plain = provider(datasource, "hohenheim:basic", Map.of("credentials", plainCredentials));
        int legacy = provider(datasource, "hohenheim:basic", Map.of("credentials", List.of(
            Map.of("username", "old", "password_hash", "legacy-pass"))));
        int hashed = provider(datasource, "hohenheim:basic", Map.of("credentials", Map.of("carol", existingHash)));
        Map<String, Object> proteusConfig = Map.of("endpoint", "https://auth.example.test/",
            "realm_client", "rc", "access_key", "plain-key");
        int proteus = provider(datasource, "hohenheim:proteus", proteusConfig);

        // 2. The data step hashes every plaintext value and every user keeps their password.
        M011_HashBasicProviderPasswords.hashPlaintext(datasource);
        Map<String, String> plainAfter = BasicAuthProviderType.credentials(config(datasource, plain));
        assertThat(plainAfter.values()).as("step 2: nothing is left in plaintext")
            .allMatch(BasicCredentials::isHashed);
        assertThat(BasicAuthProviderType.verify(basic("alice", "s3cret"), plainAfter))
            .as("step 2: alice keeps her password").isEqualTo("alice");
        assertThat(BasicAuthProviderType.verify(basic("empty", ""), plainAfter))
            .as("step 2: an empty password that worked before still works").isEqualTo("empty");

        Map<String, String> legacyAfter = BasicAuthProviderType.credentials(config(datasource, legacy));
        assertThat(BasicCredentials.isHashed(legacyAfter.get("old")))
            .as("step 2: the legacy list shape is hashed in place").isTrue();
        assertThat(BasicAuthProviderType.verify(basic("old", "legacy-pass"), legacyAfter))
            .as("step 2: and still verifies").isEqualTo("old");

        // 3. An already-hashed value is left byte for byte, and other provider types are untouched.
        assertThat(BasicAuthProviderType.credentials(config(datasource, hashed)).get("carol"))
            .as("step 3: an existing hash is not re-hashed").isEqualTo(existingHash);
        assertThat(config(datasource, proteus))
            .as("step 3: a Proteus config is not touched").isEqualTo(proteusConfig);

        // 4. Running the step again changes nothing, and the migration declares it cannot be undone.
        String aliceHash = plainAfter.get("alice");
        M011_HashBasicProviderPasswords.hashPlaintext(datasource);
        assertThat(BasicAuthProviderType.credentials(config(datasource, plain)).get("alice"))
            .as("step 4: a second run is a no-op").isEqualTo(aliceHash);
        assertThat(new M011_HashBasicProviderPasswords().getIrreversibleReason())
            .as("step 4: plaintext cannot be restored, so the migration is irreversible").isNotBlank();
    }

    /**
     * Store a provider row as the old build left it, through a frozen view: the live model would
     * run the config through the provider's current schema, which is exactly what must not
     * happen to a legacy value.
     */
    private static int provider(SqlDatasource datasource, String type, Map<String, Object> config) {
        List<Integer> ids = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField key = IntegerField.builder().name("id").build();
            StringField name = StringField.builder().name("name").build();
            StringField providerType = StringField.builder().name("provider_type").build();
            SchemaField configField = SchemaField.builder("config").build();
            FrozenModel table = new FrozenModel("site_auth_providers", key, name, providerType, configField);
            Row row = table.createEmptyRow();
            row.set(name, "M011 " + type + " " + ids.size() + " " + config.hashCode());
            row.set(providerType, type);
            row.set(configField, config);
            ids.add(table.save(row).get(key));
        });
        return ids.get(0);
    }

    /** The stored config read through an uncached frozen view, as the migration sees the table. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> config(SqlDatasource datasource, int id) {
        List<Object> found = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField key = IntegerField.builder().name("id").build();
            SchemaField config = SchemaField.builder("config").build();
            Row row = new FrozenModel("site_auth_providers", key, config).find().where(key.eq(id)).first();
            found.add(row != null ? row.get(config) : null);
        });
        return (Map<String, Object>) found.get(0);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
