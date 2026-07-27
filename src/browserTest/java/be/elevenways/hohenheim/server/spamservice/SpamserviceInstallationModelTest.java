package be.elevenways.hohenheim.server.spamservice;

import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.validator.Range;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the zero-row, typed RowSingleton backing contract. */
class SpamserviceInstallationModelTest {

    @BeforeAll
    static void initDb() throws Exception {
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void migrationCreatesTheDisabledFixedSingleton() {
        assertThat(Models.get(SpamserviceInstallationModel.class).find().count()).isOne();
        assertThat(Models.get(SpamserviceInstallationModel.class).installation())
            .extracting(row -> row.get(SpamserviceInstallationModel.ID))
            .isEqualTo(SpamserviceInstallationModel.SINGLETON_ID);
    }

    @Test
    void configurationUsesNativeFieldAndRelationTypes() {
        assertThat(SpamserviceInstallationModel.PORT).isInstanceOf(IntegerField.class);
        assertThat(SpamserviceInstallationModel.MIN_PORT).isEqualTo(1024);
        assertThat(SpamserviceInstallationModel.SYSTEM_USER_ID).isInstanceOf(IntegerField.class);
        assertThat(SpamserviceInstallationModel.MAX_HEAP_MB).isInstanceOf(IntegerField.class);
        assertThat(SpamserviceInstallationModel.SYSTEM_USER.getRelatedModel())
            .isInstanceOf(SystemUserModel.class);
        assertThat(SpamserviceInstallationModel.PORT.getValidators())
            .anyMatch(Range.class::isInstance);
        assertThat(SpamserviceInstallationModel.MAX_HEAP_MB.getValidators())
            .anyMatch(Range.class::isInstance);
        assertThat(SpamserviceInstallationModel.CONTROLLER_KEY.isSecret()).isTrue();
    }
}
