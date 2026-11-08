package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers an app's push webhook at the git provider its source is bound to, so pushes deploy without anyone
 * copying a URL and a secret into the forge by hand.
 *
 * AIDEV-NOTE: the webhook delivers to the address the Deploys tab shows ({@link HookUrls}), which is https or
 * nothing, and is signed with the app's own webhook secret; one is generated and stored on first use. Registering
 * twice updates the one webhook to that address ({@link GitProviderClient#ensurePushWebhook}), so it is also how a
 * rotated secret reaches the forge. A source cloned by URL or over SSH has no API to register through: the offer
 * says so instead of failing on press.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class GitWebhookProvisioning {

    private GitWebhookProvisioning() {
    }

    /** @return whether the instance's git source is bound to a provider record, where a webhook could be registered */
    public static boolean applies(@NonNull Row instance) {
        Map<String, Object> settings = ApplicationReleases.storedSettings(instance);
        return settings.get(GitSourceSchema.PROVIDER_ID) != null
            && GitWebhookHandler.boundRepositoryOf(settings) != null;
    }

    /** @return why the webhook cannot be registered now, null when it can */
    public static @Nullable Microcopy refusal(@NonNull Row instance) {
        Row provider = providerOf(instance);
        if (provider == null) {
            return HohenheimMicrocopy.VIOLATIONS.of("webhook_no_provider");
        }
        try {
            if (!GitProviders.clientFor(provider).registersWebhooks()) {
                return HohenheimMicrocopy.VIOLATIONS.of("webhook_provider_cannot_register");
            }
        } catch (RuntimeException unusable) {
            return HohenheimMicrocopy.VIOLATIONS.of("webhook_provider_unusable");
        }
        if (!payloadUrlOf(instance).startsWith("https://")) {
            return HohenheimMicrocopy.VIOLATIONS.of("webhook_needs_https_address");
        }
        return null;
    }

    /**
     * Registers (or brings up to date) the push webhook of the instance's bound repository.
     *
     * @return whether the webhook was created, rather than updated
     * @throws Violations naming why when it cannot be registered, or the provider's own reason when it refused
     */
    public static boolean connect(int instanceId) {
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        if (instance == null) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("webhook_no_provider"));
        }
        Microcopy refused = refusal(instance);
        if (refused != null) {
            throw Violations.ofForm(refused);
        }
        Row provider = providerOf(instance);
        Map<String, Object> settings = ApplicationReleases.storedSettings(instance);
        String repository = GitWebhookHandler.boundRepositoryOf(settings);
        String secret = secretOf(instance);
        try {
            return GitProviders.clientFor(provider).ensurePushWebhook(repository, payloadUrlOf(instance), secret)
                == GitProviderClient.HookRegistration.CREATED;
        } catch (IOException | RuntimeException failed) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("webhook_provider_refused")
                .withArg("reason", String.valueOf(HohenheimViolations.reasonOf(failed))));
        }
    }

    /** @return the address the webhook delivers to; the Deploys tab shows the same one */
    public static @NonNull String payloadUrlOf(int instanceId) {
        return HookUrls.forInstance(instanceId, GitWebhookHandler.PREFIX + instanceId);
    }

    private static @NonNull String payloadUrlOf(@NonNull Row instance) {
        return payloadUrlOf((int) instance.get(InstanceModel.ID));
    }

    /**
     * The instance's webhook secret, generated and stored when it has none.
     *
     * AIDEV-NOTE: read again and written under one lock, so two first presses at once store one secret, never one
     * each with the forge holding the other. The secret is a source setting, so a release-managed app that had none
     * reads as "redeploy to apply" afterwards, as after any edit of its delivery settings.
     *
     * @return the stored secret
     */
    private static synchronized @NonNull String secretOf(@NonNull Row asked) {
        Row instance = Models.get(InstanceModel.class).findById((Integer) asked.get(InstanceModel.ID));
        Object stored = instance == null ? null
            : ApplicationReleases.storedSettings(instance).get(GitSourceSchema.WEBHOOK_SECRET);
        if (instance == null || stored != null && !String.valueOf(stored).isBlank()) {
            return String.valueOf(stored);
        }
        String secret = SecureTokens.randomToken(32);
        Map<String, Object> settings = new LinkedHashMap<>(InstanceModel.settingsOf(instance));
        settings.put(GitSourceSchema.WEBHOOK_SECRET, secret);
        instance.set(InstanceModel.SETTINGS, settings);
        InstanceModel.saveConfiguration(instance);
        return secret;
    }

    private static @Nullable Row providerOf(@NonNull Row instance) {
        Object id = ApplicationReleases.storedSettings(instance).get(GitSourceSchema.PROVIDER_ID);
        Integer providerId = id instanceof Number number ? Integer.valueOf(number.intValue())
            : id != null && String.valueOf(id).matches("\\d+") ? Integer.valueOf(String.valueOf(id)) : null;
        return providerId == null ? null : Models.get(GitProviderModel.class).findById(providerId);
    }
}
