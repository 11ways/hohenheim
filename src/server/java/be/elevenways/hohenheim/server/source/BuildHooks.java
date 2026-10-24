package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.hohenheim.model.BuildHookModel;
import be.elevenways.hohenheim.model.DeployTrigger;
import be.elevenways.hohenheim.model.HohenheimPrincipalKind;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.DeployAttribution;
import be.elevenways.hohenheim.server.application.DeployStartGate;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.source.GitRefNames;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.operation.SecretResult;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.command.CommandPayloads;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * THE build hook verbs: mint a hook's secret URL, regenerate it, revoke it, find the live hook a POSTed token names,
 * and hold the deploys hooks queue per application.
 *
 * AIDEV-NOTE: the token is never stored. Its keyed digest under the active keyring key is ({@link #DIGEST_PURPOSE},
 * core's CommandPayloads.keyedDigests), and a lookup asks every keyring key's digest, so a key rotation keeps every URL
 * working without a stored hash of a short secret being guessable from the database alone. The URL is answered once as
 * a {@link SecretResult}, which zenit-cms shows in the create's (or regenerate's) toast and nowhere else.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class BuildHooks {

    /** The keyring purpose a token's digest is keyed under. */
    private static final String DIGEST_PURPOSE = "hohenheim.build_hook";

    /** Random bytes per token: 256 bits, spelled as 43 URL-safe characters. */
    private static final int TOKEN_BYTES = 32;

    /** How many of the token's last characters the preview keeps, enough to tell two URLs apart. */
    private static final int HINT_LENGTH = 4;

    /** The longest name a hook takes, the column's width. */
    public static final int NAME_MAX = 100;

    /** Hook deploys one application holds accepted and not yet started; a further POST is refused until one starts. */
    public static final int PENDING_PER_APPLICATION = 3;

    /**
     * The Retry-After of a POST refused by {@link #PENDING_PER_APPLICATION}.
     *
     * AIDEV-NOTE: a slot frees when a held deploy STARTS, which is when the operation holding the application's lock
     * ends; that is seconds for a refused or quick operation and minutes for a build. A minute is the grain a CI retry
     * should poll at: short enough not to strand a publish behind a finished build, long enough not to hammer a build
     * that runs for minutes.
     */
    public static final Duration PENDING_RETRY_AFTER = Duration.ofMinutes(1);

    /** The pending hook deploys per application id; an application holding none has no entry. */
    private static final ConcurrentHashMap<Integer, Integer> PENDING = new ConcurrentHashMap<>();

    private BuildHooks() {
    }

    /**
     * Creates a hook on a release-managed application.
     *
     * @return the hook's id with its URL, the only time the URL is ever answered
     * @throws Violations naming the name or the application that cannot take a hook
     */
    public static @NonNull SecretResult<UUID> mint(@Nullable Integer instanceId, @Nullable String name) {
        String trimmed = Texts.trimmedOrNull(name);
        if (trimmed == null || trimmed.length() > NAME_MAX) {
            throw Violations.ofField(BuildHookModel.NAME.getName(), name,
                HohenheimMicrocopy.VIOLATIONS.of("build_hook_name_invalid").withArg("max", NAME_MAX));
        }
        Row application = instanceId == null ? null : Models.get(InstanceModel.class).findById(instanceId);
        if (application == null || !takesHooks(application)) {
            throw Violations.ofField(BuildHookModel.INSTANCE_ID.getName(), instanceId,
                HohenheimMicrocopy.VIOLATIONS.of("build_hook_needs_application"));
        }
        BuildHookModel model = Models.get(BuildHookModel.class);
        Row hook = model.createEmptyRow();
        hook.set(BuildHookModel.INSTANCE_ID, instanceId);
        hook.set(BuildHookModel.NAME, trimmed);
        String token = stampToken(hook);
        hook = model.save(hook);
        return disclosed(hook, token);
    }

    /**
     * Replaces a live hook's token: the old URL stops answering at once, and its accepted deploys not yet started
     * are declined (HookStart); a started one completes.
     *
     * AIDEV-NOTE: one narrow write conditioned on the hook still being live, never a save of the row the caller read:
     * a revoke landing between that read and this write must win, or the stale row would revive the hook.
     *
     * @return the hook's id with its new URL, answered once
     * @throws DomainRefusal {@link HohenheimRefusalReason#BUILD_HOOK_REVOKED} for a revoked hook, which stays revoked
     */
    public static @NonNull SecretResult<UUID> regenerate(@NonNull Row hook) {
        String token = SecureTokens.randomToken(TOKEN_BYTES);
        int renewed = whileLive(hook.get(BuildHookModel.ID))
            .assign(BuildHookModel.TOKEN_DIGEST, digestOf(token))
            .assign(BuildHookModel.URL_PREVIEW, previewOf(token))
            .updateAll();
        if (renewed == 0) {
            throw revoked("regenerating");
        }
        return disclosed(hook, token);
    }

    /**
     * Revokes a live hook: its URL answers like any unknown one from now on, its accepted deploys not yet started are
     * declined (HookStart) while a started one completes; a revoked hook stays revoked.
     *
     * @throws DomainRefusal {@link HohenheimRefusalReason#BUILD_HOOK_REVOKED} when it was revoked already
     */
    public static void revoke(@NonNull Row hook) {
        if (whileLive(hook.get(BuildHookModel.ID)).assign(BuildHookModel.REVOKED_AT, Now.instant()).updateAll() == 0) {
            throw revoked("revoking");
        }
    }

    /**
     * Holds one of the application's pending hook deploys for a hook a POST named.
     *
     * @param  hook the live hook as the POST's lookup read it; its token digest is the one the deploy is accepted under
     * @return the gate the deploy asks under the application's lock, or null when the application already holds
     *         {@link #PENDING_PER_APPLICATION} hook deploys accepted and not yet started
     */
    public static @Nullable DeployStartGate admit(@NonNull Row hook) {
        int applicationId = hook.get(BuildHookModel.INSTANCE_ID);
        boolean[] held = {false};
        PENDING.compute(applicationId, (key, pending) -> {
            int now = pending == null ? 0 : pending;
            if (now >= PENDING_PER_APPLICATION) {
                return pending;
            }
            held[0] = true;
            return now + 1;
        });
        return held[0] ? new HookStart(hook) : null;
    }

    /** @return how many hook deploys the application holds accepted and not yet started */
    public static int pendingOf(int applicationId) {
        return PENDING.getOrDefault(applicationId, 0);
    }

    /**
     * The live hook a POSTed token names.
     *
     * @return null for an unknown or revoked token, and for a hook whose application is gone or no longer deploys
     *         through the release engine: every one of them answers the same
     */
    public static @Nullable Row live(@NonNull String token) {
        Row hook = Models.get(BuildHookModel.class).find()
            .where(BuildHookModel.TOKEN_DIGEST.in(CommandPayloads.keyedDigests(DIGEST_PURPOSE, token)))
            .where(BuildHookModel.REVOKED_AT.isNull())
            .first();
        if (hook == null) {
            return null;
        }
        Row application = Models.get(InstanceModel.class).findById(hook.get(BuildHookModel.INSTANCE_ID));
        return application != null && takesHooks(application) ? hook : null;
    }

    /** @return the narrow write of this hook while it is live, to stage assignments on */
    private static @NonNull QueryBuilder<Row> whileLive(@NonNull UUID hookId) {
        return Models.get(BuildHookModel.class).find()
            .where(BuildHookModel.ID.eq(hookId))
            .where(BuildHookModel.REVOKED_AT.isNull());
    }

    private static @NonNull DomainRefusal revoked(@NonNull String verb) {
        return new DomainRefusal(HohenheimRefusalReason.BUILD_HOOK_REVOKED, verb + " a build hook that is revoked");
    }

    /** @return the attribution of a deploy this hook started: the hook trigger, the hook as its principal */
    public static @NonNull DeployAttribution attribution(@NonNull Row hook) {
        return new DeployAttribution(DeployTrigger.HOOK, principalOf(hook));
    }

    /** @return the principal reference naming this hook on what it started */
    public static @NonNull PrincipalRef principalOf(@NonNull Row hook) {
        return new PrincipalRef(HohenheimPrincipalKind.BUILD_HOOK, hook.get(BuildHookModel.ID));
    }

    /**
     * Whether a hook may ask for this branch: the application's source declares exactly this branch. An application
     * without a repository takes no branch at all.
     */
    public static boolean allowsBranch(@NonNull Row application, @NonNull String branch) {
        Map<String, Object> settings = ApplicationReleases.storedSettings(application);
        return SiteSources.hasRepository(settings) && GitRefNames.isValid(branch)
            && branch.equals(GitSourceSchema.declaredBranch(settings));
    }

    /** @return whether the record deploys through the release engine, the one lane a hook starts */
    public static boolean takesHooks(@NonNull Row instance) {
        InstanceKindHandler kind = InstanceKinds.handlerOf(instance);
        return kind != null && kind.releaseManaged();
    }

    /** Stages a fresh token's digest and preview on the hook and answers the token. */
    private static @NonNull String stampToken(@NonNull Row hook) {
        String token = SecureTokens.randomToken(TOKEN_BYTES);
        hook.set(BuildHookModel.TOKEN_DIGEST, digestOf(token));
        hook.set(BuildHookModel.URL_PREVIEW, previewOf(token));
        return token;
    }

    /** @return the token's keyed digest under the active keyring key, the one a hook stores */
    private static @NonNull String digestOf(@NonNull String token) {
        return CommandPayloads.keyedDigests(DIGEST_PURPOSE, token).getFirst();
    }

    /** @return what the list shows of the URL: its path with only the token's last characters */
    private static @NonNull String previewOf(@NonNull String token) {
        return BuildHookHandler.PREFIX + "****" + token.substring(token.length() - HINT_LENGTH);
    }

    /** Lets one pending application slot go, once. */
    private static void release(int applicationId) {
        PENDING.computeIfPresent(applicationId, (key, pending) -> pending <= 1 ? null : pending - 1);
    }

    private static @NonNull SecretResult<UUID> disclosed(@NonNull Row hook, @NonNull String token) {
        return new SecretResult<>(hook.get(BuildHookModel.ID),
            Secret.of(HookUrls.forInstance(hook.get(BuildHookModel.INSTANCE_ID), BuildHookHandler.PREFIX + token)));
    }

    /**
     * A hook deploy accepted and not yet started: it holds one pending slot of its application until it starts or
     * ends without starting.
     *
     * AIDEV-NOTE: the liveness re-check and the "last triggered" stamp are ONE narrow write conditioned on the hook
     * being live AND still carrying the token digest the POST was accepted under, so a revoke or a regenerate (the
     * rotation of a leaked URL) landing after the POST was accepted declines the deploy and stamps nothing. A key
     * rotation never rewrites the stored digest, so only a regenerate changes it.
     */
    private static final class HookStart implements DeployStartGate {

        private final @NonNull UUID hookId;
        private final @NonNull String admittedDigest;
        private final int applicationId;
        private final AtomicBoolean held = new AtomicBoolean(true);

        private HookStart(@NonNull Row hook) {
            this.hookId = hook.get(BuildHookModel.ID);
            this.admittedDigest = hook.get(BuildHookModel.TOKEN_DIGEST);
            this.applicationId = hook.get(BuildHookModel.INSTANCE_ID);
        }

        @Override
        public @Nullable Microcopy declineToStart() {
            this.settled();
            int stamped = whileLive(this.hookId)
                .where(BuildHookModel.TOKEN_DIGEST.eq(this.admittedDigest))
                .assign(BuildHookModel.LAST_TRIGGERED_AT, Now.instant())
                .keepingUpdatedAt()
                .updateAll();
            if (stamped > 0) {
                return null;
            }
            Row now = Models.get(BuildHookModel.class).findById(this.hookId);
            if (now == null || now.get(BuildHookModel.REVOKED_AT) != null) {
                return HohenheimMicrocopy.VIOLATIONS.of("build_hook_revoked_before_start");
            }
            return HohenheimMicrocopy.VIOLATIONS.of("build_hook_regenerated_before_start");
        }

        @Override
        public void settled() {
            if (this.held.compareAndSet(true, false)) {
                release(this.applicationId);
            }
        }
    }
}
