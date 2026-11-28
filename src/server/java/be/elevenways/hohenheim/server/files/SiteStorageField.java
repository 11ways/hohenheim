package be.elevenways.hohenheim.server.files;

import be.elevenways.zenit.common.orm.field.StringField;

/**
 * A site's "Files kept in": the name of the storage its own files live in, drawn as the picker of the storages the
 * person editing may use ({@link SitePicks}).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class SiteStorageField extends StringField {

    protected SiteStorageField(Builder builder) {
        super(builder);
    }

    public static Builder builder(String name) {
        Builder builder = new Builder();
        builder.name(name);
        return builder;
    }

    public static class Builder extends StringField.Builder {

        @Override
        protected SiteStorageField buildField() {
            return new SiteStorageField(this);
        }
    }
}
