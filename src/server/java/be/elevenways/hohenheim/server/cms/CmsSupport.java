package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.routing.RouteLocales;
import be.elevenways.zenit.common.routing.RouteScope;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared helpers for the CMS resources: proxy reload and coerced-map copies.
 * Record-tab strips come from the framework (RecordScopedPage.recordTabs);
 * mutation-driven reloads ride {@code ProxyReloadHooks}.
 *
 * AIDEV-NOTE: this used to state "audit writes ride the framework activity log" as an
 * invariant. It is a CONVENTION, not a guarantee: an action whose whole effect is a
 * {@code updateAll()} or a daemon call writes no activity row at all, however it is
 * wrapped (ActivityLog.withAction only RENAMES rows the model hooks already write).
 * An action that must be accountable records itself explicitly.
 */
public final class CmsSupport {

    /**
     * The framework activity record source's token, as {@code ActivitySources} derives it
     * from {@code ActivityModel}. One home here because three surfaces name it (the admin
     * dashboard feed plus the per-record bands on the instance and server overviews) and a
     * typo in any of them degrades to an empty widget with only a log line to show for it.
     */
    public static final String ACTIVITY_SOURCE = "zenit.activity";

    // AIDEV-NOTE: the small operator-declared sets (hosts, DNS peers, git providers, access
    // lists, auth providers, backup targets) are plain ListChrome.MINIMAL, NOT a fourth
    // constant that also drops search. Search on those lists is a DELIBERATE declaration --
    // AdminListPresentationTest pins each of them by slug, and finding a host by the address
    // that renders under its name is the question that wave existed to answer. MINIMAL
    // already removes the three knobs a handful of rows cannot justify.

    /**
     * A high-volume table wide enough that an operator wants to choose columns, but whose
     * questions are answered by the per-column filter row rather than a saved rule tree.
     */
    public static final ListChrome WIDE_LIST = ListChrome.MINIMAL.withColumnPicker(true);

    /**
     * High volume AND a real filter vocabulary: the advanced builder is the only way to ask
     * the question ("every AAAA record under this zone whose value is not in this range").
     */
    public static final ListChrome FILTERABLE_LIST = ListChrome.MINIMAL.withAdvancedFilter(true);

    private CmsSupport() {
    }

    /** Rebuild the proxy routing table from the current configuration. */
    public static void reloadProxy() {
        var proxy = ServerMain.getProxyServer();
        if (proxy != null) {
            proxy.reload();
        }
    }

    /** The coerced maps the CMS hands to persist/update are immutable; copy before staging values. */
    public static @NonNull Map<String, Object> mutable(@NonNull Map<String, Object> coerced) {
        return new LinkedHashMap<>(coerced);
    }

    /**
     * The value a write carries for one column, or the STORED one when it carries none.
     *
     * AIDEV-NOTE: THE partial-write read. The inline cell lane hands persist/updateRow a map
     * holding EXACTLY ONE entry (and an IMMUTABLE one), so absence means LEAVE ALONE
     * everywhere in this pipeline. A validator or normalizer that reads a sibling straight
     * off the coerced map either refuses a field the operator never touched or -- worse --
     * writes a blank over stored data. containsKey, never a null check: a submitted null is
     * a deliberate clear and must not silently resurrect the stored value.
     *
     * @param existing the stored row, or null on a create (where absence really is blank)
     */
    public static @Nullable Object valueOf(@NonNull Map<String, Object> coerced,
                                           @Nullable Row existing, @NonNull Field<?, ?> field) {
        if (coerced.containsKey(field.getName())) {
            return coerced.get(field.getName());
        }
        return existing != null ? existing.get(field) : null;
    }

    // AIDEV-NOTE: there is deliberately NO valueOf(coerced, String, Object) overload for
    // record types that are not a Row: that shape is exactly Map.getOrDefault (a present
    // null value reads as null in both, so the deliberate-clear rule above holds), and
    // getOrDefault is the framework's own documented sibling-read spelling. The Row form
    // stays because a Row keyed by Field cannot express it.

    /** {@link #valueOf} as a trimmed string; absent, null and blank all read as {@code ""}. */
    public static @NonNull String textOf(@NonNull Map<String, Object> coerced,
                                         @Nullable Row existing, @NonNull Field<?, ?> field) {
        Object value = valueOf(coerced, existing, field);
        return value != null ? String.valueOf(value).trim() : "";
    }

    /** {@link #valueOf} as an Integer; anything that is not one reads as null. */
    public static @Nullable Integer intOf(@NonNull Map<String, Object> coerced,
                                          @Nullable Row existing, @NonNull Field<?, ?> field) {
        return valueOf(coerced, existing, field) instanceof Integer value ? value : null;
    }

    /**
     * THE resolution of a microcopy outside a template, in the CURRENT request's locale
     * chain -- the spelling every {@code recordTitle} and rendered cell in this package
     * shares, because those hooks take no conduit and reading the request scope is the
     * framework's own answer to that.
     *
     * @return the resolved text, or null when no request is in scope (a task thread, a
     *         test calling the hook directly), which every caller renders as "no title"
     *         rather than as a raw key
     */
    public static @Nullable String resolvedText(@NonNull Microcopy copy) {
        Conduit conduit = RouteScope.currentConduit();
        return conduit == null ? null : copy.resolve(conduit.getLocales(), conduit.getMessageResolver());
    }

    /**
     * {@link #resolvedText} that always answers, falling back to the SERVER default locale
     * when no request is in scope -- for a value computed on a hook (a form-value snapshot,
     * a derived cell) that a task thread or a direct test call also reaches.
     *
     * @return the resolved text, or the raw key when no runtime is booted at all
     */
    public static @NonNull String resolvedTextOrDefault(@NonNull Microcopy copy) {
        String resolved = resolvedText(copy);
        if (resolved != null) {
            return resolved;
        }
        try {
            return copy.resolve(LocaleChain.of(RouteLocales.get().getDefaultLocale()),
                Zenit.getMessageResolver());
        } catch (RuntimeException unbooted) {
            return copy.key();
        }
    }

    /**
     * The human label of ONE enum value, read off the value's own declaration -- never a
     * second switch over the token, and never the raw token where a label is declared.
     *
     * @return the resolved label, or the raw token when the value declares none, is not
     *         part of the vocabulary, or no request is in scope
     */
    public static @Nullable String enumLabel(@NonNull EnumField field, @Nullable Object token) {
        if (token == null) {
            return null;
        }
        String value = String.valueOf(token);
        EnumField.EnumValue declared = field.getValues().get(value);
        Microcopy label = declared == null ? null : declared.getLabel();
        String resolved = label == null ? null : resolvedText(label);
        return resolved != null ? resolved : value;
    }

    /** A violation-scoped microcopy message (catalog entries carry {@code scope=violations}). */
    public static @NonNull Microcopy violationText(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }

    /**
     * THE one-sentence description of a peer: microcopy key {@code nav_hint} in the peer's
     * OWN scope, so the sidebar entry, the panel index and the related-pages menu item a
     * demoted peer is reached through all read the same sentence.
     */
    public static @NonNull Microcopy navHint(@NonNull String scope) {
        return Microcopy.of("nav_hint").withFilter("scope", scope);
    }

    /**
     * THE page title of a record subpage: microcopy key {@code page_title} in the page's
     * OWN scope, carrying the record's name as {@code {$name}}.
     *
     * AIDEV-NOTE: one key name in one place per page, because the alternative is what
     * this replaced -- eleven call sites concatenating an English literal onto a record
     * name, one of which was written AFTER the defect had already been listed. The
     * `{page}_title`-in-a-shared-scope spelling the first three fixed pages used is gone:
     * two pages of the same record type (a zone's records and its zone file) would have
     * had to share one scope, and the whole point of the per-page scope is that each
     * surface owns its own short keys. `page_title` is guarded against reintroduction by
     * PageTitleLocalizationTest.
     *
     * @param scope the page's microcopy scope, e.g. {@code instance_device}
     * @param name the record's own name, never translated (it is user data)
     */
    public static @NonNull String pageTitle(@NonNull Conduit conduit, @NonNull String scope,
                                            @Nullable Object name) {
        return Microcopy.of("page_title").withFilter("scope", scope)
            .withArg("name", String.valueOf(name))
            .resolve(conduit.getLocales(), conduit.getMessageResolver());
    }

    /**
     * THE parent-record id a quick-add bar adds into, read off the REQUEST.
     *
     * AIDEV-NOTE: the framework asks the RESOURCE for its quick-add presets, so a
     * bespoke tab page cannot hand them in -- answering from the request is also the
     * more honest shape, because a page rendering the bar for parent A can then no
     * longer pass parent B. Two sources, in order: the {@code ?param=} prefill a
     * create link carries, then the record whose subpage is being rendered. A
     * malformed value answers null, which renders NO bar rather than a bar whose every
     * add fails on a field nothing shows.
     *
     * @param param      the query parameter AND form entry name of the parent key
     * @param parentSlug the parent resource's slug, whose subpage supplies the fallback
     */
    public static @Nullable Integer scopedParentId(@NonNull Conduit conduit, @NonNull String param,
                                                   @NonNull String parentSlug) {
        Integer prefilled = parsedInt(conduit.getQueryParam(param));
        if (prefilled != null) {
            return prefilled;
        }
        if (!parentSlug.equals(conduit.getParameter(CmsEndpoints.RESOURCE_PARAM))) {
            return null;
        }
        return parsedInt(conduit.getParameter(CmsEndpoints.RESOURCE_ID_PARAM));
    }

    /** @return the parsed integer, or null for an absent, empty or malformed value */
    public static @Nullable Integer parsedInt(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    /**
     * The hosting panel's slug during a cms dispatch (subpages render under /admin AND
     * /manage).
     *
     * AIDEV-NOTE: this replaced a {@code panelBase} that returned {@code "/" + slug} and
     * had ~20 call sites concatenating onto it. It is deliberately a SLUG and not a
     * path: a slug is what {@code CmsRoutes} takes, so there is nothing to concatenate
     * onto and the hand-built-URL failure mode cannot come back through this door.
     */
    public static @NonNull String panelSlug(@NonNull Conduit conduit) {
        String slug = conduit.getParameter(CmsEndpoints.PANEL_PARAM);
        return slug != null && !slug.isBlank() ? slug : "admin";
    }

    /**
     * Whether this render is the DELEGATED tenant panel rather than the operator one.
     *
     * AIDEV-NOTE: the projection question is about the SURFACE, never about the viewer.
     * An operator who opens /manage must see exactly what a tenant sees there, or the
     * projection is untestable by anyone who can also reach /admin -- which is everyone
     * who would notice a leak. Never rewrite this as an isAdmin check.
     *
     * AIDEV-NOTE: shared subpages ({@code InstanceOverviewPage},
     * {@code InstanceProvisioningPage}) render under BOTH panels, so "the delegated
     * resource omits it" only covers the FORM. Anything a subpage puts in its template
     * vars -- a host name, a server id inside a route target, a daemon's own error text
     * -- reaches a tenant unless the page asks this.
     */
    public static boolean isDelegatedPanel(@NonNull Conduit conduit) {
        return ManagePanel.SLUG.equals(panelSlug(conduit));
    }
}
