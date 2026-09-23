package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.instance.DeviceType;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * {@link DeviceType} is the one home of the device-type vocabulary: its members are
 * exactly the column's declared enum values, each member states its quota charge, and a
 * token that is no member is refused rather than read as a NIC.
 */
class DeviceTypeVocabularyDriftTest {

    @Test
    void theEnumIsBoundToTheColumnAndFailsClosed() {
        // 1. The members' tokens ARE the column's declared values: adding a type to the
        //    model without a member (or the reverse) fails here.
        Set<String> tokens = Arrays.stream(DeviceType.values())
            .map(DeviceType::token).collect(Collectors.toSet());
        assertThat(tokens)
            .as("step 1: DeviceType declares exactly the device column's enum values")
            .isEqualTo(InstanceDeviceModel.TYPE.getValues().keySet());

        // 2. Every stored token parses back onto its own member.
        for (DeviceType type : DeviceType.values()) {
            assertThat(DeviceType.parse(type.token()))
                .as("step 2: token '%s' parses to its member", type.token()).isSameAs(type);
        }

        // 3. The charges are facts on the members, never an else-branch: only a disk
        //    owns a volume, only install media is operator-only and charges nothing.
        assertThat(DeviceType.DISK.charge())
            .as("step 3: a disk charges gigabytes").isEqualTo(DeviceType.QuotaCharge.DISK_GIGABYTES);
        assertThat(DeviceType.NIC.charge())
            .as("step 3: a NIC charges one slot").isEqualTo(DeviceType.QuotaCharge.NIC_SLOT);
        assertThat(DeviceType.CDROM.charge())
            .as("step 3: install media charges nothing").isEqualTo(DeviceType.QuotaCharge.NONE);
        assertThat(DeviceType.DISK.ownsVolume() && !DeviceType.NIC.ownsVolume()
                && !DeviceType.CDROM.ownsVolume())
            .as("step 3: only a disk owns a daemon volume").isTrue();
        assertThat(DeviceType.CDROM.operatorOnly() && !DeviceType.DISK.operatorOnly()
                && !DeviceType.NIC.operatorOnly())
            .as("step 3: only install media is operator-only").isTrue();

        // 4. An unknown or absent token is NO member, and require() refuses it by name.
        assertThat(DeviceType.parse("floppy")).as("step 4: an unknown token parses to null").isNull();
        assertThat(DeviceType.parse(null)).as("step 4: an absent token parses to null").isNull();
        Throwable refused = catchThrowable(() -> DeviceType.require("floppy"));
        assertThat(refused).as("step 4: require refuses an unknown token")
            .isInstanceOf(Violations.class);
        assertThat(((Violations) refused).all().get(0).message().key())
            .as("step 4: with the named device-type refusal").isEqualTo("device_type_unknown");
    }
}
