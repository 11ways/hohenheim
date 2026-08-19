package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/**
 * Federation peers: other Hohenheim instances (or plain nameservers) this
 * server transfers zones to or from. A peer bundles the DNS zone-transfer
 * channel (TSIG + host) and, for a Hohenheim peer, the HTTPS admin credentials
 * used to forward edits of zones that peer owns.
 */
public final class DnsPeerResource extends RowResource {

    private static final Set<String> ALGORITHMS = Set.of(
        "hmac-sha256", "hmac-sha512", "hmac-sha384", "hmac-sha224", "hmac-sha1");

    private final FormSpec formSpec = FormSpec.builder()
        .add(DnsPeerModel.NAME)
        .add(DnsPeerModel.TRANSFER_HOST)
        .add(DnsPeerModel.TRANSFER_PORT)
        .add(DnsPeerModel.TSIG_KEY_NAME)
        .add(DnsPeerModel.TSIG_ALGORITHM)
        .add(DnsPeerModel.TSIG_SECRET)
        .add(DnsPeerModel.BASE_URL)
        .add(DnsPeerModel.API_KEY)
        .add(DnsPeerModel.ENABLED)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(DnsPeerModel.NAME).filterable().subtext("transfer_host").build())
        .column(ColumnSpec.fromField(DnsPeerModel.TRANSFER_HOST).hidden().build())
        // The key NAME (never the secret) is what the other side's operator must be told.
        .column(ColumnSpec.fromField(DnsPeerModel.TSIG_KEY_NAME).copyable().build())
        .column(ColumnSpec.fromField(DnsPeerModel.ENABLED).filterable().build())
        .filter(FilterSpec.forField(DnsPeerModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(DnsPeerModel.NAME)).build())
        .filter(FilterSpec.forField(DnsPeerModel.ENABLED, FilterSpec.Kind.BOOLEAN)
            .label(FieldLabels.labelFor(DnsPeerModel.ENABLED)).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "dns_peer"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "dns_peer"); }
    @Override public @NonNull String slug() { return "dns-peers"; }
    @Override public @NonNull Model model() { return Models.get(DnsPeerModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }

    /** Name and transfer host; both TSIG secrets are secret columns. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(DnsPeerModel.NAME, DnsPeerModel.TRANSFER_HOST);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.NETWORK_GROUP; }
    @Override public int navOrder() { return 40; }

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("handshake"); }

    /**
     * The name only.
     *
     * AIDEV-NOTE: TRANSFER_HOST and TRANSFER_PORT are the live AXFR endpoint this server
     * ships zone data to, and the TSIG triple is the authentication identity it ships it
     * under -- retyping either in a cell re-points or unauthenticates a running transfer
     * relationship. ENABLED is excluded because it arms that relationship. The peer's name
     * is the one thing about it that is purely operator wording.
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(DnsPeerModel.NAME);
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        validate(CmsSupport.mutable(coerced));
        return super.persistRow(coerced, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        validate(CmsSupport.mutable(coerced));
        super.updateRow(existing, coerced, accessContext);
    }

    private static void validate(@NonNull Map<String, Object> coerced) {
        Object nameValue = coerced.get("name");
        String name = nameValue != null ? String.valueOf(nameValue).trim() : "";
        if (name.isEmpty()) {
            throw Violations.ofField("name", name, CmsSupport.violationText("name_required"));
        }
        Object algorithmValue = coerced.get("tsig_algorithm");
        if (algorithmValue != null && !String.valueOf(algorithmValue).isBlank()
                && !ALGORITHMS.contains(String.valueOf(algorithmValue).trim().toLowerCase(Locale.ROOT))) {
            throw Violations.ofField("tsig_algorithm", algorithmValue,
                CmsSupport.violationText("dns_tsig_algorithm"));
        }
        Object portValue = coerced.get("transfer_port");
        if (portValue instanceof Integer port && (port < 1 || port > 65535)) {
            throw Violations.ofField("transfer_port", port, CmsSupport.violationText("dns_port_range"));
        }
    }
}
