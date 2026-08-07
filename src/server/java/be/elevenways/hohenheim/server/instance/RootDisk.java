package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * The root-disk size knob, declared ONCE for every kind whose driver can actually
 * enforce it. The setting name is part of the contract: the quota hook and the
 * drivers all read {@link #SETTING} off the instance's settings map, so a kind that
 * declares the field is charged and enforced with no further wiring.
 *
 * AIDEV-NOTE: this is deliberately NOT on {@code InstanceKindHandler} as a boolean.
 * A kind that cannot enforce a root quota must not OFFER the field at all -- an
 * offered-but-ignored number is the paper limit the whole item exists to avoid --
 * and the driver-side refusal (DockerInstanceRuntime.create) is the second,
 * programmatic gate. Declaring the field IS the capability declaration.
 *
 * MEASURED on daystrom 2026-08-07, which is why only the Incus kinds declare it:
 * an Incus VM's root is a real block volume (a 6GiB declaration reads back as
 * 12582912 sectors inside the guest); an Incus system container's root is a btrfs
 * subvolume with a qgroup limit (a 2500MB write into a 2GiB root stopped at exactly
 * 2.00GiB); Docker's overlayfs on ext4 ACCEPTS {@code --storage-opt size=2G} and
 * then lets 2.5GB be written into it, enforcing nothing.
 */
public final class RootDisk {

    /** The settings key every declaring kind uses and every consumer reads. */
    public static final String SETTING = "root_disk_gb";

    private RootDisk() {
    }

    /** Add the knob to a kind's settings schema; the returned field is the kind's constant. */
    public static @NonNull IntegerField addTo(@NonNull Schema schema) {
        return schema.addField(IntegerField.builder().name(SETTING)
            .label(HohenheimFormCopy.label("root_disk_gb"))
            .help(HohenheimFormCopy.help("root_disk_gb"))
            .suffix("GB")
            .build());
    }

    /**
     * The DECLARED root size in GB, or null when the settings leave it to the image.
     *
     * A non-positive or unparseable declaration reads as null HERE; it is refused by
     * name at the write funnel ({@link InstanceRootDiskQuota}), so the refusal happens
     * once, at the surface the operator submitted, instead of once per driver.
     */
    public static @Nullable Integer declaredGb(@NonNull Map<String, Object> settings) {
        Object raw = settings.get(SETTING);
        Integer value = null;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else if (raw instanceof String text && !text.isBlank()) {
            try {
                value = Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return value != null && value > 0 ? value : null;
    }
}
