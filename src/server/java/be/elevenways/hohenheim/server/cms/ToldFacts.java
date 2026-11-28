package be.elevenways.hohenheim.server.cms;

import be.elevenways.hawkeye.common.customelement.AttributeToken;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.site.SiteHostnamesCell;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.refusal.StoredRefusalWords;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE wire of the facts a server tells its master as state (SharedStates): each cell and verdict written into a JSON
 * document and read back, every piece of copy in it as its own typed DRY text, so it revives as copy.
 *
 * AIDEV-NOTE: a JSON tree carries no type, and a Microcopy written into it as a map no reader could revive; so copy
 * travels as {@link Zenit#DRY} text. A fact the document does not carry reads null (its cell shows nothing); a fact it
 * carries that this build cannot read (a newer build's tone, variant or copy, damage) throws {@link Unreadable}, so
 * its reader shows the thing as unreadable, never drops it or draws half of it.
 * AIDEV-NOTE: localized content: a document holds copy, never words resolved for a reader; the master words them for
 * its own viewer. Reader-dependent facts (a certificate's name and link on an HTTPS line) are never told.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class ToldFacts {

    /** A fact a told document carries that this build cannot read. */
    static final class Unreadable extends RuntimeException {

        Unreadable(@NonNull String fact) {
            super("A told " + fact + " this build cannot read", null, false, false);
        }
    }

    private ToldFacts() {
    }

    /** @return the copy as its DRY text, null for none */
    static @Nullable String wired(@Nullable Microcopy copy) {
        return copy == null ? null : Zenit.DRY.stringify(copy);
    }

    /** @return the copy a DRY text holds, null for none; one present but unreadable is {@link Unreadable} */
    static @Nullable Microcopy readCopy(@Nullable Object value, @NonNull String fact) {
        if (value == null) {
            return null;
        }
        Microcopy copy = value instanceof String text ? StoredRefusalWords.read(text) : null;
        if (copy == null) {
            throw new Unreadable(fact);
        }
        return copy;
    }

    /** @return the tone a token names, null for one this build does not know */
    static @Nullable HealthTone toneOf(@Nullable Object token) {
        for (HealthTone tone : HealthTone.values()) {
            if (tone.token().equals(token)) {
                return tone;
            }
        }
        return null;
    }

    /** @return a state line as a told document holds it; its certificate name and link, the reader's, left out */
    static @NonNull Map<String, Object> line(@NonNull StateLineCell cell) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("state", cell.state());
        line.put("variant", cell.variant().token());
        line.put("label", wired(cell.label()));
        line.put("detail", wired(cell.detail()));
        line.put("note", wired(cell.note()));
        return line;
    }

    /** @return the state line a told value holds, null for none */
    static @Nullable StateLineCell lineOf(@Nullable Object told) {
        if (told == null) {
            return null;
        }
        if (!(told instanceof Map<?, ?> line) || !(line.get("state") instanceof String state)) {
            throw new Unreadable("state line");
        }
        BadgeVariant variant = line.get("variant") instanceof String token
            ? AttributeToken.find(BadgeVariant.values(), token) : null;
        if (variant == null) {
            throw new Unreadable("state line");
        }
        return new StateLineCell(state, variant, readCopy(line.get("label"), "label"),
            readCopy(line.get("detail"), "detail"), null, null, readCopy(line.get("note"), "note"));
    }

    /** @return a verdict as a told document holds it: its tone and words, its fixes left out */
    static @NonNull Map<String, Object> health(@NonNull RecordHealth health) {
        Map<String, Object> told = new LinkedHashMap<>();
        told.put("tone", health.tone().token());
        told.put("headline", wired(health.headline()));
        told.put("detail", wired(health.detail()));
        return told;
    }

    /** @return the verdict a told value holds, null for none; a told verdict offers no fix */
    static @Nullable RecordHealth healthOf(@Nullable Object told) {
        if (told == null) {
            return null;
        }
        if (!(told instanceof Map<?, ?> health)) {
            throw new Unreadable("verdict");
        }
        HealthTone tone = toneOf(health.get("tone"));
        Microcopy headline = readCopy(health.get("headline"), "headline");
        if (tone == null || headline == null) {
            throw new Unreadable("verdict");
        }
        return new RecordHealth(tone, headline, readCopy(health.get("detail"), "detail"), List.of(), null);
    }

    /** @return an address cell as a told document holds it */
    static @NonNull Map<String, Object> address(@NonNull SiteHostnamesCell cell) {
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("primary", cell.primary());
        address.put("more", cell.moreCount());
        return address;
    }

    /** @return the address cell a told value holds, null for none */
    static @Nullable SiteHostnamesCell addressOf(@Nullable Object told) {
        if (told == null) {
            return null;
        }
        if (!(told instanceof Map<?, ?> address)) {
            throw new Unreadable("address");
        }
        return new SiteHostnamesCell(address.get("primary") instanceof String primary ? primary : null,
            address.get("more") instanceof Number more ? more.intValue() : 0);
    }
}
