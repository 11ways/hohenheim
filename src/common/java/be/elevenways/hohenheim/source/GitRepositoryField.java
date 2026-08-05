package be.elevenways.hohenheim.source;

import be.elevenways.zenit.common.orm.field.ComparableField;
import be.elevenways.zenit.common.orm.field.StringField;

/**
 * Repository path at a git provider ({@code owner/name}, nested groups allowed):
 * a marker StringField subclass whose form entry derives to the provider-backed
 * repository picker (see {@link GitPickerFormEntries}). Declare model constants
 * as {@code StringField} (the UrlField precedent).
 */
public class GitRepositoryField extends StringField {

    protected GitRepositoryField(ComparableField.Builder<String, String, ?, ?> builder) {
        super(builder);
    }

    public static Builder builder(String name) {
        Builder builder = new Builder();
        builder.name(name);
        return builder;
    }

    public static class Builder extends StringField.Builder {

        @Override
        protected GitRepositoryField buildField() {
            return new GitRepositoryField(this);
        }
    }
}
