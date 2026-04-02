package be.elevenways.hohenheim.sitetype;

import be.elevenways.zenit.common.orm.field.TypeDefinition;

/**
 * Common site type metadata. Lives in src/common so admin UI (client) can
 * access type information without server/Undertow dependencies.
 */
public interface SiteTypeInfo extends TypeDefinition {

    /**
     * Short description shown in the type selector UI.
     */
    String getDescription();

    /**
     * Icon identifier for the admin UI.
     */
    String getIcon();

    /**
     * Standard properties map containing description and icon.
     */
    default java.util.Map<String, Object> getProperties() {
        return java.util.Map.of("description", getDescription(), "icon", getIcon());
    }
}
