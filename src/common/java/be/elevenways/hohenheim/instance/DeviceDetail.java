package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.zenit.common.orm.field.Field;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * THE vocabulary of the per-type details a device row carries beyond its name and type; which of them a type
 * means is the fact {@link DeviceType#details()}, so a surface listing devices draws a detail only for those.
 *
 * AIDEV-NOTE: the Devices tab once drew one "Size (GB)" column and put an install medium's ISO name in it; a column
 * per member, filled only where the row's type declares it, is what keeps a detail out of another's column.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum DeviceDetail {

    /** A disk's size in gigabytes. */
    SIZE_GB(InstanceDeviceModel.SIZE_GB),

    /** The ISO volume an install-media drive holds. */
    SOURCE_MEDIA(InstanceDeviceModel.SOURCE_MEDIA);

    private final Field<?, ?> field;

    DeviceDetail(@NonNull Field<?, ?> field) {
        this.field = field;
    }

    /** @return the device row's field holding this detail, whose label names it */
    public @NonNull Field<?, ?> field() {
        return this.field;
    }
}
