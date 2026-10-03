package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * An instance config file lands on an absolute, non-climbing path with an octal mode, refused on the model's own
 * save, so a direct save (the lane no resource guards) answers to the same rule as the form and the inline cell.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class ContainerFileRulesTest extends HohenheimTestBase {

    @Test
    void aContainerFileIsAbsoluteNeverClimbsAndCarriesAnOctalMode() {
        Model files = Models.get(InstanceFileModel.class);
        int instance = instance();

        // 1. A padded absolute path is stored trimmed.
        Row file = files.createEmptyRow();
        file.set(InstanceFileModel.INSTANCE_ID, instance);
        file.set(InstanceFileModel.CONTAINER_PATH, "  /etc/rules/app.conf ");
        file.set(InstanceFileModel.MODE, "0644");
        files.save(file);
        Row stored = files.findById(file.get(InstanceFileModel.ID));
        assertThat((String) stored.get(InstanceFileModel.CONTAINER_PATH)).as("step 1: the stored path is trimmed")
            .isEqualTo("/etc/rules/app.conf");

        // 2. A relative path is refused on create, anchored on the path.
        Row relative = files.createEmptyRow();
        relative.set(InstanceFileModel.INSTANCE_ID, instance);
        relative.set(InstanceFileModel.CONTAINER_PATH, "etc/rules/app.conf");
        assertThat(refusedField(() -> files.save(relative))).as("step 2: a relative path is refused")
            .isEqualTo(InstanceFileModel.CONTAINER_PATH.getName());

        // 3. An update that climbs, or a mode that is not octal, is refused; the stored row keeps its values.
        Row climbing = files.findById(file.get(InstanceFileModel.ID));
        climbing.set(InstanceFileModel.CONTAINER_PATH, "/etc/../root/.ssh/authorized_keys");
        assertThat(refusedField(() -> files.save(climbing))).as("step 3: a climbing path is refused")
            .isEqualTo(InstanceFileModel.CONTAINER_PATH.getName());
        Row decimal = files.findById(file.get(InstanceFileModel.ID));
        decimal.set(InstanceFileModel.MODE, "0999");
        assertThat(refusedField(() -> files.save(decimal))).as("step 3: a non-octal mode is refused")
            .isEqualTo(InstanceFileModel.MODE.getName());
        Row after = files.findById(file.get(InstanceFileModel.ID));
        assertThat((String) after.get(InstanceFileModel.CONTAINER_PATH)).as("step 3: the path is unchanged")
            .isEqualTo("/etc/rules/app.conf");
        assertThat((String) after.get(InstanceFileModel.MODE)).as("step 3: the mode is unchanged").isEqualTo("0644");

        // 4. A write that touches neither column passes, and an octal mode is accepted.
        after.set(InstanceFileModel.MODE, "0755");
        files.save(after);
        assertThat((String) files.findById(file.get(InstanceFileModel.ID)).get(InstanceFileModel.MODE))
            .as("step 4: an octal mode is stored").isEqualTo("0755");
    }

    private static String refusedField(Runnable save) {
        Violations refused = catchThrowableOfType(save::run, Violations.class);
        assertThat((Throwable) refused).as("the save is refused").isNotNull();
        return refused.all().stream().map(Violation::fieldName).findFirst().orElse("");
    }

    private static int instance() {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, "container-file-rules");
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }
}
