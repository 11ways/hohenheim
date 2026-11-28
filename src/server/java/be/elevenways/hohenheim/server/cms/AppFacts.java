package be.elevenways.hohenheim.server.cms;

import be.elevenways.hawkeye.common.customelement.AttributeToken;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.server.cms.AppDirectory.Source;
import be.elevenways.hohenheim.site.SiteHostnamesCell;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * THE facts of one app the Apps list draws its cells from: read from this process for an app of this server
 * ({@link AppDirectory}), from the state its server last told for an app of a linked server ({@link AppStates}).
 *
 * AIDEV-NOTE: a cell of a linked server's app is drawn by this server's own cell code from these facts alone, never
 * from a fact of this process read under the app's id (a proxy's health of a site id, a certificate store): that id
 * names another server's record. A fact the state does not carry is null and its cell shows nothing.
 * AIDEV-NOTE: localized content: the kind and the verdict and HTTPS words are copy (Microcopy, carried in its DRY
 * form), resolved for the viewer that reads the list; the name, host and address are content, never localized.
 *
 * @param id      the record's id on its own server, in the source's model
 * @param kind    what it is, in the words its own record page uses
 * @param address the address cell, null when the app has no address to show
 * @param host    the host it runs on, null without one
 * @param https   what HTTPS gives its main address, null without an exact address
 * @param health  the verdict its own record page leads with, its fixes left out of a told one
 * @param started when what runs last started, null while it does not run or for an app nothing runs of
 * @author Jelle De Loecker
 * @since  0.10.0
 */
record AppFacts(@NonNull Source source, int id, @NonNull String name, @NonNull Microcopy kind,
                @Nullable SiteHostnamesCell address, @Nullable String host, @Nullable StateLineCell https,
                @NonNull RecordHealth health, @Nullable Instant started) {

    AppFacts {
        Objects.requireNonNull(source, "source cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(kind, "kind cannot be null");
        Objects.requireNonNull(health, "health cannot be null");
    }

    /** @return the key unique over one server's Apps list: the source's token and the record's id */
    @NonNull String key() {
        return this.source.token() + "-" + this.id;
    }

    /**
     * @return the facts as the state document a linked server tells its master: JSON, each piece of copy in it as its
     *         own DRY text ({@link #wired}), so it revives as copy, a composed one with its parts ({@link #of}); a
     *         JSON tree carries no type, and a Microcopy written into it as a map no reader could revive
     */
    @NonNull String document() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("source", this.source.token());
        document.put("id", this.id);
        document.put("name", this.name);
        document.put("kind", wired(this.kind));
        if (this.address != null) {
            Map<String, Object> address = new LinkedHashMap<>();
            address.put("primary", this.address.primary());
            address.put("more", this.address.moreCount());
            document.put("address", address);
        }
        document.put("host", this.host);
        if (this.started != null) {
            document.put("started", this.started.toString());
        }
        if (this.https != null) {
            Map<String, Object> https = new LinkedHashMap<>();
            https.put("state", this.https.state());
            https.put("variant", this.https.variant().token());
            https.put("label", wired(this.https.label()));
            https.put("detail", wired(this.https.detail()));
            https.put("note", wired(this.https.note()));
            document.put("https", https);
        }
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("tone", this.health.tone().token());
        health.put("headline", wired(this.health.headline()));
        health.put("detail", wired(this.health.detail()));
        document.put("health", health);
        return Zenit.DRY.toJson(document);
    }

    /**
     * @return the facts a state document holds, null for one this build cannot read (a source, tone or variant it
     *         does not know, copy that did not revive): {@link AppStates#held} lists such an app as unreadable, never
     *         leaves it out
     */
    static @Nullable AppFacts of(@NonNull String document) {
        Microcopy kind;
        Microcopy headline;
        if (!(Zenit.DRY.parseJsonTree(document) instanceof Map<?, ?> facts)
                || !(facts.get("source") instanceof String sourceToken)
                || !(facts.get("id") instanceof Number id) || !(facts.get("name") instanceof String name)
                || (kind = copyOf(facts.get("kind"))) == null
                || !(facts.get("health") instanceof Map<?, ?> health)
                || (headline = copyOf(health.get("headline"))) == null) {
            return null;
        }
        Source source = sourceOf(sourceToken);
        HealthTone tone = toneOf(health.get("tone"));
        if (source == null || tone == null) {
            return null;
        }
        SiteHostnamesCell address = null;
        if (facts.get("address") instanceof Map<?, ?> told) {
            address = new SiteHostnamesCell(told.get("primary") instanceof String primary ? primary : null,
                told.get("more") instanceof Number more ? more.intValue() : 0);
        }
        StateLineCell https = null;
        if (facts.get("https") instanceof Map<?, ?> told) {
            BadgeVariant variant = told.get("variant") instanceof String token
                ? AttributeToken.find(BadgeVariant.values(), token) : null;
            Microcopy label = copyOf(told.get("label"));
            if (!(told.get("state") instanceof String state) || variant == null || label == null) {
                return null;
            }
            https = new StateLineCell(state, variant, label, copyOf(told.get("detail")), null, null,
                copyOf(told.get("note")));
        }
        Instant started = null;
        if (facts.get("started") instanceof String told) {
            try {
                started = Instant.parse(told);
            } catch (DateTimeParseException unreadable) {
                return null;
            }
        }
        return new AppFacts(source, id.intValue(), name, kind, address,
            facts.get("host") instanceof String host ? host : null, https,
            new RecordHealth(tone, headline, copyOf(health.get("detail")), List.of(), null), started);
    }

    /** @return the copy as its DRY text, null for none */
    private static @Nullable String wired(@Nullable Microcopy copy) {
        return copy == null ? null : Zenit.DRY.stringify(copy);
    }

    /** @return the copy a DRY text holds, null for none or for a text that holds no copy this build can read */
    private static @Nullable Microcopy copyOf(@Nullable Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return Zenit.DRY.parse(text) instanceof Microcopy copy ? copy : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static @Nullable Source sourceOf(@NonNull String token) {
        for (Source source : Source.values()) {
            if (source.token().equals(token)) {
                return source;
            }
        }
        return null;
    }

    private static @Nullable HealthTone toneOf(@Nullable Object token) {
        for (HealthTone tone : HealthTone.values()) {
            if (tone.token().equals(token)) {
                return tone;
            }
        }
        return null;
    }
}
