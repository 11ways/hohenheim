package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationForms;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.PatchBase;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A site's address created or changed by its settings as an assistant names them, through the address panel
 * resource's own create and save, so the route claims, the quarantine, the passthrough refusals and the tenant freeze
 * decide as they do for the panel's form.
 *
 * AIDEV-NOTE: the entries are the address form's own fields (DomainParts.adminFormSpec), the site and the pinned
 * certificate taken by their codes. Someone who is no administrator writes through the /manage twin, whose form takes
 * fewer fields: one it does not take is refused there, and TenantWrites freezes the rest on every writer anyway.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class AddressWrites {

    /** The argument naming the site an address belongs to. */
    public static final String SITE = "site";

    /** The argument pinning the certificate an address serves. */
    public static final String CERTIFICATE = "certificate";

    /** The address's own settings, as its form names them. */
    private static final List<Field<?, ?>> SETTINGS = List.of(SiteDomainModel.HOSTNAME, SiteDomainModel.MATCH_TYPE,
        SiteDomainModel.PATH, SiteDomainModel.STRIP_PATH, SiteDomainModel.LISTEN_ON, SiteDomainModel.FORCE_SSL,
        SiteDomainModel.HSTS_ENABLED, SiteDomainModel.HSTS_SUBDOMAINS, SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT,
        SiteDomainModel.CUSTOM_HEADERS, SiteDomainModel.RESPONSE_HEADERS);

    /** The record each linking argument names, by the address's own column; a refusal names it back. */
    private static final Map<String, Field<?, ?>> LINKS = Map.of(SITE, SiteDomainModel.SITE_ID,
        CERTIFICATE, SiteDomainModel.CERTIFICATE_ID);

    /** A create has no subject to key its command by: the request id alone answers the first result. */
    private static final OperationCommand CREATES = OperationCommand.perVerb().onDatasource("default");

    /** Adds an address to a site. */
    public static final Operation<Void, OperationInput.Values, Integer> CREATE =
        Operation.declare(HohenheimIds.id("create_address"))
            .label(HohenheimMicrocopy.HOHENHEIM.of("create_address"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("create_address_description"))
            .noSubject()
            // The address resource's create decides who may: a site the caller manages (SiteParts.childAuthority).
            .gate(OperationGate.open())
            .input(OperationInput.derived(form(true)))
            .result(Integer.class)
            .createdRecord(SiteDomainModel.MODEL_ID, id -> id)
            .facts(OperationFact.IDEMPOTENT)
            .command(CREATES)
            .register();

    /** Changes what the call sends of an address; every other setting stays as it is. */
    public static final Operation<Row, OperationInput.Values, String> CHANGE =
        Operation.declare(HohenheimIds.id("change_address"))
            .label(HohenheimMicrocopy.HOHENHEIM.of("change_address"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("change_address_description"))
            .one(CertificateOperations.DOMAIN)
            .gate(OperationGate.open())
            .input(OperationInput.derived(form(false)))
            .result(String.class)
            .patchable()
            .facts(OperationFact.IDEMPOTENT)
            .command(CmsCommands.TRANSACTIONAL)
            .register();

    static {
        OperationHandlers.attach(CREATE).handle(call -> created(Objects.requireNonNull(call.input()),
            Objects.requireNonNull(call.access(), "a create has its caller")));
        OperationHandlers.attach(CHANGE)
            .patchBase(PatchBase.ofValues(CHANGE, (address, access) -> stored(Objects.requireNonNull(address))))
            .handle(call -> changed(call.subject(), Objects.requireNonNull(call.input()), call.patched(),
                Objects.requireNonNull(call.access(), "a change has its caller")));
    }

    private AddressWrites() {
    }

    /** Installs the handlers; idempotent through the class initializer. */
    public static void init() {
    }

    /** @param creating whether the form is the create's, which alone names the site */
    private static @NonNull FormSpec form(boolean creating) {
        FormSpec.Builder form = FormSpec.builder();
        if (creating) {
            form.add(OperationForms.recordId(SITE, HohenheimMicrocopy.HELP.of("address_site"), SiteModel.MODEL_ID,
                false));
        }
        for (Field<?, ?> field : SETTINGS) form.add(KindSettingsWrites.entryOf(field));
        form.add(OperationForms.recordId(CERTIFICATE, HohenheimMicrocopy.HELP.of("address_certificate"),
            CertificateModel.MODEL_ID, false));
        return form.build();
    }

    /**
     * Adds the address through the caller's address resource.
     *
     * AIDEV-NOTE: a setting left at its field's default is not posted, so the /manage twin's smaller form takes a
     * create that names only what it offers; the row create stores the same default either way.
     */
    private static @NonNull Integer created(OperationInput.@NonNull Values input, @NonNull AccessContext caller) {
        Map<String, Object> raw = new LinkedHashMap<>();
        if (input.get(SITE) != null) raw.put(SiteDomainModel.SITE_ID.getName(), String.valueOf(input.get(SITE)));
        for (Field<?, ?> field : SETTINGS) {
            Object value = input.get(field.getInputName());
            if (value != null && !value.equals(field.getDefaultValue())) raw.put(field.getName(), value);
        }
        if (input.get(CERTIFICATE) != null) {
            raw.put(SiteDomainModel.CERTIFICATE_ID.getName(), String.valueOf(input.get(CERTIFICATE)));
        }
        Panel panel = panelOf(caller);
        Object key = KindSettingsWrites.speaking(SETTINGS, "", LINKS, () -> ResourceWrites.create(panel,
            addresses(panel), KindSettingsWrites.posted(SETTINGS, raw), caller));
        return Integer.valueOf(String.valueOf(key));
    }

    /**
     * Writes only what the call sent over the address, through the caller's address resource.
     *
     * @return the names of the settings written
     */
    private static @NonNull String changed(@NonNull Row address, OperationInput.@NonNull Values input,
                                           @Nullable Set<String> patched, @NonNull AccessContext caller) {
        Set<String> sent = patched != null ? patched : input.coerced().keySet();
        Map<String, Object> raw = new LinkedHashMap<>();
        for (String name : sent) {
            Object value = input.get(name);
            if (CERTIFICATE.equals(name)) {
                raw.put(SiteDomainModel.CERTIFICATE_ID.getName(), value == null ? null : String.valueOf(value));
            } else {
                raw.put(KindSettingsWrites.storedName(SETTINGS, name), value);
            }
        }
        String code = RecordCodes.codeUnjudged(SiteDomainModel.MODEL_ID, String.valueOf(address.get(SiteDomainModel
            .ID)));
        if (raw.isEmpty()) return KindSettingsWrites.changedLine(code, "");
        Panel panel = panelOf(caller);
        KindSettingsWrites.speaking(SETTINGS, "", LINKS, () -> ResourceWrites.update(panel, addresses(panel),
            Objects.requireNonNull(address.get(SiteDomainModel.ID)), address,
            KindSettingsWrites.posted(SETTINGS, raw),
            caller));
        return KindSettingsWrites.changedLine(code, String.join(", ", sent));
    }

    /** @return the address's stored settings, the patch a change lands on */
    private static @NonNull Map<String, Object> stored(@NonNull Row address) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field<?, ?> field : SETTINGS) values.put(field.getInputName(), address.get(field.getName()));
        return values;
    }

    /** @return the operator's panel for an administrator, the /manage panel for anyone else */
    private static @NonNull Panel panelOf(@NonNull AccessContext caller) {
        return HohenheimAccess.isAdmin(caller) ? ApiConduits.adminPanel() : ApiConduits.managePanel();
    }

    /** @return the panel's address resource, the one whose form the panel posts */
    private static @NonNull PanelResource<Row> addresses(@NonNull Panel panel) {
        return Objects.requireNonNull(CmsSupport.declaredRowEntry(panel, HohenheimSlugs.DOMAINS),
            "the panel declares its addresses");
    }
}
