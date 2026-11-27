package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.zenit.common.text.Texts;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A signed-link rule's secrets as stored in its data: the current one and, after a replacement, the old one with its
 * end; the one reader and writer of {@link AccessRuleModel#SIGNED_LINK_DATA_SCHEMA}'s shape.
 *
 * @param current the secret new links are signed with, null on a rule that holds none
 * @param old     the secret it replaced, null when none works any more or it was ended
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public record SignedLinkKeys(@Nullable Made current, @Nullable Made old) {

    /**
     * One secret and who made it when.
     *
     * @param secret its text
     * @param madeAt when it was made, null when unknown
     * @param madeBy the account that made it, null when unknown
     * @param until  when it stops working, null for the current one
     */
    public record Made(@NonNull String secret, @Nullable Instant madeAt, @Nullable UUID madeBy,
                       @Nullable Instant until) {

        /** @return its last four characters, how the page names it */
        public @NonNull String endsIn() {
            return this.secret.substring(Math.max(0, this.secret.length() - 4));
        }

        @Override
        public @NonNull String toString() {
            return "Made[secret withheld, made " + this.madeAt + ", until " + this.until + "]";
        }
    }

    /** @return the secrets a rule's stored data holds */
    public static @NonNull SignedLinkKeys of(@NonNull Map<String, Object> data) {
        return new SignedLinkKeys(
            made(data, AccessRuleModel.SIGNED_LINK_SECRET.getName(), AccessRuleModel.SIGNED_LINK_MADE_AT.getName(),
                AccessRuleModel.SIGNED_LINK_MADE_BY.getName(), null),
            made(data, AccessRuleModel.SIGNED_LINK_OLD_SECRET.getName(),
                AccessRuleModel.SIGNED_LINK_OLD_MADE_AT.getName(), AccessRuleModel.SIGNED_LINK_OLD_MADE_BY.getName(),
                AccessRuleModel.SIGNED_LINK_OLD_UNTIL.getName()));
    }

    /** @return the data a rule stores for these secrets */
    public @NonNull Map<String, Object> toData() {
        Map<String, Object> data = new LinkedHashMap<>();
        put(data, this.current, AccessRuleModel.SIGNED_LINK_SECRET.getName(),
            AccessRuleModel.SIGNED_LINK_MADE_AT.getName(), AccessRuleModel.SIGNED_LINK_MADE_BY.getName(), null);
        put(data, this.old, AccessRuleModel.SIGNED_LINK_OLD_SECRET.getName(),
            AccessRuleModel.SIGNED_LINK_OLD_MADE_AT.getName(), AccessRuleModel.SIGNED_LINK_OLD_MADE_BY.getName(),
            AccessRuleModel.SIGNED_LINK_OLD_UNTIL.getName());
        return data;
    }

    /** @return the old secret while it still works at this moment, else null */
    public @Nullable Made oldInForce(@NonNull Instant now) {
        return this.old != null && this.old.until() != null && now.isBefore(this.old.until()) ? this.old : null;
    }

    /** @return the keys the proxy checks a request against: the current one, and the old one until its end */
    public @NonNull List<SignedLink.Key> keys() {
        List<SignedLink.Key> keys = new ArrayList<>();
        if (this.current != null) keys.add(new SignedLink.Key(this.current.secret(), null));
        if (this.old != null && this.old.until() != null) keys.add(new SignedLink.Key(this.old.secret(),
            this.old.until()));
        return keys;
    }

    private static @Nullable Made made(@NonNull Map<String, Object> data, @NonNull String secretKey,
                                       @NonNull String madeAtKey, @NonNull String madeByKey,
                                       @Nullable String untilKey) {
        String secret = Texts.trimmedOrNull(data.get(secretKey));
        if (secret == null) return null;
        return new Made(secret, instant(data.get(madeAtKey)), account(data.get(madeByKey)),
            untilKey == null ? null : instant(data.get(untilKey)));
    }

    private static void put(@NonNull Map<String, Object> data, @Nullable Made made, @NonNull String secretKey,
                            @NonNull String madeAtKey, @NonNull String madeByKey, @Nullable String untilKey) {
        if (made == null) return;
        data.put(secretKey, made.secret());
        if (made.madeAt() != null) data.put(madeAtKey, made.madeAt().toString());
        if (made.madeBy() != null) data.put(madeByKey, made.madeBy().toString());
        if (untilKey != null && made.until() != null) data.put(untilKey, made.until().toString());
    }

    /** @return the stored instant, null for none or one that no longer parses (an old secret then ends at once) */
    private static @Nullable Instant instant(@Nullable Object stored) {
        String text = Texts.trimmedOrNull(stored);
        if (text == null) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeException unreadable) {
            return null;
        }
    }

    private static @Nullable UUID account(@Nullable Object stored) {
        String text = Texts.trimmedOrNull(stored);
        if (text == null) return null;
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }
}
