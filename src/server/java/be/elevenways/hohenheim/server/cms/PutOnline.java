package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.upstream.UpstreamKindInfo;
import be.elevenways.hohenheim.upstream.UpstreamKinds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.edit.FieldFormEntryDefaults;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.FormStep;
import be.elevenways.zenit.common.edit.Nested;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.OperationSteps;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * "Put something online": one flow that creates an app, its address and its certificate, as two background
 * operations behind one wizard page ({@link PutOnlinePage}).
 *
 * AIDEV-NOTE: two operations because the input differs by WHAT is put online. A template's form is that template's own
 * variables, resolved per selected template (the subject), so {@link #PUT_ONLINE} takes the template as its subject,
 * exactly as the create-from-template lane does. An address that needs no workload (a redirect, an existing service,
 * static files, TLS passthrough) has no subject; {@link #PUT_ADDRESS_ONLINE} asks the site form's upstream kind and
 * settings. Both end in the same Website and Certificate steps (PutOnlineHandlers), so the going-live half is one
 * implementation. Both run in the background: their run page is the board's "Going live" step.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class PutOnline {

    /** The HTTPS choice: a Let's Encrypt certificate ordered once the address exists. */
    public static final String HTTPS_AUTOMATIC = "automatic";

    /** The HTTPS choice: no order now; the address answers over HTTP until a certificate is added. */
    public static final String HTTPS_LATER = "later";

    /** The address visitors type. Named like the site create's first hostname, so route refusals path on it. */
    public static final StringField HOSTNAME = StringField.builder(SiteDomainModel.HOSTNAME.getName())
        .label(copy("address"))
        .help(copy("address_help"))
        .placeholder("shop.example.com")
        .build();

    /** The same address for the address kinds, which have nothing to serve without one (so no "leave it empty"). */
    public static final StringField ADDRESS_HOSTNAME = StringField.builder(SiteDomainModel.HOSTNAME.getName())
        .label(copy("address"))
        .help(copy("address_help_required"))
        .placeholder("shop.example.com")
        .build();

    public static final StringField HTTPS = StringField.builder("https").label(copy("https")).build();

    /** The run's steps for a template: the app, its install, its website, its certificate, then live. */
    private static final OperationSteps APP_STEPS = OperationSteps.of(
        OperationSteps.step("app", copy("run_app")),
        OperationSteps.step("install", copy("run_install")),
        OperationSteps.step("website", copy("run_website")),
        OperationSteps.step("certificate", copy("run_certificate")),
        OperationSteps.step("live", copy("run_live")));

    /** The run's steps for an address: its website and its certificate. */
    private static final OperationSteps ADDRESS_STEPS = OperationSteps.of(
        OperationSteps.step("website", copy("run_website")),
        OperationSteps.step("certificate", copy("run_certificate")));

    private static final FormSpec TEMPLATE_INPUT = FormSpec.builder()
        .add(InstanceTemplateOperations.NAME)
        .add(HOSTNAME)
        .add(InstanceTemplateOperations.SERVER_ID)
        .add(InstanceTemplateOperations.PROJECT_ID)
        .add(InstanceTemplateOperations.ENVIRONMENT_ID)
        .add(Nested.of(InstanceTemplateOperations.VARIABLES).subSpec(FormSpec.builder().build()).build())
        .add(httpsChoice())
        .step(FormStep.of("where", copy("step_where"), InstanceTemplateOperations.NAME.getName(), HOSTNAME.getName(),
            InstanceTemplateOperations.SERVER_ID.getName(), InstanceTemplateOperations.PROJECT_ID.getName(),
            InstanceTemplateOperations.ENVIRONMENT_ID.getName()).describe(copy("step_where_lead")))
        .step(new FormStep(InstanceTemplateOperations.VARIABLES, copy("step_options"), copy("step_options_lead"),
            List.of(InstanceTemplateOperations.VARIABLES)))
        .step(FormStep.of("https", copy("step_https"), HTTPS.getName()).describe(copy("step_https_lead")))
        .build();

    private static final FormSpec ADDRESS_INPUT = FormSpec.builder()
        .add(SiteModel.NAME)
        .add(ADDRESS_HOSTNAME)
        .add(Select.of(SiteModel.UPSTREAM_KIND)
            .options(OptionSource.dynamic(context -> FieldFormEntryDefaults.enumOptionSource(SiteModel.UPSTREAM_KIND)
                .resolve(context).stream().filter(option -> offeredAsApp(option.value())).toList()))
            .presentation(Select.Presentation.CARDS)
            .clearable(false)
            .build())
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(SiteModel.SETTINGS))
        .add(httpsChoice())
        .step(FormStep.of("where", copy("step_where"), SiteModel.NAME.getName(), ADDRESS_HOSTNAME.getName(),
            SiteModel.UPSTREAM_KIND.getName(), SiteModel.SETTINGS.getName()).describe(copy("step_where_lead")))
        .step(FormStep.of("https", copy("step_https"), HTTPS.getName()).describe(copy("step_https_lead")))
        .build();

    /** The template's own create (named as its form names it), its address and its HTTPS choice. */
    public record FromTemplate(@Nullable String name, @Nullable String hostname, @Nullable Integer serverId,
                               @Nullable Integer projectId, @Nullable Integer environmentId,
                               @NonNull Map<String, Object> variables, @Nullable String https) {

        /** @return the template create funnel's own input */
        public InstanceTemplateOperations.@NonNull CreateFromTemplate create() {
            return new InstanceTemplateOperations.CreateFromTemplate(this.name, this.serverId, this.projectId,
                this.environmentId, this.variables);
        }
    }

    /** An address that needs no workload: what it serves (the site's upstream kind and settings) and its HTTPS. */
    public record Address(@Nullable String name, @Nullable String hostname, @Nullable String upstream_kind,
                          @Nullable Map<String, Object> settings, @Nullable String https) {
    }

    /** Puts one template online: the app, its address, its certificate, then the first deploy. */
    public static final Operation<Row, FromTemplate, Integer> PUT_ONLINE =
        Operation.declare(HohenheimIds.id("put_online"))
            .label(copy("put_online"))
            .icon(Icon.of("rocket"))
            .one(InstanceTemplateOperations.TEMPLATE)
            .gate(OperationGate.open())
            .input(OperationInput.of(TEMPLATE_INPUT, FromTemplate.class, values -> new FromTemplate(
                values.get(InstanceTemplateOperations.NAME), values.get(HOSTNAME),
                values.get(InstanceTemplateOperations.SERVER_ID), values.get(InstanceTemplateOperations.PROJECT_ID),
                values.get(InstanceTemplateOperations.ENVIRONMENT_ID),
                InstanceTemplateOperations.variables(values.get(InstanceTemplateOperations.VARIABLES)),
                values.get(HTTPS))))
            .result(Integer.class)
            .rateLimit(HohenheimEndpoints.INSTANCE_CREATE_LIMIT)
            .facts(OperationFact.REACHES_OUTSIDE)
            .command(OperationCommand.perSubject().execution(CommandExecution.OUTSIDE_TRANSACTION))
            .background(APP_STEPS)
            .register();

    /** Puts one address online that needs no workload: its website, then its certificate. */
    public static final Operation<Void, Address, Integer> PUT_ADDRESS_ONLINE =
        Operation.declare(HohenheimIds.id("put_address_online"))
            .label(copy("put_online"))
            .icon(Icon.of("rocket"))
            .noSubject()
            .gate(OperationGate.open())
            .input(OperationInput.of(ADDRESS_INPUT, Address.class, values -> new Address(values.get(SiteModel.NAME),
                values.get(HOSTNAME), values.get(SiteModel.UPSTREAM_KIND),
                SiteWrites.settings(values.get(SiteModel.SETTINGS.getName())), values.get(HTTPS))))
            .result(Integer.class)
            .facts(OperationFact.REACHES_OUTSIDE)
            .command(CmsCommands.EXTERNAL)
            .background(ADDRESS_STEPS)
            .register();

    private PutOnline() {
    }

    /** Forces the declarations to register before boot verifies their handlers. */
    public static void init() {
        PutOnlineHandlers.init();
    }

    /** @return whether the upstream kind with this stored id may be put online as an app of its own */
    static boolean offeredAsApp(@Nullable String kind) {
        Identifier id = kind == null ? null : Identifier.tryParse(kind);
        UpstreamKindInfo info = id == null ? null : UpstreamKinds.REGISTRY.get(id);
        return info != null && info.offeredAsApp();
    }

    private static @NonNull Select<String> httpsChoice() {
        return Select.of(HTTPS)
            .options(OptionSource.of(
                FieldOption.of(HTTPS_AUTOMATIC, copy("https_automatic")).withDescription(copy("https_automatic_help")),
                FieldOption.of(HTTPS_LATER, copy("https_later")).withDescription(copy("https_later_help"))))
            .presentation(Select.Presentation.CARDS)
            .clearable(false)
            .build();
    }

    static @NonNull Microcopy copy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "put_online");
    }
}
