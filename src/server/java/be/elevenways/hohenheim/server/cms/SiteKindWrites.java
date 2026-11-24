package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.KindSettingsWrites.Kind;
import be.elevenways.hohenheim.server.upstream.kinds.AddressUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.RedirectUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.StaticUpstreamKind;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.Plain;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationForms;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A site of one kind created or changed by its settings as an assistant names them, a {@link KindSettingsWrites}
 * family run through the site panel resource's own create and save, so its gates, the route claims and the enable
 * invariant decide as they do for the panel's form.
 *
 * AIDEV-NOTE: someone who is no administrator changes only the name, the switch and the description, through the
 * delegated save the /manage form posts. The panel's create and save decide who may (SiteOperationHandlers), so both
 * operations are open.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SiteKindWrites {

    /** The argument naming the app a site of the app kind serves. */
    public static final String APP = "app";

    /** The argument naming the access list every request of the site must pass. */
    public static final String ACCESS_LIST = "access_list";

    /** The record each linking argument names, by the site's own column it is written into; a refusal names it back. */
    private static final Map<String, Field<?, ?>> LINKS = Map.of(APP, SiteModel.INSTANCE_ID,
        ACCESS_LIST, SiteModel.ACCESS_LIST_ID);

    /** What the delegated save writes: the name, the switch and the description. */
    private static final Set<String> DELEGATED = Set.of(SiteModel.NAME.getName(), SiteModel.ENABLED.getName(),
        SiteModel.DESCRIPTION.getName());

    /** A create has no subject to key its command by: the request id alone answers the first result. */
    private static final OperationCommand CREATES = OperationCommand.perVerb().onDatasource("default");

    /** Where a site's settings sit in its form, as a refusal of one names its path. */
    private static final String SETTINGS_PATH = SiteModel.SETTINGS.getName() + ".";

    /** The site kinds: a site's name, switch, note and upstream trust besides its kind's settings. */
    private static final KindSettingsWrites.Family SITES = KindSettingsWrites.family("site", SiteModel.MODEL_ID,
            SiteOperations.SITE, SiteModel.ID, SiteModel.UPSTREAM_KIND, SiteModel.SETTINGS)
        .common(List.of(SiteModel.NAME, SiteModel.ENABLED, SiteModel.DESCRIPTION, SiteModel.TRUSTED_UPSTREAM))
        .createEntries(kind -> {
            List<FormEntry> entries = new ArrayList<>(List.of(Plain.of(SiteWrites.CREATE_HOSTNAME)));
            entries.addAll(links(kind));
            return entries;
        })
        .settings(SiteKindWrites::settings)
        .changeEntries(SiteKindWrites::links)
        .commands(CREATES, CmsCommands.TRANSACTIONAL)
        .writers(SiteKindWrites::created, SiteKindWrites::changed);

    public static final Kind APP_SITE = declare(InstanceUpstreamKind.ID, InstanceUpstreamKind.SETTINGS_SCHEMA,
        "site_app_set", APP);
    public static final Kind STATIC_FILES = declare(StaticUpstreamKind.ID, StaticUpstreamKind.SETTINGS_SCHEMA,
        "site_static_files_set");
    public static final Kind PROXY = declare(AddressUpstreamKind.ID, AddressUpstreamKind.SETTINGS_SCHEMA,
        "site_proxy_set");
    // The panel lets a redirect be made without its target, which answers 502: the tool asks for it (the spec's rule).
    public static final Kind REDIRECT = declare(RedirectUpstreamKind.ID, RedirectUpstreamKind.SETTINGS_SCHEMA,
        "site_redirect_set", RedirectUpstreamKind.TARGET_URL.getInputName());

    private SiteKindWrites() {
    }

    /** @return every kind's writes, in the order their tools are offered */
    public static @NonNull List<Kind> all() {
        return SITES.kinds();
    }

    private static @NonNull Kind declare(@NonNull Identifier kind, @NonNull Schema schema, @NonNull String tool,
                                         @NonNull String @NonNull ... needs) {
        String word = tool.substring(0, tool.length() - "_set".length());
        return SITES.kind(kind, schema, word, tool, HohenheimMicrocopy.HOHENHEIM.of("site_thing_" + word), needs);
    }

    /** @return whether a site of the kind serves one of Hohenheim's apps, so it names the app */
    private static boolean servesApp(@NonNull Identifier kind) {
        return InstanceUpstreamKind.ID.equals(kind);
    }

    /** @return the records a site of the kind links to: its app where it serves one, and its access list */
    private static @NonNull List<FormEntry> links(@NonNull Identifier kind) {
        List<FormEntry> entries = new ArrayList<>();
        if (servesApp(kind)) {
            entries.add(OperationForms.recordId(APP, HohenheimMicrocopy.HELP.of("site_app"), InstanceModel.MODEL_ID,
                false));
        }
        entries.add(OperationForms.recordId(ACCESS_LIST, HohenheimMicrocopy.HELP.of("site_access_list"),
            AccessListModel.MODEL_ID, false));
        return entries;
    }

    /** Creates the site, with its first address when the call gives one, through the panel's own create. */
    private static @NonNull Integer created(@NonNull Kind kind, OperationInput.@NonNull Values input,
                                            @NonNull AccessContext caller) {
        Map<String, Object> raw = SITES.created(kind, input);
        Object hostname = input.get(SiteWrites.CREATE_HOSTNAME.getName());
        if (hostname != null) raw.put(SiteWrites.CREATE_HOSTNAME.getName(), hostname);
        if (servesApp(kind.id()) && input.get(APP) != null) {
            raw.put(SiteModel.INSTANCE_ID.getName(), String.valueOf(input.get(APP)));
        }
        if (input.get(ACCESS_LIST) != null) {
            raw.put(SiteModel.ACCESS_LIST_ID.getName(), String.valueOf(input.get(ACCESS_LIST)));
        }
        Panel panel = ApiConduits.adminPanel();
        Object key = KindSettingsWrites.speaking(kind.settings(), SETTINGS_PATH, LINKS,
            () -> ResourceWrites.create(panel, sites(panel), raw, caller));
        return Integer.valueOf(String.valueOf(key));
    }

    /**
     * Writes what the call sent over the site as it stands: an administrator's through the operator's save (a site
     * turned into this kind keeping the settings this kind declares, its app link cleared when it serves no app),
     * anyone else's through the delegated save.
     *
     * @return the names of the settings written
     * @throws Violations for a setting only an administrator changes
     */
    private static @NonNull String changed(@NonNull Kind kind, @NonNull Row site, OperationInput.@NonNull Values input,
                                           @NonNull Set<String> sent, boolean turned,
                                           @NonNull AccessContext caller) {
        List<String> written = new ArrayList<>(sent);
        if (!HohenheimAccess.isAdmin(caller)) {
            if (turned || !DELEGATED.containsAll(sent)) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("tenant_field_frozen"));
            }
            Map<String, Object> raw = new LinkedHashMap<>();
            for (String name : sent) raw.put(name, input.get(name));
            if (raw.isEmpty()) return "";
            Panel panel = ApiConduits.managePanel();
            ResourceWrites.update(panel, sites(panel), Objects.requireNonNull(site.get(SiteModel.ID)), site, raw,
                caller);
            return String.join(", ", written);
        }
        // The operator's save takes its whole form: what the call left out is posted as it is stored.
        Map<String, Object> raw = new LinkedHashMap<>();
        for (Field<?, ?> field : SITES.commonFields()) raw.put(field.getName(), input.get(field.getName()));
        raw.put(SiteModel.UPSTREAM_KIND.getName(), kind.id().toString());
        // A site turned away from the app kind loses its app link, or the link rule refuses the save.
        Object app = !servesApp(kind.id()) ? null : sent.contains(APP) ? input.get(APP)
            : site.get(SiteModel.INSTANCE_ID);
        raw.put(SiteModel.INSTANCE_ID.getName(), idText(app));
        raw.put(SiteModel.ACCESS_LIST_ID.getName(), idText(sent.contains(ACCESS_LIST) ? input.get(ACCESS_LIST)
            : site.get(SiteModel.ACCESS_LIST_ID)));
        raw.put(SiteModel.AUTH_PROVIDER_ID.getName(), idText(site.get(SiteModel.AUTH_PROVIDER_ID)));
        // Only what the call sent changes: a setting it left out stays as stored (absent stays absent, so its kind's
        // default keeps applying), and null clears one by taking its key away. A setting the tool does not offer (an
        // app site's port, which nothing honours) stays as it is stored too, except on a site turned from another kind.
        Map<String, Object> kept = RawValues.map(site.get(SiteModel.SETTINGS));
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Field<?, ?> field : kind.schema().getFields().values()) {
            String name = field.getName();
            boolean given = kind.settings().contains(field) && sent.contains(field.getInputName());
            Object value = given ? input.get(field.getInputName())
                : turned && !kind.settings().contains(field) ? null : kept.get(name);
            if (value != null) settings.put(name, value);
        }
        raw.put(SiteModel.SETTINGS.getName(), settings);
        if (turned) written.add(0, SiteModel.UPSTREAM_KIND.getName());
        Panel panel = ApiConduits.adminPanel();
        KindSettingsWrites.speaking(kind.settings(), SETTINGS_PATH, LINKS, () -> ResourceWrites.update(panel,
            sites(panel), Objects.requireNonNull(site.get(SiteModel.ID)), site, raw, caller));
        return String.join(", ", written);
    }

    /**
     * @return the kind's settings an assistant gives by name: all but an app site's port, which the runtime never
     *     honours (InstanceUpstreamKind.PORT: a site serves its app's single publication)
     */
    private static @NonNull List<Field<?, ?>> settings(@NonNull Schema schema) {
        List<Field<?, ?>> settings = new ArrayList<>(schema.getFields().values());
        settings.remove(InstanceUpstreamKind.PORT);
        return settings;
    }

    private static @Nullable String idText(@Nullable Object id) {
        return id == null ? null : String.valueOf(id);
    }

    /** @return the panel's site resource, the one whose form the panel posts */
    private static @NonNull PanelResource<Row> sites(@NonNull Panel panel) {
        return Objects.requireNonNull(CmsSupport.declaredRowEntry(panel, HohenheimSlugs.SITES),
            "the panel declares its sites");
    }
}
