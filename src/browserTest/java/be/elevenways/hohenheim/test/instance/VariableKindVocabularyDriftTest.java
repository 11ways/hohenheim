package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.instance.VariableKind;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.server.instance.ConsoleRedaction;
import be.elevenways.hohenheim.server.instance.InstanceApi;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VariableKind} is the one home of the variable-kind vocabulary, bound to the
 * column's declared values, and every READER fails closed: a kind nobody recognizes is a
 * secret, so it is redacted and its value is never projected.
 */
class VariableKindVocabularyDriftTest extends HohenheimTestBase {

    @Test
    void theEnumIsBoundToTheColumnAndReadersFailClosed() {
        // 1. The members' tokens ARE the column's declared values.
        Set<String> tokens = Arrays.stream(VariableKind.values())
            .map(VariableKind::token).collect(Collectors.toSet());
        assertThat(tokens)
            .as("step 1: VariableKind declares exactly the variable column's enum values")
            .isEqualTo(InstanceVariableModel.KIND.getValues().keySet());
        assertThat(VariableKind.of(InstanceVariableModel.KIND_SECRET).isSecret())
            .as("step 1: the secret token is a secret").isTrue();
        assertThat(VariableKind.of(InstanceVariableModel.KIND_PLAIN).isSecret())
            .as("step 1: the plain token is not").isFalse();

        // 2. An unknown or absent token reads as SECRET: never shown, always redacted.
        assertThat(VariableKind.of("mystery"))
            .as("step 2: an unknown kind reads as a secret").isEqualTo(VariableKind.SECRET);
        assertThat(VariableKind.of(null))
            .as("step 2: an absent kind reads as a secret").isEqualTo(VariableKind.SECRET);
        assertThat(VariableKind.parse("mystery"))
            .as("step 2: while parse (the writers' question) says it is no member").isNull();

        // 3. The API projection of a row whose stored kind drifted withholds its value:
        //    the old reader projected anything not spelled "secret" as plain text.
        Row drifted = Models.get(InstanceVariableModel.class).createEmptyRow();
        drifted.set(InstanceVariableModel.KEY, "DRIFTED");
        drifted.set(InstanceVariableModel.KIND, "mystery");
        drifted.set(InstanceVariableModel.PLAIN_VALUE, "hunter2-plaintext-value");
        List<Map<String, Object>> projected = InstanceApi.variableProjection(List.of(drifted));
        assertThat(projected.get(0))
            .as("step 3: an unknown-kind row projects as a secret")
            .containsEntry("kind", InstanceVariableModel.KIND_SECRET)
            .doesNotContainKey("value");

        // 4. The console redactor treats a STORED drifted row as a secret too, from
        //    whichever column carries its value (a hook-free write, as an older or
        //    foreign writer could leave it).
        Row instance = Models.get(InstanceModel.class).createEmptyRow();
        instance.set(InstanceModel.NAME, "varkind-drift");
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        Models.get(InstanceModel.class).save(instance);
        int instanceId = instance.get(InstanceModel.ID);
        try {
            Row stored = Models.get(InstanceVariableModel.class).createEmptyRow();
            stored.set(InstanceVariableModel.INSTANCE_ID, instanceId);
            stored.set(InstanceVariableModel.KEY, "DRIFTED");
            stored.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_PLAIN);
            stored.set(InstanceVariableModel.PLAIN_VALUE, "hunter2-plaintext-value");
            Models.get(InstanceVariableModel.class).save(stored);
            assertThat(ConsoleRedaction.secretsOf(instanceId))
                .as("step 4: a PLAIN value is not a console secret")
                .doesNotContain("hunter2-plaintext-value");
            Models.get(InstanceVariableModel.class).find()
                .where(InstanceVariableModel.ID.eq(stored.get(InstanceVariableModel.ID)))
                .assign(InstanceVariableModel.KIND, "mystery")
                .updateAll();
            assertThat(ConsoleRedaction.secretsOf(instanceId))
                .as("step 4: an unknown-kind value IS redacted, read from its plain column")
                .contains("hunter2-plaintext-value");
        } finally {
            for (Row row : Models.get(InstanceVariableModel.class).findByInstanceId(instanceId)) {
                Models.get(InstanceVariableModel.class).delete(row.get(InstanceVariableModel.ID));
            }
            Models.get(InstanceModel.class).delete(instanceId);
        }
    }
}
