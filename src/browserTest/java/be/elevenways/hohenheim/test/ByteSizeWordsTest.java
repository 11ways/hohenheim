package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.setting.SettingsForms;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hohenheim's sizes read as sizes: a read-only value of a field counting megabytes or bytes says the size it is, in
 * the reader's words, and an input counting a unit names that unit as every size in it reads.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class ByteSizeWordsTest extends HohenheimTestBase {

    /** Journey: a finished build's record page reads its ceilings and measurements as sizes. */
    @Test
    void aBuildsRecordPageReadsItsCeilingsAndMeasurementsAsSizes() throws Exception {
        // 1. A build that ran under a 2048 MB memory and 1536 MB disk ceiling, peaked at 123456789 bytes of disk and
        //    produced a 67890-byte artifact.
        Model builds = Models.get(BuildOperationModel.class);
        Row build = builds.createEmptyRow();
        build.set(BuildOperationModel.BUILDER_KIND, BuildOperationModel.KIND_DOCKERFILE);
        build.set(BuildOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        build.set(BuildOperationModel.FOR_ID, 1);
        build.set(BuildOperationModel.STATUS, BuildOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED));
        build.set(BuildOperationModel.SOURCE_REF, "bytesizes-ref");
        build.set(BuildOperationModel.IMAGE_ID, "sha256:bytesizes-image");
        build.set(BuildOperationModel.MEMORY_LIMIT_MB, 2048);
        build.set(BuildOperationModel.DISK_LIMIT_MB, 1536);
        build.set(BuildOperationModel.PEAK_DISK_BYTES, 123_456_789L);
        build.set(BuildOperationModel.ARTIFACT_BYTES, 67_890L);
        builds.save(build);

        // 2. THE ASSERTION: each value reads as the size it is, never as a count beside a unit.
        navigateToApp(CmsRoutes.detail(HohenheimSlugs.ADMIN, HohenheimSlugs.BUILDS,
            build.get(BuildOperationModel.ID)).toUrl());
        assertThat(readonlyEntry("memory_limit_mb")).as("step 2: 2048 MB of memory is 2 GB").isEqualTo("2 GB");
        assertThat(readonlyEntry("disk_limit_mb")).as("step 2: 1536 MB of disk is 1.5 GB").isEqualTo("1.5 GB");
        assertThat(readonlyEntry("peak_disk_bytes")).as("step 2: a byte count reads in the unit it calls for")
            .isEqualTo("117.7 MB");
        assertThat(readonlyEntry("artifact_bytes")).as("step 2: and a small one in kilobytes").isEqualTo("66.3 KB");
    }

    /** A size setting's input names its unit in the word every size in that unit reads with. */
    @Test
    void aSizeSettingsInputNamesItsUnitAsSizesReadIt() {
        assertThat(SettingsForms.fieldFor(HohenheimSettings.Database.MAX_DUMP_MB).getSuffix()
            .resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver()))
            .as("a dump cap counted in megabytes names them as a size in megabytes reads").isEqualTo("MB");
    }

    /** The text of one read-only entry, by its form path, folded section or not. */
    private String readonlyEntry(String path) {
        return page.locator("pl-field[data-path='" + path + "'] .zf-field-readonly").first().textContent().trim();
    }
}
