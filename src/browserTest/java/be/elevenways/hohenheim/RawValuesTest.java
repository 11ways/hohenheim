package be.elevenways.hohenheim;

import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.server.instance.NetworkBandwidth;
import be.elevenways.hohenheim.server.instance.RootDisk;
import be.elevenways.hohenheim.server.instance.VmKind;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.zenit.common.orm.model.Schema;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The untyped reads of {@link RawValues}: one whole-number rule through every positive setting reader, one flag read.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
class RawValuesTest {

    @Test
    void aWholeNumberReadsAsItselfAndAFractionReadsAsAbsentEverywhere() {
        // 1. Whole values of any carrier read as the int: Integer, Long, a whole Double and trimmed text.
        assertThat(List.of(512, 512L, 512.0, " 512 ")).allSatisfy(raw -> assertThat(RawValues.parsedInt(raw))
            .as("step 1: %s (%s) is the whole number 512", raw, raw.getClass().getSimpleName()).isEqualTo(512));

        // 2. A fraction is never truncated, and blank, malformed or out-of-range values are absent too.
        assertThat(Arrays.asList(512.5, "512.5", "", "abc", 3_000_000_000L, null))
            .allSatisfy(raw -> assertThat(RawValues.parsedInt(raw))
                .as("step 2: %s reads as absent", raw).isNull());

        // 3. intOr answers the fallback exactly where parsedInt answers absent.
        assertThat(RawValues.intOr(7.0, -1)).as("step 3: a whole Double is read").isEqualTo(7);
        assertThat(RawValues.intOr(7.5, -1)).as("step 3: a fraction falls back").isEqualTo(-1);

        // 4. A positive setting: zero and negatives are absent as well.
        assertThat(RawValues.positiveInt(0)).as("step 4: zero is no positive setting").isNull();
        assertThat(RawValues.positiveInt(-5)).as("step 4: a negative is no positive setting").isNull();
        assertThat(RawValues.positiveInt(40.0)).as("step 4: a whole Double is").isEqualTo(40);

        // 5. Every positive-setting reader follows the same rule: a fraction falls back, it is never truncated.
        assertThat(RootDisk.declaredGb(Map.of(RootDisk.SETTING, 20.5)))
            .as("step 5: a fractional root disk is undeclared").isNull();
        assertThat(NetworkBandwidth.declaredMbit(Map.of(NetworkBandwidth.SETTING, 100.0)))
            .as("step 5: a whole bandwidth Double is declared").isEqualTo(100);
        assertThat(ResourceLimits.fromSettings(Map.of(InstanceKindFields.MEMORY_LIMIT_MB, 512.5), 256)
            .bookedMemoryMb()).as("step 5: a fractional memory limit books the kind's footprint").isEqualTo(256);
        assertThat(ResourceLimits.fromSettings(Map.of(InstanceKindFields.MEMORY_LIMIT_MB, "1024"), 256)
            .bookedMemoryMb()).as("step 5: a whole memory limit books itself").isEqualTo(1024);

        // 6. The untyped container reads: a non-map or non-list answers empty (or null), never a ClassCastException.
        assertThat(RawValues.map("text")).as("step 6: a non-map reads as an empty map").isEmpty();
        assertThat(RawValues.mapOrNull(List.of())).as("step 6: a non-map reads as null").isNull();
        assertThat(RawValues.list(Map.of())).as("step 6: a non-list reads as an empty list").isEmpty();
        assertThat(RawValues.nonBlankString("  ")).as("step 6: blank text is absent").isNull();
        assertThat(RawValues.nonBlankString(" a ")).as("step 6: text is kept untrimmed").isEqualTo(" a ");
    }

    @Test
    void aFlagReadsAsStoredAndAnAbsentFlagAsTheFieldsDeclaredDefault() {
        // 1. A stored Boolean is itself, whatever the declared default.
        assertThat(RawValues.isOn(Map.of(VmKind.SECURE_BOOT.getName(), true), VmKind.SECURE_BOOT))
            .as("step 1: a stored true reads true").isTrue();
        assertThat(RawValues.isOn(Map.of(VmKind.GUEST_AGENT.getName(), false), VmKind.GUEST_AGENT))
            .as("step 1: a stored false reads false over a true default").isFalse();

        // 2. A missing, null or non-Boolean entry reads as the field's declared default.
        Map<String, Object> foreign = new HashMap<>();
        foreign.put(VmKind.SECURE_BOOT.getName(), "true");
        foreign.put(VmKind.GUEST_AGENT.getName(), null);
        assertThat(RawValues.isOn(foreign, VmKind.SECURE_BOOT)).as("step 2: text is no flag, default false").isFalse();
        assertThat(RawValues.isOn(foreign, VmKind.GUEST_AGENT)).as("step 2: null reads default true").isTrue();
        assertThat(RawValues.isOn(null, VmKind.GUEST_AGENT)).as("step 2: no map reads the default").isTrue();

        // 3. A key read names what absent means itself (the git source keys, built per host schema).
        assertThat(RawValues.isOn(Map.of(), GitSourceSchema.SHALLOW_CLONE, true))
            .as("step 3: an absent key reads the stated answer").isTrue();

        // 4. auto_deploy has ONE default: what the form seeds a missing key with is what every reader answers for it,
        //    so a source that never stored the flag shows and gets auto-deploy alike (on by default), and a stored
        //    false (what M011 wrote on every source stored before) still stays off.
        Object declared = GitSourceSchema.addTo(new Schema()).getField(GitSourceSchema.AUTO_DEPLOY).getDefaultValue();
        assertThat(declared).as("step 4: the form's declared default").isEqualTo(true);
        assertThat(GitSourceSchema.autoDeploys(Map.of())).as("step 4: an absent flag reads that same on").isTrue();
        assertThat(GitSourceSchema.autoDeploys(Map.of(GitSourceSchema.AUTO_DEPLOY, false)))
            .as("step 4: a stored false stays off").isFalse();
    }
}
