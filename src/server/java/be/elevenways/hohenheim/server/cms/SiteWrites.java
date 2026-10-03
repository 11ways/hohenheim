package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimPickRules;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.FieldFormEntryDefaults;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectArity;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.ArchiveOperations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * The site resource's write verbs as domain operations (stage 4 contract 10): the admin create with its first
 * hostname, the admin edit, the delegated /manage edit and the delete, each typed by the form its placement posts.
 *
 * AIDEV-NOTE: declared SERVER-side, unlike {@link SiteOperations}: an operation's input form is what the pipeline
 * coerces and validates with, and the site form's instance pick narrows by the server's instance-kind registry, so
 * the real form has to be the input; a name-only stand-in would drop that narrowing. Every gate is
 * {@link OperationGate#open()}: who may act is the server-attached authorizer (SiteOperationHandlers): installation
 * administration for create and delete, reach of the site for both edits (DECIDED 2026-10-02 ~19:25).
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class SiteWrites {

    /**
     * The first hostname the new site answers on, stored as a {@code site_domains} child row by the create rather than
     * as a column of the site.
     *
     * AIDEV-NOTE: the backing field belongs to NO Schema on purpose: a hostname is a child ROW, and a column here would
     * be a second home for a fact the route invariant judges. It is {@code visibleIn(CREATE)}, so it is the create's
     * input alone and the edit form neither renders nor coerces it; the Domains tab is the editor once the site
     * exists. It is NAMED "hostname" because the route invariant's refusals are pathed on that name
     * ({@code Violations.ofField("hostname", ...)} in SiteDomainRouteInvariant), so a claimed name lands on this input.
     */
    public static final StringField CREATE_HOSTNAME = StringField.builder()
        .name(SiteDomainModel.HOSTNAME.getName())
        .label(HohenheimFormCopy.label("hostname"))
        .help(HohenheimFormCopy.help("create_hostname"))
        .placeholder("example.com")
        .visibleIn(EditView.CREATE)
        .build();

    /**
     * The operator's site form: choice cards decide the upstream kind, the per-kind settings switch under it without a
     * round trip, and the instance pick only wakes up for the {@code instance} kind, narrowed to instances the routing
     * tier can actually serve.
     */
    public static final FormSpec ADMIN_FORM = FormSpec.builder()
        .add(SiteModel.NAME)
        // Optional, CREATE only: a blank one still creates a hostname-less site and lands on the Domains tab.
        .add(CREATE_HOSTNAME)
        .add(Select.of(SiteModel.UPSTREAM_KIND)
            .options(FieldFormEntryDefaults.enumOptionSource(SiteModel.UPSTREAM_KIND))
            .presentation(Select.Presentation.CARDS)
            .clearable(false)
            .build())
        // The pick resolves ONLY while the chosen upstream kind is the instance one (disabled otherwise), and offers
        // only kinds whose serving container publishes a port. An instance is never created on a whim from inside a
        // site form, so there is no "create new" here. The submit is re-narrowed by this same form's coercion.
        .add(RelationPick.of(SiteModel.INSTANCE_ID, InstanceModel.MODEL_ID)
            .creatable(false).clearable(true)
            .rulesFromSiblings(new HohenheimPickRules.UpstreamInstanceRules(
                SiteModel.UPSTREAM_KIND.getName(),
                InstanceUpstreamKind.ID.toString(),
                InstanceKinds.kindsWhere(InstanceKindHandler::supportsSiteUpstream)),
                "upstream_kind")
            .build())
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SETTINGS))
        // Operator-only: the /manage form omits it, and TenantWrites freezes the column for every tenant write
        // whatever a form offers.
        .add(SiteModel.TRUSTED_UPSTREAM)
        .add(SiteModel.ENABLED)
        .add(SiteModel.DESCRIPTION)
        // Both are SECURITY declarations shared across sites: minting one from inside a site form is how a
        // half-configured provider or an empty allow-list goes live.
        .add(RelationPick.of(SiteModel.AUTH_PROVIDER_ID, SiteAuthProviderModel.MODEL_ID)
            .creatable(false).build())
        .add(RelationPick.of(SiteModel.ACCESS_LIST_ID, AccessListModel.MODEL_ID)
            .creatable(false).build())
        // Creating a site is deciding a name, what it serves and whether it is on. The prose and the two shared
        // security declarations are edits a site receives later, so they fold -- still in the DOM, still posted,
        // and forced open by a refusal.
        .section(FormSection.advanced(
            SiteModel.TRUSTED_UPSTREAM.getName(),
            SiteModel.DESCRIPTION.getName(),
            SiteModel.AUTH_PROVIDER_ID.getName(),
            SiteModel.ACCESS_LIST_ID.getName()))
        .build();

    /** The delegated form: only non-execution metadata is editable on /manage. */
    public static final FormSpec MANAGE_FORM = FormSpec.builder()
        .add(SiteModel.NAME)
        .add(SiteModel.ENABLED)
        .add(SiteModel.DESCRIPTION)
        .build();

    /**
     * The settings pair a secret restore reads: the discriminating kind and the settings it selects. A blank secret
     * leaf of a posted settings map means "keep", exactly as on the row lane.
     */
    static final FormSpec SETTINGS_FORM = FormSpec.builder()
        .add(SiteModel.UPSTREAM_KIND)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SETTINGS))
        .build();

    /** The admin create's input: the form's CREATE view, the first hostname included. */
    public record CreateInput(@Nullable String name, @Nullable String hostname, @Nullable String upstream_kind,
                              @Nullable Integer instance_id, @Nullable Map<String, Object> settings,
                              @Nullable Boolean trusted_upstream, @Nullable Boolean enabled,
                              @Nullable String description, @Nullable Integer auth_provider_id,
                              @Nullable Integer access_list_id) {
    }

    /** The admin edit's input: the form's EDIT view. */
    public record EditInput(@Nullable String name, @Nullable String upstream_kind, @Nullable Integer instance_id,
                            @Nullable Map<String, Object> settings, @Nullable Boolean trusted_upstream,
                            @Nullable Boolean enabled, @Nullable String description,
                            @Nullable Integer auth_provider_id, @Nullable Integer access_list_id) {
    }

    /** The delegated edit's input. */
    public record ManageInput(@Nullable String name, @Nullable Boolean enabled, @Nullable String description) {
    }

    /** Creates a site and, when the form carried one, its first hostname, atomically. Its result is the site's id. */
    public static final Operation<Void, CreateInput, Integer> CREATE =
        Operation.declare(HohenheimIds.id("create_site"))
            .label(Microcopy.of("create").withFilter("scope", "cms"))
            .noSubject()
            .gate(OperationGate.open())
            .input(OperationInput.of(ADMIN_FORM, CreateInput.class, v -> new CreateInput(
                v.get(SiteModel.NAME), v.get(CREATE_HOSTNAME), v.get(SiteModel.UPSTREAM_KIND),
                v.get(SiteModel.INSTANCE_ID), settings(v.get(SiteModel.SETTINGS.getName())),
                v.get(SiteModel.TRUSTED_UPSTREAM), v.get(SiteModel.ENABLED), v.get(SiteModel.DESCRIPTION),
                v.get(SiteModel.AUTH_PROVIDER_ID), v.get(SiteModel.ACCESS_LIST_ID))))
            .result(Integer.class)
            .register();

    /** The operator's edit, checked against the site's latest revision; patchable for a partial write. */
    public static final Operation<Row, EditInput, Void> UPDATE =
        Operation.declare(HohenheimIds.id("update_site"))
            .label(Microcopy.of("save").withFilter("scope", "cms"))
            .one(SiteOperations.SITE)
            .gate(OperationGate.open())
            .input(OperationInput.of(ADMIN_FORM.forView(EditView.EDIT), EditInput.class, v -> new EditInput(
                v.get(SiteModel.NAME), v.get(SiteModel.UPSTREAM_KIND), v.get(SiteModel.INSTANCE_ID),
                settings(v.get(SiteModel.SETTINGS.getName())), v.get(SiteModel.TRUSTED_UPSTREAM),
                v.get(SiteModel.ENABLED), v.get(SiteModel.DESCRIPTION), v.get(SiteModel.AUTH_PROVIDER_ID),
                v.get(SiteModel.ACCESS_LIST_ID))))
            .patchable()
            .register();

    /** The delegated edit: name, switch and description only, never the admin normalizers. */
    public static final Operation<Row, ManageInput, Void> MANAGE_UPDATE =
        Operation.declare(HohenheimIds.id("manage_update_site"))
            .label(Microcopy.of("save").withFilter("scope", "cms"))
            .one(SiteOperations.SITE)
            .gate(OperationGate.open())
            .input(OperationInput.of(MANAGE_FORM, ManageInput.class, v -> new ManageInput(
                v.get(SiteModel.NAME), v.get(SiteModel.ENABLED), v.get(SiteModel.DESCRIPTION))))
            .patchable()
            .register();

    /**
     * Trashes a site after reclaiming the previews it routed; offered-but-dead on the site serving the panel. Its
     * result is the number of sites deleted.
     */
    public static final Operation<Row, Void, Integer> DELETE = Operation.declare(HohenheimIds.id("delete_site"))
        .label(Microcopy.of("delete").withFilter("scope", "cms"))
        .icon(Icon.TRASH)
        .one(SiteOperations.SITE)
        .gate(OperationGate.open())
        .facts(OperationFact.REACHES_OUTSIDE, OperationFact.DESTRUCTIVE)
        .result(Integer.class)
        .register();

    /**
     * The Trash's verbs: core's archive operations over the site model, restore and permanent delete of one site and
     * of a selection, gated on installation administration as the delete is.
     *
     * AIDEV-NOTE: a restore is the soft-delete behaviour's restore, which saves through the model, so every site write
     * hook runs and the quota re-books the slot (refused by name over the cap), exactly as the legacy trash did.
     */
    public static final Operation<Row, Void, Integer> RESTORE =
        ArchiveOperations.restore(SiteModel.class, SubjectArity.ONE, OperationGate.permission(HohenheimPanel.ACCESS));
    public static final Operation<Row, Void, Integer> RESTORE_MANY =
        ArchiveOperations.restore(SiteModel.class, SubjectArity.MANY, OperationGate.permission(HohenheimPanel.ACCESS));
    public static final Operation<Row, Void, Integer> PURGE =
        ArchiveOperations.purge(SiteModel.class, SubjectArity.ONE, OperationGate.permission(HohenheimPanel.ACCESS));
    public static final Operation<Row, Void, Integer> PURGE_MANY =
        ArchiveOperations.purge(SiteModel.class, SubjectArity.MANY, OperationGate.permission(HohenheimPanel.ACCESS));

    private SiteWrites() {
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> settings(@Nullable Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
