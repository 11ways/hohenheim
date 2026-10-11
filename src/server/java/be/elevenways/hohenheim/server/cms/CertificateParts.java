package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.server.tls.CertificateStore;
import be.elevenways.hohenheim.server.tls.CertificateExpiry;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectArity;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.server.operation.RowDeleteOperations;
import be.elevenways.protoblast.common.time.RelativeTime;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.SortSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.data.RowScope;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FieldGroup;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.field.attributes.FieldAttributes;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.ui.Timezones;
import be.elevenways.zenit.common.validation.Violations;
import org.bouncycastle.openssl.PEMParser;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * TLS certificates: manual PEM uploads plus Let's Encrypt requests (via the
 * request page linked from the header). The internal ACME account row is
 * scoped out of every list/load.
 * UI copy retains the certificate catalog; names and coverage remain user data.
 *
 * @author Jelle De Loecker
 * @since 0.9.0
 */
public final class CertificateParts {

    /** Every certificate but the internal ACME account row; the /manage scope narrows this same base. */
    public static final RowScope ROWS = RowScope.within(HohenheimSources::notTheAcmeAccountRow);

    /** Canonical O2 delete: the same model removal and hooks as the generated legacy verb. */
    public static final Operation<Row, Void, Integer> DELETE = RowDeleteOperations.delete(CertificateModel.class,
        SubjectArity.ONE, OperationGate.permission(HohenheimSources.ADMIN_ACCESS));

    /** The certificate list's state column. */
    static final String STATE_COLUMN = "state";

    /** The names a certificate covers besides its own name: the name column's second line. */
    static final String OTHER_NAMES_COLUMN = "other_names";

    private CertificateParts() {}
    /**
     * Display-only form entries: VIRTUAL string fields, never schema columns.
     *
     * AIDEV-NOTE: the stored columns cannot be shown directly here. A readonly entry
     * renders its raw value and NOTHING when that value is null, which is what left the
     * renewal panel with three labels above empty boxes and the DNS publisher with a
     * label, a description and no control at all. These carry an already-resolved
     * sentence instead, so an absent value reads "None" / "Not scheduled" rather than as
     * a rendering bug. They are bound {@code alwaysReadonly} below, so the submit
     * pipeline strips them before any write and the missing columns are never touched.
     */
    private static final StringField COVERED_NAMES_DISPLAY = displayField("covered_names_display",
        "cert_domain_names", "coverage");
    private static final StringField EXPIRY_DISPLAY = displayField("expiry_display",
        "cert_expires_on", "coverage");
    private static final StringField CHALLENGE_DISPLAY = displayField("challenge_type_display",
        "cert_challenge_type", "renewal");
    private static final StringField DNS_PUBLISHER_DISPLAY = displayField("dns_publisher_display",
        "cert_dns_publisher", "renewal");
    private static final StringField RENEWAL_ERROR_DISPLAY = displayField("renewal_error_display",
        "cert_renewal_error", "renewal");
    private static final StringField NEXT_ATTEMPT_DISPLAY = displayField("next_attempt_display",
        "cert_next_attempt_at", "renewal");
    /**
     * The TXT records a manual DNS-01 order waits for; "Verify DNS and finish" continues it once they are published.
     */
    private static final StringField DNS_RECORDS_DISPLAY = displayField("dns_records_display",
        "cert_dns_records", "coverage");

    /** Wall-clock shape of {@code Dates.wallText}, which needs a RenderContext this hook has not. */
    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final FormSpec ADMIN_FORM = FormSpec.builder()
        // The create is the upload ("Get a certificate" orders one, "Upload a certificate" saves
        // your own); the header's request action stands beside it.
        .createTitle(HohenheimMicrocopy.CERTIFICATE.of("create_title"))
        .add(CertificateModel.NICE_NAME)
        .add(CertificateModel.CERTIFICATE_PEM)
        .add(CertificateModel.PRIVATE_KEY_PEM)
        .add(CertificateModel.AUTO_RENEW)
        .add(COVERED_NAMES_DISPLAY)
        .add(DNS_RECORDS_DISPLAY)
        .add(EXPIRY_DISPLAY)
        .add(CHALLENGE_DISPLAY)
        .add(DNS_PUBLISHER_DISPLAY)
        .add(RENEWAL_ERROR_DISPLAY)
        .add(CertificateModel.ERROR_COUNT)
        .add(NEXT_ATTEMPT_DISPLAY)
        // AIDEV-NOTE: coverage and renewal are STATUS the ACME machinery writes, never
        // authored here, so they sit in the side column beside the name and the key
        // material an operator actually edits (and reads first on the overview).
        .group(FieldGroup.of("coverage", HohenheimMicrocopy.CERTIFICATE.of("coverage"))
            .inSidebar())
        .group(FieldGroup.of("renewal", HohenheimMicrocopy.CERTIFICATE.of("renewal_status"))
            .inSidebar())
        .build();

    /** One virtual read-only entry: a label from the field catalog, no column behind it. */
    private static @NonNull StringField displayField(@NonNull String name, @NonNull String labelKey,
                                                     @NonNull String group) {
        return StringField.builder().name(name)
            .visibleIn(EditView.EDIT, EditView.DETAIL)
            .attribute(FieldAttributes.GROUP, group)
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of(labelKey))
            .build();
    }

    private static final TableSpec<Row> ADMIN_TABLE = TableSpec.<Row>builder()
        // AIDEV-NOTE: eight visible columns down to six. The first pairs answer ONE
        // question in one cell -- what does it cover, why is it in this state -- while
        // every date stands alone, because a subtext line is not sortable.
        .column(ColumnSpec.fromField(CertificateModel.NICE_NAME).filterable().subtext(OTHER_NAMES_COLUMN).build())
        .column(otherNamesColumn())
        .column(ColumnSpec.fromField(CertificateModel.DOMAIN_NAMES_TEXT).filterable().hidden().build())
        .column(ColumnSpec.fromField(CertificateModel.PROVIDER).filterable().build())
        // The state in words (failing, waiting for a DNS record, works and for how long), the column an operator
        // scans first; the stored status stays in the picker and the filter strip.
        .column(stateColumn())
        .column(ColumnSpec.fromField(CertificateModel.STATUS).filterable().hidden().build())
        .column(ColumnSpec.fromField(CertificateModel.RENEWAL_ERROR).filterable().hidden().build())
        .column(ColumnSpec.fromField(CertificateModel.CHALLENGE_TYPE).filterable().hidden().build())
        .column(ColumnSpec.fromField(CertificateModel.DNS_PUBLISHER).hidden().build())
        // AIDEV-NOTE: expiry once carried next_attempt_at as its subtext; the next
        // attempt became a column of its own instead, which is what makes it sortable:
        // "which renewal runs next" was previously unaskable. (The date cells ride the
        // shared datetime cell with dateStyle(ABSOLUTE) now, not a renderer partial.)
        .column(dateColumn(CertificateModel.ISSUED_ON).hidden().build())
        .column(dateColumn(CertificateModel.EXPIRES_ON).filterable().build())
        // Hidden by default since the date cells became two-line (stamp over relative):
        // the list still ran past a 1440px viewport, and the next attempt only carries
        // information while a renewal is failing, which Status already says.
        .column(dateColumn(CertificateModel.NEXT_ATTEMPT_AT).hidden().build())
        .column(ColumnSpec.fromField(CertificateModel.ERROR_COUNT).hidden().build())
        // Created at is in the picker, not the default view: measured on robbedoes after
        // the two-line date cells, the list still ran 73px past a 1440px viewport with it,
        // and a certificate list is read by "what expires next", so that is the sort.
        .column(dateColumn(CertificateModel.CREATED_AT).filterable().hidden().build())
        .filter(FilterSpec.leaf(CertificateModel.NICE_NAME, CoreTypes.CONTAINS)
            .label(FieldLabels.labelFor(CertificateModel.NICE_NAME)).build())
        .filter(FilterSpec.leaf(CertificateModel.PROVIDER, CoreTypes.EQUALS)
            .label(FieldLabels.labelFor(CertificateModel.PROVIDER)).build())
        .filter(FilterSpec.leaf(CertificateModel.DOMAIN_NAMES_TEXT, CoreTypes.CONTAINS)
            .label(FieldLabels.labelFor(CertificateModel.DOMAIN_NAMES_TEXT)).build())
        .filter(FilterSpec.leaf(CertificateModel.STATUS, CoreTypes.EQUALS)
            .label(FieldLabels.labelFor(CertificateModel.STATUS)).build())
        .filter(FilterSpec.leaf(CertificateModel.EXPIRES_ON, CoreTypes.BETWEEN, CoreTypes.GTE, CoreTypes.LTE)
            .label(FieldLabels.labelFor(CertificateModel.EXPIRES_ON)).build())
        .filter(FilterSpec.leaf(CertificateModel.CREATED_AT, CoreTypes.BETWEEN, CoreTypes.GTE, CoreTypes.LTE)
            .label(FieldLabels.labelFor(CertificateModel.CREATED_AT)).build())
        .defaultSort(SortSpec.asc(CertificateModel.EXPIRES_ON.getName()))
        .build();

    /** The hidden column behind the name's second line, {@link #otherNames}. */
    private static @NonNull ColumnSpec otherNamesColumn() {
        return ColumnSpec.virtual(OTHER_NAMES_COLUMN, HohenheimMicrocopy.CERTIFICATE.of("other_names_column")).hidden()
            .build();
    }

    /**
     * The names a certificate covers besides the name it is listed by, so a certificate named after its one domain
     * does not repeat it under itself.
     *
     * @return the other names comma-joined, or null when it covers none
     */
    static @Nullable String otherNames(@NonNull Row cert) {
        String name = cert.get(CertificateModel.NICE_NAME);
        List<String> others = new ArrayList<>();
        for (String covered : CertificateCoverage.namesOf(cert)) {
            if (name == null || !covered.equalsIgnoreCase(name.trim())) {
                others.add(covered);
            }
        }
        return others.isEmpty() ? null : String.join(", ", others);
    }

    /** The certificate's state column: a word and a line, {@link #stateCell}. */
    private static @NonNull ColumnSpec stateColumn() {
        return ColumnSpec.virtual(STATE_COLUMN, HohenheimMicrocopy.CERTIFICATE.of("state_column"))
            .renderer(HohenheimTemplateIds.CELL_STATE_LINE).build();
    }

    /**
     * What a certificate's state means to an operator, in a word and a line: a failing renewal and how long the
     * certificate still holds, a manual DNS order waiting for its record, one not issued yet, expired, stored as
     * working but not loaded by the proxy, expiring inside the expiry alert's window, or that it works and until when.
     *
     * AIDEV-NOTE: THE certificate state: the Certificates list, the dashboard's Certificates tile (a state that is not
     * {@link CertificateState#WORKS} needs a look) and the expiring item's window
     * ({@link AcmeService#EXPIRY_ALERT_DAYS}) read it, so the list never says "Works" beside a dashboard that counts
     * the certificate as needing a look. A renewal that failed is "failing" even while the old certificate still serves
     * (status active with an error count): that is the one an operator must act on before it expires; its last error
     * rides along as the cell's note. A stored active row the proxy did not load serves nobody, whatever
     * its expiry says.
     */
    static @NonNull StateLineCell stateCell(@NonNull Row cert) {
        return stateCell(cert, CertificateCoverage.loaded(cert));
    }

    /** {@link #stateCell(Row)} with whether the running proxy loaded the row. */
    static @NonNull StateLineCell stateCell(@NonNull Row cert, boolean loaded) {
        String status = cert.get(CertificateModel.STATUS);
        Integer errorCount = cert.get(CertificateModel.ERROR_COUNT);
        int errors = errorCount == null ? 0 : errorCount;
        Instant expires = cert.get(CertificateModel.EXPIRES_ON);
        Long days = expires == null ? null : CertificateExpiry.daysLeft(expires);
        if (CertificateModel.STATUS_ERROR.equals(status) || errors > 0) {
            Microcopy detail = days == null
                ? HohenheimMicrocopy.CERTIFICATE.of("state_failing_unissued")
                    .withArg("count", Math.max(errors, 1))
                : HohenheimMicrocopy.CERTIFICATE.of("state_failing_detail")
                    .withArg("count", Math.max(errors, 1)).withArg("expiry", CertificateExpiry.inSentence(expires));
            String error = cert.get(CertificateModel.RENEWAL_ERROR);
            return StateLineCell.of(CertificateState.RENEWAL_FAILING, detail)
                .withNote(error == null || error.isBlank() ? null : Microcopy.literal(error));
        }
        if (CertificateModel.STATUS_PENDING.equals(status)) {
            boolean manualDns = CertificateModel.CHALLENGE_DNS.equals(cert.get(CertificateModel.CHALLENGE_TYPE))
                && CertificateModel.DNS_PUBLISHER_MANUAL.equals(cert.get(CertificateModel.DNS_PUBLISHER));
            return manualDns
                ? StateLineCell.of(CertificateState.WAITING_DNS,
                    HohenheimMicrocopy.CERTIFICATE.of("state_waiting_dns_detail"))
                : StateLineCell.of(CertificateState.ISSUING, HohenheimMicrocopy.CERTIFICATE.of("state_issuing_detail"));
        }
        if (days != null && days < 0) {
            return StateLineCell.of(CertificateState.EXPIRED, CertificateExpiry.of(expires));
        }
        if (!loaded) {
            return StateLineCell.of(CertificateState.NOT_LOADED,
                HohenheimMicrocopy.CERTIFICATE.of("state_not_loaded_detail"));
        }
        boolean renews = Boolean.TRUE.equals(cert.get(CertificateModel.AUTO_RENEW))
            && CertificateModel.PROVIDER_LETSENCRYPT.equals(cert.get(CertificateModel.PROVIDER));
        if (days != null && days <= AcmeService.EXPIRY_ALERT_DAYS) {
            return StateLineCell.of(CertificateState.EXPIRING, HohenheimMicrocopy.CERTIFICATE
                .of(renews ? "state_expiring_renews_detail" : "state_expiring_upload_detail")
                .withArg("expiry", CertificateExpiry.inSentence(expires)));
        }
        Microcopy valid = days == null ? null
            : renews
                ? HohenheimMicrocopy.CERTIFICATE.of("state_renews_detail")
                    .withArg("expiry", CertificateExpiry.inSentence(expires))
                : CertificateExpiry.of(expires);
        return StateLineCell.of(CertificateState.WORKS, valid);
    }

    /**
     * A sortable date column reading absolute-first: the framework's own
     * datetime cell with {@code ColumnSpec.dateStyle(ABSOLUTE)} -- these
     * columns are planned against the calendar, not against "how long ago".
     */
    private static ColumnSpec.@NonNull Builder dateColumn(@NonNull DateTimeField field) {
        return ColumnSpec.fromField(field).sortable().dateStyle(ColumnSpec.DateStyle.ABSOLUTE);
    }

    /** Full installation surface: PEM authoring, read-first overview, and the canonical delete. */
    public static @NonNull PanelResource<Row> admin() {
        return entry(HohenheimIds.id("certificate"))
            .scope(ROWS)
            .reads(ResourceReads.rows().mapCells(CertificateParts::namesCell)
                .mapValues(Set.of(COVERED_NAMES_DISPLAY.getName(), DNS_RECORDS_DISPLAY.getName(),
                EXPIRY_DISPLAY.getName(),
                CHALLENGE_DISPLAY.getName(), DNS_PUBLISHER_DISPLAY.getName(), RENEWAL_ERROR_DISPLAY.getName(),
                NEXT_ATTEMPT_DISPLAY.getName()), CertificateParts::displayValues))
            .list(ResourceList.rows(ADMIN_TABLE).chrome(CmsSupport.WIDE_LIST).facets().ruleFilters()
                .search(CertificateModel.NICE_NAME, CertificateModel.DOMAIN_NAMES_TEXT)
                .computed(Objects.requireNonNull(ADMIN_TABLE.column(STATE_COLUMN)), (row, request) -> stateCell(row))
                .computed(Objects.requireNonNull(ADMIN_TABLE.column(OTHER_NAMES_COLUMN)),
                    (row, request) -> otherNames(row))
                .build())
            .form(ResourceForm.<Row>of(ADMIN_FORM).bindings(fieldBindings()).wideRecordPages()
                .landingTab(RecordOverview.SLUG).build())
            .writes(ResourceMutations.rows().create(call -> create(call.values()))
                .update(call -> { update(call.record(), call.values()); return null; }).delete(DELETE).build())
            .deleteConfirmation(DeleteConfirmation.<Row>of(deleteConfirmation())
                .forRow((row, request) -> deleteConfirmationFor(row)))
            .actions(actions())
            // AIDEV-NOTE: parity is the tab contract here: the legacy twin offered no contributed access tab.
            .tabs(ResourceTabs.<Row>of(List.of(RecordOverview.<Row>fields())).withHistory())
            .build();
    }

    /** Status-only tenant twin: its form contains no PEM, and it declares no write or download. */
    public static @NonNull PanelResource<Row> manage() {
        FormSpec form = FormSpec.builder().add(CertificateModel.NICE_NAME).add(CertificateModel.DOMAIN_NAMES_TEXT)
            .add(CertificateModel.STATUS).add(CertificateModel.EXPIRES_ON).add(CertificateModel.RENEWAL_ERROR).build();
        List<ResourceFieldBinding> bindings = new ArrayList<>();
        for (var entry : form.entries()) {
            bindings.add(ResourceFieldBinding.of(entry.name(), FieldAccess.alwaysReadonly()));
        }
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(CertificateModel.NICE_NAME).subtext(OTHER_NAMES_COLUMN).build())
            .column(otherNamesColumn())
            .column(ColumnSpec.fromField(CertificateModel.DOMAIN_NAMES_TEXT).hidden().build())
            .column(stateColumn())
            .column(ColumnSpec.fromField(CertificateModel.STATUS).hidden().build())
            .column(ColumnSpec.fromField(CertificateModel.RENEWAL_ERROR).hidden().build())
            .column(ColumnSpec.fromField(CertificateModel.EXPIRES_ON).build())
            .defaultSort(SortSpec.desc(CertificateModel.EXPIRES_ON.getName())).build();
        // A Domains cluster member: certificates are readable to any signed-in tenant.
        return ManageTwin.listed(entry(ManageTwin.id("certificate")), TenantScopes.CERTIFICATES, ResourceTabs.none(),
                access -> HohenheimAccess.isAdmin(access) || access.principalId() != null
                    || HohenheimAccess.reachesAny(access, CertificateModel.MODEL_ID, HohenheimCapabilities.VIEW))
            .reads(ResourceReads.rows().mapCells(CertificateParts::namesCell))
            .list(ResourceList.rows(table).chrome(CmsSupport.WIDE_LIST).facets().ruleFilters()
                .search(CertificateModel.NICE_NAME, CertificateModel.DOMAIN_NAMES_TEXT)
                .computed(Objects.requireNonNull(table.column(STATE_COLUMN)), (row, request) -> stateCell(row))
                .computed(Objects.requireNonNull(table.column(OTHER_NAMES_COLUMN)), (row, request) -> otherNames(row))
                .build())
            .form(ResourceForm.<Row>of(form).bindings(bindings).wideRecordPages().build())
            .build();
    }

    /** The identity and nav placement both twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull Identifier id) {
        return PanelResource.builder(id, HohenheimSlugs.CERTIFICATES, SubjectType.record(CertificateModel.MODEL_ID))
            .label(HohenheimMicrocopy.CERTIFICATE.of("plural"))
            .recordLabel(HohenheimMicrocopy.CERTIFICATE.of("singular"))
            .description(HohenheimMicrocopy.CERTIFICATE.of("nav_hint"))
            .icon(Icon.of("certificate")).navGroup(HohenheimPanel.NETWORK_GROUP).navOrder(20);
    }

    private static @NonNull ConfirmationSpec deleteConfirmation() {
        return DeleteConfirmation.body(HohenheimMicrocopy.CERTIFICATE.of("delete_confirm"));
    }

    /** The same warning NAMING the domains this certificate secures before its key is gone. */
    private static @NonNull ConfirmationSpec deleteConfirmationFor(@NonNull Row record) {
        String domains = DeleteImpact.join(CertificateCoverage.namesOf(record));
        if (domains.isEmpty()) {
            return deleteConfirmation();
        }
        return DeleteConfirmation.body(HohenheimMicrocopy.CERTIFICATE.of("delete_confirm_domains")
            .withArg("name", String.valueOf((Object) record.get(CertificateModel.NICE_NAME)))
            .withArg("domains", domains));
    }

    /** Coverage and renewal diagnostics are written by the ACME machinery, never by hand. */
    private static @NonNull List<ResourceFieldBinding> fieldBindings() {
        return List.of(
            ResourceFieldBinding.of(COVERED_NAMES_DISPLAY.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(DNS_RECORDS_DISPLAY.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(EXPIRY_DISPLAY.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(CHALLENGE_DISPLAY.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(DNS_PUBLISHER_DISPLAY.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(RENEWAL_ERROR_DISPLAY.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(CertificateModel.ERROR_COUNT.getName(), FieldAccess.alwaysReadonly()),
            ResourceFieldBinding.of(NEXT_ATTEMPT_DISPLAY.getName(), FieldAccess.alwaysReadonly()));
    }

    /**
     * The names a certificate covers as a list cell reads them ("starfleet.life, www.starfleet.life"): the stored text
     * is comma-joined data, which the name column's subtext would otherwise print verbatim.
     */
    private static @Nullable Object namesCell(@NonNull Row cert, @NonNull ColumnSpec column) {
        return CertificateModel.DOMAIN_NAMES_TEXT.getName().equals(column.name())
            ? String.join(", ", CertificateCoverage.namesOf(cert)) : null;
    }

    /**
     * Fill the display-only entries: what this certificate covers, when it expires, and
     * why the last renewal did or did not happen -- each as a sentence that says
     * something when the underlying column is empty.
     */
    private static @NonNull Map<String, Object> displayValues(@NonNull Row row,
                                                             @NonNull Map<String, Object> base) {
        Map<String, Object> values = new LinkedHashMap<>(base);
        List<String> names = CertificateCoverage.namesOf(row);
        values.put(COVERED_NAMES_DISPLAY.getName(),
            names.isEmpty() ? copy("coverage_none") : String.join(", ", names));
        values.put(DNS_RECORDS_DISPLAY.getName(), dnsRecordsText(row));
        values.put(EXPIRY_DISPLAY.getName(), instantText(row.get(CertificateModel.EXPIRES_ON),
            copy("expiry_none")));
        values.put(CHALLENGE_DISPLAY.getName(), orNone(
            CmsSupport.enumLabel(CertificateModel.CHALLENGE_TYPE, row.get(CertificateModel.CHALLENGE_TYPE))));
        values.put(DNS_PUBLISHER_DISPLAY.getName(), orNone(
            CmsSupport.enumLabel(CertificateModel.DNS_PUBLISHER, row.get(CertificateModel.DNS_PUBLISHER))));
        values.put(RENEWAL_ERROR_DISPLAY.getName(), orNone(row.get(CertificateModel.RENEWAL_ERROR)));
        Integer errors = row.get(CertificateModel.ERROR_COUNT);
        values.put(CertificateModel.ERROR_COUNT.getName(), errors != null ? errors : 0);
        // "Not scheduled" beside an enabled auto-renew read like a fault; the absence
        // sentence says what the schedule will DO, which depends on the switch.
        values.put(NEXT_ATTEMPT_DISPLAY.getName(), instantText(row.get(CertificateModel.NEXT_ATTEMPT_AT),
            copy(Boolean.TRUE.equals(row.get(CertificateModel.AUTO_RENEW)) ? "next_attempt_auto"
                    : "next_attempt_manual")));
        return values;
    }

    /** The waiting manual DNS-01 order's records as "name TXT value" entries, or the sentence that none waits. */
    private static @NonNull String dnsRecordsText(@NonNull Row row) {
        var proxy = ServerMain.getProxyServer();
        AcmeService.ManualDnsRequest pending = proxy == null ? null
            : proxy.getAcmeService().manualDnsRequestFor(row.get(CertificateModel.ID));
        if (pending == null || pending.records().isEmpty()) {
            return copy("dns_records_none");
        }
        StringBuilder text = new StringBuilder();
        for (var record : pending.records()) {
            if (!text.isEmpty()) {
                text.append("; ");
            }
            text.append(record.name()).append(" TXT ").append(record.value());
        }
        return text.toString();
    }

    /** An absolute wall-clock stamp plus the relative wording, or the absence sentence. */
    private static @NonNull String instantText(@Nullable Instant instant, @NonNull String absent) {
        if (instant == null) {
            return absent;
        }
        return WALL_CLOCK.format(instant.atZone(viewerZone()))
            + " (" + RelativeTime.ago(instant, CmsSupport.timeWording(RouteScope.currentConduit())) + ")";
    }

    /** The viewer's own zone, falling back to UTC when no request or cookie says otherwise. */
    private static @NonNull ZoneId viewerZone() {
        try {
            return ZoneId.of(Timezones.current(RouteScope.currentConduit()));
        } catch (RuntimeException unknownZone) {
            return ZoneOffset.UTC;
        }
    }

    private static @NonNull String orNone(@Nullable Object value) {
        String text = trimmed(value);
        return text.isEmpty() ? copy("value_none") : text;
    }

    private static @NonNull String copy(@NonNull String key) {
        return CmsSupport.resolvedTextOrDefault(HohenheimMicrocopy.CERTIFICATE.of(key));
    }

    /** An uploaded certificate: refused unless its certificate and key both parse; reachable from tests. */
    static @NonNull Object create(@NonNull Map<String, Object> submitted) {
        Map<String, Object> values = CmsSupport.mutable(submitted);
        describe(values, validatePems(values, null));
        Row row = Models.get(CertificateModel.class).createEmptyRow();
        values.forEach(row::set);
        row.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        row.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        row.set(CertificateModel.AUTO_RENEW, false);
        Models.get(CertificateModel.class).save(row);
        return row.get(CertificateModel.ID);
    }

    private static void update(@NonNull Row existing, @NonNull Map<String, Object> submitted) {
        Map<String, Object> values = CmsSupport.mutable(submitted);
        X509Certificate leaf = validatePems(values, existing);
        if (values.containsKey(CertificateModel.CERTIFICATE_PEM.getName())) {
            describe(values, leaf);
        }
        if (CertificateModel.DNS_PUBLISHER_MANUAL.equals(existing.get(CertificateModel.DNS_PUBLISHER))) {
            values.put(CertificateModel.AUTO_RENEW.getName(), false);
        }
        values.forEach(existing::set);
        Models.get(CertificateModel.class).save(existing);
    }

    /**
     * What an uploaded certificate says about itself, stored beside it: the names it covers and when it was issued and
     * expires, which the list's state, the expiry alert and the HTTPS cells read.
     *
     * AIDEV-NOTE: an upload once stored none of these, so it read "Works" with no expiry, never "Expiring",
     * and covered no name in the stored rows.
     */
    private static void describe(@NonNull Map<String, Object> values, @Nullable X509Certificate leaf) {
        if (leaf == null) {
            return;
        }
        values.put(CertificateModel.DOMAIN_NAMES_TEXT.getName(), String.join(",", CertificateStore.hostnamesOf(leaf)));
        values.put(CertificateModel.ISSUED_ON.getName(), leaf.getNotBefore().toInstant());
        values.put(CertificateModel.EXPIRES_ON.getName(), leaf.getNotAfter().toInstant());
    }

    /**
     * All three parts must be present and parseable, submitted or stored.
     *
     * AIDEV-NOTE: each read takes the STORED value when the write does not carry the key.
     * The inline cell lane hands updateRow a map holding EXACTLY ONE entry, so demanding
     * all three off that map refused every partial write with "cert_fields_required" --
     * about a certificate body that was sitting in the row all along. Re-parsing the
     * stored PEMs on an unrelated edit is deliberate: they are the record's whole point,
     * and a row that cannot parse must not be saved further.
     *
     * @param existing the stored certificate, or null on a create
     * @return the certificate's leaf (the first in the PEM), null when the PEM holds none
     */
    private static @Nullable X509Certificate validatePems(@NonNull Map<String, Object> coerced,
                                                          @Nullable Row existing) {
        String certPem = CmsSupport.textOf(coerced, existing, CertificateModel.CERTIFICATE_PEM);
        String keyPem = CmsSupport.textOf(coerced, existing, CertificateModel.PRIVATE_KEY_PEM);
        String name = CmsSupport.textOf(coerced, existing, CertificateModel.NICE_NAME);
        if (name.isEmpty() || certPem.isEmpty() || keyPem.isEmpty()) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("cert_fields_required"));
        }
        X509Certificate leaf;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            var certificates = cf.generateCertificates(new ByteArrayInputStream(certPem.getBytes()));
            leaf = certificates.isEmpty() ? null : (X509Certificate) certificates.iterator().next();
        } catch (Exception e) {
            throw Violations.ofField("certificate_pem", null,
                HohenheimMicrocopy.VIOLATIONS.of("cert_pem_invalid").withArg("detail", e.getMessage()));
        }
        try {
            new PEMParser(new StringReader(keyPem)).readObject();
        } catch (Exception e) {
            throw Violations.ofField("private_key_pem", null,
                HohenheimMicrocopy.VIOLATIONS.of("key_pem_invalid").withArg("detail", e.getMessage()));
        }
        return leaf;
    }


    private static @NonNull List<PanelAction<Row>> actions() {
        List<PanelAction<Row>> actions = new ArrayList<>();
        actions.add(PanelAction.<Row>link(HohenheimIds.id("download_certificate"), ActionPlacement.ROW)
            .label(HohenheimMicrocopy.CERTIFICATE.of("download"))
            .icon(Icon.of("download"))
            .route((row, request) -> HohenheimEndpoints.CERTIFICATES_DOWNLOAD
                .with(HohenheimEndpoints.CERT_ID, row.get(CertificateModel.ID)))
            // Exporting the PEM is rare next to edit/delete: overflow, not inline.
            .inlineInRow(false)
            .build());
        // Re-ordering a certificate is how a domain is added or HTTP-01/DNS-01 is switched: the row's own domain list
        // and challenge are readonly on the form because they describe what the CA actually issued.
        actions.add(CertificateOperations.reissueAction());
        actions.add(CertificateOperations.continueDnsAction());
        actions.add(CertificateOperations.requestAction());
        return actions;
    }

}
