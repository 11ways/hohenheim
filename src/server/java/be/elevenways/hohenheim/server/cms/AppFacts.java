package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.server.cms.AppDirectory.Source;
import be.elevenways.hohenheim.site.SiteHostnamesCell;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.common.Zenit;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
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
     * @return the facts as the state document a linked server tells its master: JSON, through the one wire of told
     *         facts ({@link ToldFacts}), so its copy revives as copy, a composed one with its parts ({@link #of})
     */
    @NonNull String document() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("source", this.source.token());
        document.put("id", this.id);
        document.put("name", this.name);
        document.put("kind", ToldFacts.wired(this.kind));
        if (this.address != null) {
            document.put("address", ToldFacts.address(this.address));
        }
        document.put("host", this.host);
        if (this.started != null) {
            document.put("started", this.started.toString());
        }
        if (this.https != null) {
            document.put("https", ToldFacts.line(this.https));
        }
        document.put("health", ToldFacts.health(this.health));
        return Zenit.DRY.toJson(document);
    }

    /**
     * @return the facts a state document holds, null for one this build cannot read (a source, tone or variant it
     *         does not know, copy that did not revive): {@link AppStates#held} lists such an app as unreadable, never
     *         leaves it out
     */
    static @Nullable AppFacts of(@NonNull String document) {
        try {
            if (!(Zenit.DRY.parseJsonTree(document) instanceof Map<?, ?> facts)
                    || !(facts.get("source") instanceof String sourceToken)
                    || !(facts.get("id") instanceof Number id) || !(facts.get("name") instanceof String name)) {
                return null;
            }
            Microcopy kind = ToldFacts.readCopy(facts.get("kind"), "kind");
            RecordHealth health = ToldFacts.healthOf(facts.get("health"));
            StateLineCell https = ToldFacts.lineOf(facts.get("https"));
            Source source = sourceOf(sourceToken);
            if (source == null || kind == null || health == null || (https != null && https.label() == null)) {
                return null;
            }
            Instant started = facts.get("started") instanceof String told ? Instant.parse(told) : null;
            return new AppFacts(source, id.intValue(), name, kind, ToldFacts.addressOf(facts.get("address")),
                facts.get("host") instanceof String host ? host : null, https, health, started);
        } catch (ToldFacts.Unreadable | DateTimeParseException unreadable) {
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
}
