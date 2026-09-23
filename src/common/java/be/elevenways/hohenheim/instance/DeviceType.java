package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE vocabulary of an instance device row's {@code type}, with what each member charges
 * and how the daemon treats it as facts on the member.
 *
 * AIDEV-NOTE: the stored tokens stay the {@link InstanceDeviceModel} constants (the
 * column's declared enum values), and {@code DeviceTypeVocabularyDriftTest} binds the two
 * sets. Every reader that used to spell {@code TYPE_DISK.equals(...) ? ... : nic} read an
 * unknown token as a NIC: reconcile ensured a NIC for it, the quota's delete released a
 * NIC slot nobody had charged. A reader now switches exhaustively over a member, and a
 * token that is no member is refused by {@link #require}.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum DeviceType {

    /** An extra block device backed by a daemon volume, charged in gigabytes. */
    DISK(InstanceDeviceModel.TYPE_DISK, QuotaCharge.DISK_GIGABYTES, true, false),

    /** An extra network interface, charged one NIC slot. */
    NIC(InstanceDeviceModel.TYPE_NIC, QuotaCharge.NIC_SLOT, false, false),

    /** Operator-published install media; charges nothing and owns no volume. */
    CDROM(InstanceDeviceModel.TYPE_CDROM, QuotaCharge.NONE, false, true);

    /** The owner-quota bucket a device of a type is charged to. */
    public enum QuotaCharge {

        /** The owner's disk bucket, by the row's size in GB. */
        DISK_GIGABYTES,

        /** The owner's extra-NIC bucket, one slot per row. */
        NIC_SLOT,

        /** Nothing: shared operator state, not tenant capacity. */
        NONE
    }

    private final String token;
    private final QuotaCharge charge;
    private final boolean ownsVolume;
    private final boolean operatorOnly;

    DeviceType(@NonNull String token, @NonNull QuotaCharge charge, boolean ownsVolume,
               boolean operatorOnly) {
        this.token = token;
        this.charge = charge;
        this.ownsVolume = ownsVolume;
        this.operatorOnly = operatorOnly;
    }

    /** @return the stored column value */
    public @NonNull String token() {
        return this.token;
    }

    /** @return the quota bucket a row of this type is charged to */
    public @NonNull QuotaCharge charge() {
        return this.charge;
    }

    /** @return whether the device is backed by a daemon volume that detach and destroy delete */
    public boolean ownsVolume() {
        return this.ownsVolume;
    }

    /** @return whether attaching or detaching it is an operator act no delegation reaches */
    public boolean operatorOnly() {
        return this.operatorOnly;
    }

    /** @return the member stored as {@code token}, or null when it is no member */
    public static @Nullable DeviceType parse(@Nullable Object token) {
        if (token == null) {
            return null;
        }
        String text = token.toString();
        for (DeviceType type : values()) {
            if (type.token.equals(text)) {
                return type;
            }
        }
        return null;
    }

    /**
     * @return the member stored as {@code token}
     * @throws Violations {@code device_type_unknown} when it is no member
     */
    public static @NonNull DeviceType require(@Nullable Object token) {
        DeviceType type = parse(token);
        if (type == null) {
            throw Violations.ofField("type", token == null ? null : token.toString(),
                Microcopy.of("device_type_unknown").withFilter("scope", "violations")
                    .withArg("type", String.valueOf(token)));
        }
        return type;
    }
}
