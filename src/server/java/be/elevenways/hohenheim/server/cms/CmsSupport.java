package be.elevenways.hohenheim.server.cms;

import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.common.brand.Brands;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.protoblast.common.time.RelativeTimeWording;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import java.util.function.BiFunction;
import java.util.Objects;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.wording.Reader;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.resource.ActivitySources;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.routing.RouteLocales;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

import static be.elevenways.hohenheim.RawValues.parsedInt;

/**
 * Shared helpers for the CMS resources: proxy reload and coerced-map copies.
 * Record-tab strips come from the framework (RecordTab.recordTabs);
 * mutation-driven reloads ride {@code ProxyReloadHooks}.
 *
 * AIDEV-NOTE: this used to state "audit writes ride the framework activity log" as an
 * invariant. It is a CONVENTION, not a guarantee: an action whose whole effect is an
 * unrecorded {@code updateAll()} or a daemon call records nothing at all. An action that
 * must be accountable records itself explicitly ({@code History.event} or its save's {@code Change}).
 */
public final class CmsSupport {

    /**
     * The framework activity record source {@code ActivitySources.register} declares over {@code ActionModel}, under
     * its public token {@link ActivitySources#SOURCE}. One home here because three surfaces read it (the admin
     * dashboard feed plus the per-record Recent cards).
     *
     * AIDEV-NOTE: never a literal token or a model id: the source was "zenit.activity" while it read ActivityModel,
     * then ActionModel's id, which the activity log resource's own source answers to since the feed got its public
     * token, titling every row by the action's command type.
     *
     * @throws IllegalStateException when the boot registered no activity source
     */
    public static @NonNull RecordSource<?> activitySource() {
        return RecordSourceRegistry.INSTANCE.requireById(ActivitySources.SOURCE);
    }

    // AIDEV-NOTE: the small operator-declared sets (hosts, DNS peers, git providers, access
    // lists, auth providers, backup targets) are plain ListChrome.MINIMAL, NOT a fourth
    // constant that also drops search. Search on those lists is a DELIBERATE declaration --
    // AdminListPresentationTest pins each of them by slug, and finding a host by the address
    // that renders under its name is the question those lists exist to answer. MINIMAL
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

    /** The coerced maps the CMS hands to persist/update are immutable; copy before staging values. */
    public static @NonNull Map<String, Object> mutable(@NonNull Map<String, Object> coerced) {
        return new LinkedHashMap<>(coerced);
    }

    // AIDEV-NOTE: there is deliberately NO stored-plus-submitted helper here. The inline cell
    // lane hands a writer a map holding EXACTLY ONE entry, so absence means LEAVE ALONE; an
    // UPDATE writer reads the whole record as coerced.merged() / call.merged() (zenit-cms
    // PartialWrite: the stored form values with the submission over them), and keeps the
    // submitted map for what was sent (containsKey). A hand merge here once duplicated it.

    /**
     * THE resolution of a microcopy outside a template, for the CURRENT request's reader -- the spelling every
     * {@code recordTitle} and rendered cell in this package shares, because those hooks take no conduit and reading
     * the request scope is the framework's own answer to that.
     *
     * @return the resolved text, or null when no request is in scope (a task thread, a
     *         test calling the hook directly), which every caller renders as "no title"
     *         rather than as a raw key
     */
    public static @Nullable String resolvedText(@NonNull Microcopy copy) {
        Conduit conduit = RouteScope.currentConduit();
        return conduit == null ? null : copy.asString(conduit);
    }

    /**
     * @return the current request's reader (its language and the viewer's zone), else the server default locale's,
     *         the one {@link #resolvedTextOrDefault} falls back to
     */
    public static @NonNull Reader currentReader() {
        Conduit conduit = RouteScope.currentConduit();
        return conduit != null ? conduit.getReader()
            : Reader.of(LocaleChain.of(RouteLocales.get().getDefaultLocale()), Zenit.getMessageResolver());
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
        String resolved = resolvedText(enumValueLabel(field, token));
        return resolved != null ? resolved : String.valueOf(token);
    }

    /** @return the declared label of ONE enum value as copy, the raw token as words where the value declares none */
    public static @NonNull Microcopy enumValueLabel(@NonNull EnumField field, @NonNull Object token) {
        EnumField.EnumValue declared = field.getValues().get(String.valueOf(token));
        return declared != null ? declared.getLabel() : Microcopy.literal(String.valueOf(token));
    }

    /**
     * A placed record operation that asks input under a titled form and answers the id of the record it made, which
     * the panel then opens; the caller adds what differs (sheet, row inlining, prefill).
     *
     * @param landing the URL of the made record, from the panel slug and its id
     * @param verb    the form's title and its submit button
     */
    static PanelAction.@NonNull OperationBuilder<Row, Integer> opensWhatItMade(
            @NonNull Operation<?, ?, Integer> operation, @NonNull BiFunction<String, Integer, String> landing,
            @NonNull Microcopy verb, @NonNull Microcopy body) {
        return PanelAction.<Row, Integer>places(operation, ActionPlacement.ROW,
                (request, made) -> CmsActionResult.redirect(new Uri(landing.apply(request.request().panelSlug(),
                    Objects.requireNonNull(made, "the operation answers the record it made")))))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .confirmation(ConfirmationSpec.verb(verb, body, ActionStyle.DEFAULT));
    }

    /**
     * THE one-sentence description of a peer: microcopy key {@code nav_hint} in the peer's
     * OWN scope, so the sidebar entry, the panel index and the related-pages menu item a
     * demoted peer is reached through all read the same sentence.
     */
    public static @NonNull Microcopy navHint(@NonNull HohenheimMicrocopy scope) {
        return scope.of("nav_hint");
    }

    /**
     * A sentence naming who runs this installation: the key's {@code _named} variant carrying {@code {$operator}}
     * when the installation declares its operator ({@link Brands#OPERATOR}), the plain key otherwise.
     */
    public static @NonNull Microcopy operatorWords(@NonNull HohenheimMicrocopy scope, @NonNull String key) {
        String operator = Brands.operator();
        return operator == null ? scope.of(key) : scope.of(key + "_named").withArg("operator", operator);
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
     * @param scope the page's microcopy scope, e.g. {@link HohenheimMicrocopy#INSTANCE_DEVICE}
     * @param name the record's own name, never translated (it is user data)
     */
    public static @NonNull String pageTitle(@NonNull Conduit conduit, @NonNull HohenheimMicrocopy scope,
                                            @Nullable Object name) {
        return scope.of("page_title").withArg("name", String.valueOf(name))
            .asString(conduit);
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

    /** @return the quick-add bar's preset {@code field -> }{@link #scopedParentId}, empty when it names no parent */
    public static @NonNull Map<String, Object> parentPreset(@NonNull AccessContext access, @NonNull String field,
                                                            @NonNull String parentSlug) {
        Conduit conduit = access.conduit();
        Integer id = conduit == null ? null : scopedParentId(conduit, field, parentSlug);
        return id != null ? Map.of(field, id) : Map.of();
    }

    /** @return the relative-time wording in the request's locale, the English defaults outside a request */
    public static @NonNull RelativeTimeWording timeWording(@Nullable Conduit conduit) {
        return conduit == null ? RelativeTimeWording.agoDefaults() : RelativeTimeWording.resolve(conduit);
    }

    /**
     * THE read of a declared create-form prefill parameter off the request's query string.
     *
     * AIDEV-NOTE: {@code Conduit.getParameter(definition)} only answers parameters DECLARED on
     * the matched route, and a prefill rides the generic CMS create route, which declares none
     * of Hohenheim's -- so this reads the query value by the definition's own name and resolves
     * it through the definition's own resolver. The name and the parse therefore have ONE home
     * (HohenheimParams), shared with the links that compose the same parameter.
     *
     * @param conduit the request, null for a caller without one (a create default asked by a task)
     * @return the resolved value, or null when there is no request or the parameter is absent, blank or malformed
     */
    public static <T> @Nullable T prefill(@Nullable Conduit conduit, @NonNull ParameterDefinition<T> param) {
        String raw = conduit != null ? conduit.getQueryParam(param.getName()) : null;
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return param.parse(raw.trim());
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
        return slug != null && !slug.isBlank() ? slug : HohenheimSlugs.ADMIN;
    }

    /**
     * Whether this render is the DELEGATED tenant panel rather than the operator one.
     *
     * AIDEV-NOTE: the projection question is about the SURFACE, never about the viewer.
     * An operator who opens /manage must see exactly what a tenant sees there, or the
     * projection is untestable by anyone who can also reach /admin -- which is everyone
     * who would notice a leak. Never rewrite this as an isAdmin check.
     *
     * AIDEV-NOTE: shared subpages ({@code InstanceOverview},
     * {@code InstanceProvisioningPage}) render under BOTH panels, so "the delegated
     * resource omits it" only covers the FORM. Anything a subpage puts in its template
     * vars -- a host name, a server id inside a route target, a daemon's own error text
     * -- reaches a tenant unless the page asks this.
     */
    public static boolean isDelegatedPanel(@NonNull Conduit conduit) {
        return HohenheimSlugs.MANAGE.equals(panelSlug(conduit));
    }

    /**
     * The row entry a panel registers under one slug: the panel's own declaration, never a second instance beside it.
     *
     * @throws IllegalStateException when the panel registers no row entry under that slug
     */
    public static @NonNull PanelResource<Row> rowEntry(@NonNull Panel panel, @NonNull String slug) {
        PanelResource<Row> entry = declaredRowEntry(panel, slug);
        if (entry == null) {
            throw new IllegalStateException("panel '" + panel.slug() + "' declares no row entry '" + slug + "'");
        }
        return entry;
    }

    /**
     * {@link #rowEntry}, answering null when the panel declares nothing under that slug, which is what a role this
     * node runs without leaves behind ({@link HohenheimPanel#addIf}).
     *
     * @throws IllegalStateException when the slug names an entry that is not a row entry
     */
    @SuppressWarnings("unchecked")
    public static @Nullable PanelResource<Row> declaredRowEntry(@NonNull Panel panel, @NonNull String slug) {
        PanelEntry declared = panel.entryBySlug(slug);
        if (declared == null) {
            return null;
        }
        if (declared instanceof PanelResource<?> entry && entry.subject().modelId() != null) {
            return (PanelResource<Row>) entry;
        }
        throw new IllegalStateException("panel '" + panel.slug() + "' declares no row entry '" + slug + "'");
    }

    /**
     * {@link #declaredRowEntry} when the panel also admits this viewer to it ({@link Panel#admits}, the judgement the
     * entry's own routes and a child list's sections make), so a page that links into it never draws a link that
     * would answer "page not found": the /manage panel declares no twin of several admin-only entries.
     *
     * @return null when the panel declares no such entry or refuses this viewer
     */
    public static @Nullable PanelResource<Row> admittedRowEntry(@NonNull Panel panel, @NonNull String slug,
                                                                @NonNull AccessContext access) {
        PanelResource<Row> entry = declaredRowEntry(panel, slug);
        return entry != null && panel.admits(entry, access) ? entry : null;
    }
}
