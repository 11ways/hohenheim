package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.app.PutOnlineReach;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.KindSettingsWrites.Kind;
import be.elevenways.hohenheim.server.upstream.kinds.AddressUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.DevNamespaceUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.RedirectUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.SiteManagerSettings;
import be.elevenways.hohenheim.server.upstream.kinds.StaticUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.StreamUpstreamKind;
import be.elevenways.hohenheim.server.upstream.kinds.TlsPassthroughUpstreamKind;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.hohenheim.upstream.UpstreamKindInfo;
import be.elevenways.hohenheim.upstream.UpstreamKinds;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.Plain;
import be.elevenways.zenit.common.operation.OperationCommand;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A site of one kind created or changed by its settings as an assistant names them, a {@link KindSettingsWrites}
 * family run through the site panel resource's own create and save, so its gates, the route claims and the enable
 * invariant decide as they do for the panel's form.
 *
 * AIDEV-NOTE: someone who is no administrator changes only the name, the switch, the description and the settings
 * the kind gives the site's manager (SiteManagerSettings), through the delegated save the /manage form posts. The
 * panel's create and save decide who may (SiteOperationHandlers), so both operations are open.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SiteKindWrites {

    /** The argument naming the app a site of the app kind serves. */
    public static final String APP = "app";

    /** The argument naming the access list every request of the site must pass. */
    public static final String ACCESS_LIST = "access_list";

    /** The argument naming the sign-in provider every visitor of the site must pass. */
    public static final String SIGN_IN_PROVIDER = "sign_in_provider";

    /**
     * The records a site links to, each by the argument naming it and the site's own column it is written into: its app
     * where its kind serves one, its access list and its sign-in provider where its kind reads HTTP (a passthrough site
     * never sees a request; a stream relays bytes, so it takes an access list of networks but no sign-in).
     */
    private static final KindSettingsWrites.Links LINKS = KindSettingsWrites.links(
        KindSettingsWrites.link(APP, HohenheimMicrocopy.HELP.of("site_app"), InstanceModel.MODEL_ID,
            SiteModel.INSTANCE_ID).onlyFor(InstanceUpstreamKind.ID::equals),
        KindSettingsWrites.link(ACCESS_LIST, HohenheimMicrocopy.HELP.of("site_access_list"), AccessListModel.MODEL_ID,
            SiteModel.ACCESS_LIST_ID).onlyFor(kind -> !TlsPassthroughUpstreamKind.ID.equals(kind)),
        KindSettingsWrites.link(SIGN_IN_PROVIDER, HohenheimMicrocopy.HELP.of("site_sign_in_provider"),
            SiteAuthProviderModel.MODEL_ID, SiteModel.AUTH_PROVIDER_ID)
            .onlyFor(kind -> !TlsPassthroughUpstreamKind.ID.equals(kind) && !reachedOnItsPorts(kind)));

    /** What the delegated save writes: the name, the switch and the description. */
    private static final Set<String> DELEGATED = Set.of(SiteModel.NAME.getName(), SiteModel.ENABLED.getName(),
        SiteModel.DESCRIPTION.getName());

    /** A create has no subject to key its command by: the request id alone answers the first result. */
    private static final OperationCommand CREATES = OperationCommand.perVerb().onDatasource("default");

    /** The site kinds: a site's name, switch, note and upstream trust besides its kind's settings. */
    private static final KindSettingsWrites.Family SITES = KindSettingsWrites.family("site", SiteModel.MODEL_ID,
            SiteOperations.SITE, SiteModel.ID, SiteModel.UPSTREAM_KIND, SiteModel.SETTINGS)
        .common(List.of(SiteModel.NAME, SiteModel.ENABLED, SiteModel.DESCRIPTION, SiteModel.TRUSTED_UPSTREAM))
        .createEntries(kind -> {
            // A site reached on ports of its own (a stream) has no address, so its create takes none.
            List<FormEntry> entries = new ArrayList<>(reachedOnItsPorts(kind) ? List.of()
                : List.of(Plain.of(SiteWrites.CREATE_HOSTNAME)));
            entries.addAll(LINKS.entries(kind, true));
            return entries;
        })
        .settings(SiteKindWrites::settings)
        .changeEntries(kind -> LINKS.entries(kind, false))
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
    // One site forwards every public port of one service, a list of ports (StreamPort); each create names at least one.
    public static final Kind STREAM = declare(StreamUpstreamKind.ID, StreamUpstreamKind.SETTINGS_SCHEMA,
        "site_stream_set", StreamUpstreamKind.FORWARD_HOST.getInputName(), StreamUpstreamKind.PORTS.getInputName());
    // TLS passes through untouched to its backend, which presents its own certificate: no access list, no sign-in.
    public static final Kind PASSTHROUGH = declare(TlsPassthroughUpstreamKind.ID,
        TlsPassthroughUpstreamKind.SETTINGS_SCHEMA, "site_passthrough_set",
        TlsPassthroughUpstreamKind.FORWARD_HOST.getInputName());
    public static final Kind DEV_NAMESPACE = declare(DevNamespaceUpstreamKind.ID,
        DevNamespaceUpstreamKind.SETTINGS_SCHEMA, "site_dev_namespace_set");

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

    /** @return whether a site of the kind is reached on public ports of its own, never at an address */
    private static boolean reachedOnItsPorts(@NonNull Identifier kind) {
        UpstreamKindInfo info = UpstreamKinds.registry().get(kind);
        return info != null && info.putOnlineReach() == PutOnlineReach.PORTS;
    }

    /** Creates the site, with its first address when the call gives one, through the panel's own create. */
    private static @NonNull Integer created(@NonNull Kind kind, OperationInput.@NonNull Values input,
                                            @NonNull AccessContext caller) {
        Map<String, Object> raw = SITES.created(kind, input);
        // A map setting (a stream's ports) is posted as the panel's key/value editor posts it, as the app writes do.
        raw.put(SiteModel.SETTINGS.getName(), KindSettingsWrites.posted(kind.settings(),
            RawValues.map(raw.get(SiteModel.SETTINGS.getName()))));
        Object hostname = input.get(SiteWrites.CREATE_HOSTNAME.getName());
        if (hostname != null) raw.put(SiteWrites.CREATE_HOSTNAME.getName(), hostname);
        for (KindSettingsWrites.Link link : LINKS.takenBy(kind.id())) {
            Object given = input.get(link.argument());
            if (given != null) raw.put(link.column().getName(), String.valueOf(given));
        }
        Panel panel = ApiConduits.adminPanel();
        Object key = KindSettingsWrites.speaking(LINKS.columns(),
            () -> ResourceWrites.create(panel, sites(panel), raw, caller));
        return Integer.valueOf(String.valueOf(key));
    }

    /**
     * Writes what the call sent over the site as it stands: an administrator's through the operator's save (a site
     * turned into this kind keeping the settings this kind declares, its app link cleared when it serves no app),
     * anyone else's through the delegated save, which takes the name, the switch, the description and the settings the
     * kind gives the site's manager.
     *
     * @return the names of the settings written
     * @throws Violations for a setting only an administrator changes, on its argument
     */
    private static @NonNull String changed(@NonNull Kind kind, @NonNull Row site, OperationInput.@NonNull Values input,
                                           @NonNull Set<String> sent, boolean turned,
                                           @NonNull AccessContext caller) {
        List<String> written = new ArrayList<>(sent);
        if (!HohenheimAccess.isAdmin(caller)) {
            if (turned) throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("tenant_field_frozen"));
            List<Field<?, ?>> managed = new ArrayList<>(SiteManagerSettings.of(kind.schema()));
            managed.retainAll(kind.settings());
            Set<String> delegated = new LinkedHashSet<>(DELEGATED);
            for (Field<?, ?> field : managed) delegated.add(field.getInputName());
            for (String name : sent) {
                if (!delegated.contains(name)) {
                    throw Violations.ofField(name, input.get(name),
                        HohenheimMicrocopy.VIOLATIONS.of("tenant_field_frozen"));
                }
            }
            if (sent.isEmpty()) return "";
            // The delegated save takes its whole form: what the call left out is posted as it is stored (the patch
            // base), the kind is the site's own, and every other setting keeps its stored value there.
            Map<String, Object> raw = new LinkedHashMap<>();
            for (String name : DELEGATED) raw.put(name, input.get(name));
            Map<String, Object> settings = new LinkedHashMap<>();
            for (Field<?, ?> field : managed) {
                // What the call left out stays as stored (its default where none is), so the delegated save posts it
                // back unchanged: a switch left out of a post would otherwise read as off.
                Object kept = RawValues.map(site.get(SiteModel.SETTINGS)).get(field.getName());
                Object value = sent.contains(field.getInputName()) ? input.get(field.getInputName())
                    : kept != null ? kept : field.getDefaultValue();
                if (value != null) settings.put(field.getInputName(), value);
            }
            // Settings go only when the call changes one, so a rename leaves the settings exactly as stored.
            boolean changesSettings = managed.stream().anyMatch(field -> sent.contains(field.getInputName()));
            if (changesSettings) raw.put(SiteModel.SETTINGS.getName(), KindSettingsWrites.posted(managed, settings));
            Panel panel = ApiConduits.managePanel();
            ResourceWrites.update(panel, sites(panel), Objects.requireNonNull(site.get(SiteModel.ID)), site, raw,
                caller);
            return String.join(", ", written);
        }
        // The operator's save takes its whole form: what the call left out is posted as it is stored.
        Map<String, Object> raw = new LinkedHashMap<>();
        for (Field<?, ?> field : SITES.commonFields()) {
            raw.put(field.getInputName(), input.get(field.getInputName()));
        }
        raw.put(SiteModel.UPSTREAM_KIND.getName(), kind.id().toString());
        // A link the kind takes keeps what is stored unless the call sent it; one it does not take is cleared, or the
        // site's rules refuse the save (a site turned from an app keeps no app, a passthrough no access list).
        for (KindSettingsWrites.Link link : LINKS.changeable()) {
            Object value = !link.takenBy(kind.id()) ? null : sent.contains(link.argument())
                ? input.get(link.argument()) : site.get(link.column().getName());
            raw.put(link.column().getName(), idText(value));
        }
        // Only what the call sent changes: a setting it left out stays as stored (absent stays absent, so its kind's
        // default keeps applying), and null clears one by taking its key away. A setting the tool does not offer (an
        // app site's port, which nothing honours) stays as it is stored too, except on a site turned from another kind.
        Map<String, Object> kept = RawValues.map(site.get(SiteModel.SETTINGS));
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Field<?, ?> field : kind.schema().getFields().values()) {
            boolean given = kind.settings().contains(field) && sent.contains(field.getInputName());
            Object value = given ? input.get(field.getInputName())
                : turned && !kind.settings().contains(field) ? null : kept.get(field.getName());
            if (value != null) settings.put(field.getInputName(), value);
        }
        raw.put(SiteModel.SETTINGS.getName(), KindSettingsWrites.posted(kind.schema().getFields().values(), settings));
        if (turned) written.add(0, SiteModel.UPSTREAM_KIND.getName());
        Panel panel = ApiConduits.adminPanel();
        KindSettingsWrites.speaking(LINKS.columns(), () -> ResourceWrites.update(panel, sites(panel),
            Objects.requireNonNull(site.get(SiteModel.ID)), site, raw, caller));
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
