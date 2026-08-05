package be.elevenways.hohenheim.source;

import be.elevenways.zenit.common.orm.field.ComparableField;
import be.elevenways.zenit.common.orm.field.StringField;

/**
 * Branch name in a provider-bound repository: a marker StringField subclass whose
 * form entry derives to the provider-backed branch picker (see
 * {@link GitPickerFormEntries}). Declare model constants as {@code StringField}.
 */
public class GitBranchField extends StringField {

    protected GitBranchField(ComparableField.Builder<String, String, ?, ?> builder) {
        super(builder);
    }

    public static Builder builder(String name) {
        Builder builder = new Builder();
        builder.name(name);
        return builder;
    }

    public static class Builder extends StringField.Builder {

        @Override
        protected GitBranchField buildField() {
            return new GitBranchField(this);
        }
    }
}
