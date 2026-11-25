package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.KindSettingsWrites.Kind;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.StaticSiteKind;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An app of one kind created or changed by its settings as an assistant names them, a {@link KindSettingsWrites}
 * family run through the instance panel resource's own create and update, so its placement, quota, image policy and
 * resize hooks decide as they do for the panel's form.
 *
 * AIDEV-NOTE: the variables map is the one kind setting left out, as the app's variables are written by
 * set_instance_variables (its rows win over the map at a deploy).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class AppKindWrites {

    /** The argument naming the host of a new app. */
    public static final String HOST = "host";

    /** The argument naming the project of a new app. */
    public static final String PROJECT = "project";

    /** The argument naming the backup target an app's backups go to. */
    public static final String BACKUP_TARGET = "backup_target";

    /** The argument naming the app whose serving image a Docker container runs. */
    public static final String IMAGE_APP = "image_app";

    /**
     * The records an app links to, each by the argument naming it and the app's own column it is written into: its
     * host and project on a create, its backup target, and for a Docker container the app whose image it runs. A link
     * a change sets or clears (null) is an administrator's alone.
     */
    private static final KindSettingsWrites.Links LINKS = KindSettingsWrites.links(
        KindSettingsWrites.link(HOST, HohenheimMicrocopy.HELP.of("app_create_host"), HostModel.MODEL_ID,
            InstanceModel.HOST_ID).onCreateOnly(),
        KindSettingsWrites.link(PROJECT, HohenheimMicrocopy.HELP.of("app_create_project"), ProjectModel.MODEL_ID,
            InstanceParts.PROJECT).onCreateOnly(),
        KindSettingsWrites.link(BACKUP_TARGET, HohenheimMicrocopy.HELP.of("app_backup_target"),
            BackupTargetModel.MODEL_ID, InstanceModel.BACKUP_TARGET_ID),
        KindSettingsWrites.link(IMAGE_APP, HohenheimMicrocopy.HELP.of("app_image_app"), InstanceModel.MODEL_ID,
            InstanceModel.IMAGE_APP_ID).onlyFor(DockerContainerKind.ID::equals));

    /**
     * Creates and changes run as one command of their caller, so the same request id answers the first result.
     *
     * AIDEV-NOTE: outside a transaction, as every instance command: the panel write inside opens its own and joins
     * this command (PartsWrites.asOneCommand), and a placement on a host is never rolled back by a later refusal. A
     * create has no subject to resolve its command's datasource from, so it names the instances' own.
     */
    private static final OperationCommand CREATES = OperationCommand.perVerb(InstanceOperations.KEYS)
        .onDatasource(() -> Models.get(InstanceModel.class).getResolvedDatasource())
        .execution(CommandExecution.OUTSIDE_TRANSACTION);
    private static final OperationCommand CHANGES = OperationCommand.perSubject(InstanceOperations.KEYS)
        .execution(CommandExecution.OUTSIDE_TRANSACTION);

    /** The app kinds: an app's name and crash policy besides its kind's settings. */
    private static final KindSettingsWrites.Family APPS = KindSettingsWrites.family("app", InstanceModel.MODEL_ID,
            InstanceOperations.INSTANCE, InstanceModel.ID, InstanceModel.KIND, InstanceModel.SETTINGS)
        .common(List.of(InstanceModel.NAME, InstanceModel.CRASH_POLICY))
        .settings(AppKindWrites::settings)
        .createEntries(kind -> LINKS.entries(kind, true))
        .changeEntries(kind -> LINKS.entries(kind, false))
        .changeGate(InstanceOperations.gate(HohenheimCapabilities.CONFIG))
        .commands(CREATES, CHANGES)
        .writers(AppKindWrites::created, AppKindWrites::changed);

    public static final Kind APPLICATION = declare(ApplicationKind.ID, ApplicationKind.SETTINGS_SCHEMA);
    public static final Kind DOCKER_CONTAINER = declare(DockerContainerKind.ID, DockerContainerKind.SETTINGS_SCHEMA);
    public static final Kind STATIC_SITE = declare(StaticSiteKind.ID, StaticSiteKind.SETTINGS_SCHEMA);

    private AppKindWrites() {
    }

    /** @return the kind's settings an assistant gives by name: all but the variables map */
    private static @NonNull List<Field<?, ?>> settings(@NonNull Schema schema) {
        List<Field<?, ?>> settings = new ArrayList<>();
        for (Field<?, ?> field : schema.getFields().values()) {
            if (!InstanceKindFields.ENVIRONMENT_VARIABLES.equals(field.getName())) settings.add(field);
        }
        return settings;
    }

    private static @NonNull Kind declare(@NonNull Identifier kind, @NonNull Schema schema) {
        String word = kind.getPath();
        return APPS.kind(kind, schema, word + "_app", "app_" + word + "_set",
            HohenheimMicrocopy.HOHENHEIM.of("app_thing_" + word));
    }

    /** @return every kind's writes, in the order their tools are offered */
    public static @NonNull List<Kind> all() {
        return APPS.kinds();
    }

    /**
     * Creates the app through the operator's instance resource; nothing is deployed.
     *
     * @throws DomainRefusal FORBIDDEN, in its own words, for a caller who is no administrator
     */
    private static @NonNull Integer created(@NonNull Kind kind, OperationInput.@NonNull Values input,
                                            @NonNull AccessContext caller) {
        if (!HohenheimAccess.isAdmin(caller)) {
            Microcopy words = HohenheimMicrocopy.VIOLATIONS.of("app_create_needs_admin");
            throw new DomainRefusal(ZenitRefusalReason.FORBIDDEN, words.describe(), words);
        }
        Map<String, Object> raw = APPS.created(kind, input);
        LINKS.columns().forEach((argument, column) -> {
            Object linked = input.get(argument);
            if (linked != null) raw.put(column.getInputName(), Integer.valueOf(String.valueOf(linked)));
        });
        Map<String, Object> settings = RawValues.map(raw.get(InstanceModel.SETTINGS.getName()));
        raw.put(InstanceModel.SETTINGS.getName(), KindSettingsWrites.posted(kind.schema().getFields().values(),
            settings));
        Panel panel = ApiConduits.adminPanel();
        Object key = KindSettingsWrites.speaking(LINKS.columns(),
            () -> ResourceWrites.create(panel, instances(panel), raw, caller));
        return Integer.valueOf(String.valueOf(key));
    }

    /**
     * Writes what the call sent over the app as it stands, through the caller's instance resource: an administrator's
     * the whole form, anyone else's only the name and crash policy. An app turned into this kind keeps the settings
     * this kind declares, the panel's kind change judging whether its host and state allow it.
     *
     * @return the names of the settings written
     * @throws Violations for a setting only an administrator changes
     */
    private static @NonNull String changed(@NonNull Kind kind, @NonNull Row app, OperationInput.@NonNull Values input,
                                           @NonNull Set<String> sent, boolean turned,
                                           @NonNull AccessContext caller) {
        Map<String, Object> raw = new LinkedHashMap<>();
        List<String> written = new ArrayList<>();
        for (Field<?, ?> field : APPS.commonFields()) {
            if (sent.contains(field.getInputName())) {
                raw.put(field.getInputName(), input.get(field.getInputName()));
                written.add(field.getInputName());
            }
        }
        boolean admin = HohenheimAccess.isAdmin(caller);
        for (KindSettingsWrites.Link link : LINKS.changeable()) {
            if (!sent.contains(link.argument())) continue;
            if (!admin) throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("tenant_field_frozen"));
            Object linked = input.get(link.argument());
            raw.put(link.column().getName(), linked == null ? null : Integer.valueOf(String.valueOf(linked)));
            written.add(link.argument());
        }
        // The form posts only what this kind declares (its settings sub-form is closed-world); the instance resource
        // puts the stored keys it does not declare back unless the kind changes (InstanceParts.keepUndeclaredSettings).
        // The stored map goes by the fields' stored names, the form by their input names.
        Map<String, Object> kept = RawValues.map(app.get(InstanceModel.SETTINGS));
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Field<?, ?> field : kind.schema().getFields().values()) {
            if (kept.containsKey(field.getName())) settings.put(field.getInputName(), kept.get(field.getName()));
        }
        boolean settingsSent = turned;
        for (Field<?, ?> field : kind.settings()) {
            if (!sent.contains(field.getInputName())) continue;
            // Null clears a setting by taking its key away, so its kind's default applies again, never a stored null.
            Object value = input.get(field.getInputName());
            if (value == null) settings.remove(field.getInputName());
            else settings.put(field.getInputName(), value);
            written.add(field.getInputName());
            settingsSent = true;
        }
        if (turned) {
            raw.put(InstanceModel.KIND.getName(), kind.id().toString());
            written.add(0, InstanceModel.KIND.getName());
        }
        if (settingsSent) {
            if (!admin) throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("tenant_field_frozen"));
            raw.put(InstanceModel.SETTINGS.getName(), KindSettingsWrites.posted(kind.schema().getFields().values(),
                settings));
        }
        if (raw.isEmpty()) return "";
        Panel panel = admin ? ApiConduits.adminPanel() : ApiConduits.managePanel();
        KindSettingsWrites.speaking(LINKS.columns(), () -> ResourceWrites.update(panel, instances(panel),
            Objects.requireNonNull(app.get(InstanceModel.ID)), app, raw, caller));
        return String.join(", ", written);
    }

    /** @return the panel's instance resource, the one whose form the panel posts */
    private static @NonNull PanelResource<Row> instances(@NonNull Panel panel) {
        return Objects.requireNonNull(CmsSupport.declaredRowEntry(panel, HohenheimSlugs.INSTANCES),
            "the panel declares its instances");
    }
}
