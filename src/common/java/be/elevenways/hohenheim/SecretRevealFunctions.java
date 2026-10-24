package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.Arg;
import be.elevenways.hawkeye.common.annotation.HawkeyeFunction;
import be.elevenways.hawkeye.common.render.RenderContext;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * The template face of the stored-secret reveal (namespace {@code SecretReveal}), the one fetch {@code hh-secret-reveal}
 * runs per press.
 *
 * A refusal answers null rather than throwing, so the control draws its own error line; the log names the secret and
 * the record, never a value.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class SecretRevealFunctions {

    private SecretRevealFunctions() {
    }

    /**
     * @return the stored value, empty when none is stored; null on the server and for any refused or failed fetch
     */
    @HawkeyeFunction(
        name = "reveal",
        namespace = "SecretReveal",
        description = "Fetch one record's stored secret from the server, recorded as a reveal",
        returnType = String.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "secret", required = true, type = String.class, expectsReference = false,
                 description = "The StoredSecret token naming which secret"),
            @Arg(name = "recordId", required = true, type = Integer.class, expectsReference = false,
                 description = "The record holding it")
        }
    )
    public static @Nullable String reveal(RenderContext context, @Nullable String secret, @Nullable Integer recordId) {
        if (!Blast.IS_TEAVM || secret == null || recordId == null) {
            return null;
        }
        try {
            return HohenheimEndpoints.SECRETS_REVEAL.call(null, Map.of(HohenheimEndpoints.SECRET, secret,
                HohenheimEndpoints.SECRET_RECORD_ID, recordId));
        } catch (RuntimeException refused) {
            Blast.log("SECRET: reveal of", secret, "on record", recordId, "failed -", refused.getMessage());
            return null;
        }
    }
}
