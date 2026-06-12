package be.elevenways.hohenheim.render;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Render-state for the site create/edit form: the current values plus every
 * dropdown/editor data source, so templates take one {@code form} property
 * instead of forwarding a dozen scalars through the component tree.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record SiteFormData(
    String siteName,
    String siteSlug,
    List<Map<String, Object>> siteTypes,
    Map<String, Object> settings,
    Map<String, Object> sourceSettings,
    List<Map<String, Object>> nodeVersions,
    List<Map<String, Object>> systemUsers,
    List<Map<String, String>> environmentVariables,
    List<String> apiKeys,
    List<Map<String, String>> buildEnvironmentVariables,
    List<String> servers,
    List<Map<String, Object>> authProviders,
    String authProviderId,
    List<Map<String, Object>> accessLists,
    String accessListId
) {

    public SiteFormData {
        Objects.requireNonNull(siteName, "siteName cannot be null");
        Objects.requireNonNull(siteSlug, "siteSlug cannot be null");
        Objects.requireNonNull(siteTypes, "siteTypes cannot be null");
        Objects.requireNonNull(settings, "settings cannot be null");
        Objects.requireNonNull(sourceSettings, "sourceSettings cannot be null");
        Objects.requireNonNull(nodeVersions, "nodeVersions cannot be null");
        Objects.requireNonNull(systemUsers, "systemUsers cannot be null");
        Objects.requireNonNull(environmentVariables, "environmentVariables cannot be null");
        Objects.requireNonNull(apiKeys, "apiKeys cannot be null");
        Objects.requireNonNull(buildEnvironmentVariables, "buildEnvironmentVariables cannot be null");
        Objects.requireNonNull(servers, "servers cannot be null");
        Objects.requireNonNull(authProviders, "authProviders cannot be null");
        Objects.requireNonNull(authProviderId, "authProviderId cannot be null");
        Objects.requireNonNull(accessLists, "accessLists cannot be null");
        Objects.requireNonNull(accessListId, "accessListId cannot be null");
    }
}
