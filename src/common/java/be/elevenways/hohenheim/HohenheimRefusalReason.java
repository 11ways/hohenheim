package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.DomainRefusals;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The refusals Hohenheim's instance tier answers in its own words: the uniform "not permitted" that never names the
 * missing capability, and a dependency that is not ready yet.
 *
 * AIDEV-NOTE: each {@link #code()} is today's {@code /api/v1} wire code, the violation key {@code ApiConduits.refusal}
 * writes, and each message is the same violations copy the service gates throw, so the API answers a refusal of the
 * operation pipeline byte-identical to the service's {@code Violations} of the same key.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@BlastAutoLoad
public enum HohenheimRefusalReason implements DomainRefusal.Reason {

    /** The caller lacks the instance capability; the same answer as for an instance it cannot see. */
    INSTANCE_NOT_PERMITTED("instance_not_permitted", false,
        "You are not allowed to perform this action on this instance"),

    /** An attached managed database is not active yet; deploying now would start the workload without it. */
    DATABASE_NOT_READY("database_not_ready", true,
        "A database this instance uses is not ready yet");

    static {
        DomainRefusals.declare(values());
    }

    private final @NonNull Identifier id;
    private final @NonNull String code;
    private final boolean retriable;
    private final @NonNull Microcopy message;

    HohenheimRefusalReason(@NonNull String key, boolean retriable, @NonNull String fallback) {
        this.id = HohenheimIds.id(key);
        this.code = key;
        this.retriable = retriable;
        this.message = Microcopy.of(key).withFilter("scope", "violations").withFallback(fallback);
    }

    @Override
    public @NonNull Identifier id() {
        return this.id;
    }

    /** @return 422, the status {@code /api/v1} answers every instance-tier refusal with */
    @Override
    public int status() {
        return 422;
    }

    @Override
    public @NonNull String code() {
        return this.code;
    }

    @Override
    public @NonNull Microcopy message() {
        return this.message;
    }

    @Override
    public boolean retriable() {
        return this.retriable;
    }
}
