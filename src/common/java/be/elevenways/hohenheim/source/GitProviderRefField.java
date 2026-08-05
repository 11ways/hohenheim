package be.elevenways.hohenheim.source;

import be.elevenways.zenit.common.orm.field.IntegerField;

/**
 * Reference to a {@code GitProviderModel} row from inside a JSON sub-schema
 * (where no BelongsTo relation can exist): a marker IntegerField subclass whose
 * form entry derives to a select over the registered providers (see
 * {@link GitPickerFormEntries}). Declare model constants as {@code IntegerField}.
 */
public class GitProviderRefField extends IntegerField {

    protected GitProviderRefField(Builder builder) {
        super(builder);
    }

    public static Builder builder(String name) {
        Builder builder = new Builder();
        builder.name(name);
        return builder;
    }

    public static class Builder extends IntegerField.Builder {

        @Override
        protected GitProviderRefField buildField() {
            return new GitProviderRefField(this);
        }
    }
}
