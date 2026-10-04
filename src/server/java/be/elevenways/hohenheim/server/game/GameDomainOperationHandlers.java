package be.elevenways.hohenheim.server.game;

import be.elevenways.hohenheim.game.GameDomainOperations;
import be.elevenways.hohenheim.game.GameDomainOperations.MappingForm;
import be.elevenways.hohenheim.model.GameDomainModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.OperationCall;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * The handlers of the game-domain mapping writes, attached once per JVM: each one is the {@link GameDomains} funnel.
 *
 * AIDEV-NOTE: the update stamps the reviewed lock version on the loaded mapping, so the funnel's save is the guarded
 * UPDATE and a concurrent edit surfaces as core's STALE refusal. {@link #init()} only forces the class to load before
 * boot verifies every operation has its handler.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class GameDomainOperationHandlers {

    static {
        OperationHandlers.attach(GameDomainOperations.CREATE).handle(call -> {
            Row mapping = Models.get(GameDomainModel.class).createEmptyRow();
            assign(mapping, Objects.requireNonNull(call.input(), "the mapping form is the input"));
            GameDomains.applyAuthorized(access(call), mapping);
            return mapping.get(GameDomainModel.ID);
        });
        OperationHandlers.attach(GameDomainOperations.UPDATE).handle(call -> {
            Row mapping = call.subject();
            assign(mapping, Objects.requireNonNull(call.input(), "the mapping form is the input"));
            if (call.expectedVersion() != null) {
                mapping.set(GameDomainModel.VERSION, Math.toIntExact(call.expectedVersion()));
            }
            GameDomains.applyAuthorized(access(call), mapping);
            return null;
        });
        OperationHandlers.attach(GameDomainOperations.DELETE).handle(call -> {
            GameDomains.deleteAuthorized(access(call), call.subject().get(GameDomainModel.ID));
            return null;
        });
    }

    private GameDomainOperationHandlers() {
    }

    /** Loads the class, attaching the handlers; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }

    private static void assign(@NonNull Row mapping, @NonNull MappingForm input) {
        mapping.set(GameDomainModel.SITE_DOMAIN_ID, key(input.site_domain_id()));
        mapping.set(GameDomainModel.BACKEND_INSTANCE_ID, key(input.backend_instance_id()));
        mapping.set(GameDomainModel.PROXY_INSTANCE_ID, key(input.proxy_instance_id()));
        mapping.set(GameDomainModel.BACKEND_PORT, input.backend_port());
        mapping.set(GameDomainModel.ENABLED, input.enabled());
    }

    /** A picked relation's coerced key; anything else is left for the funnel to refuse as missing. */
    private static @Nullable Integer key(@Nullable Object value) {
        return value instanceof Integer id ? id : null;
    }

    private static @NonNull AccessContext access(@NonNull OperationCall<?, ?> call) {
        return Objects.requireNonNull(call.access(), "a mapping write is a person's, never the system's");
    }
}
