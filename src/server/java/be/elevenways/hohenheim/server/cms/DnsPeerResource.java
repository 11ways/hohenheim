package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.server.dns.DnsFederationKeys;
import be.elevenways.hohenheim.server.dns.DnsPeerApi;
import be.elevenways.hohenheim.server.dns.DnsTsig;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldAccess;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Federation peers: other Hohenheim instances (or plain nameservers) this
 * server transfers zones to or from. A peer bundles the DNS zone-transfer
 * channel (TSIG + host) and, for a Hohenheim peer, the HTTPS admin credentials
 * used to forward edits of zones that peer owns.
 */
public final class DnsPeerResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(DnsPeerModel.NAME)
        .add(DnsPeerModel.PEER_TYPE)
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
        .column(ColumnSpec.fromField(DnsPeerModel.PEER_TYPE).filterable().build())
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
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "dns_peer"); }
    @Override public @NonNull String slug() { return "dns-peers"; }
    @Override public @NonNull Model model() { return Models.get(DnsPeerModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /** Name and transfer host; both TSIG secrets are secret columns. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(DnsPeerModel.NAME, DnsPeerModel.TRANSFER_HOST);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.NETWORK_GROUP; }
    @Override public int navOrder() { return 40; }

    /**
     * Demoted out of the sidebar, so this sentence reaches a reader through the panel
     * index and the related-pages menu of the list that names it.
     */
    @Override public @Nullable Microcopy description() { return CmsSupport.navHint("dns_peer"); }

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

    /**
     * The edit-forwarding credentials exist only on a Hohenheim peer: on a peer typed
     * as a plain nameserver they can never be written.
     *
     * AIDEV-NOTE: the null-record arm returns EDITABLE rather than the usual fail-closed
     * HIDDEN, and that is deliberate: the null record IS the create form, where there is
     * no stored type yet and no record data to leak -- hiding them there would make a
     * Hohenheim peer impossible to create in one pass.
     *
     * AIDEV-NOTE: this binding is a WRITE guard, not a display one. The form renderer
     * resolves field access WITHOUT the record
     * ({@code ResourceFormPageRenderer} hands the translator only {@code fieldAccessByPath}
     * plus the AccessContext), so a record-aware decision cannot hide a field on the
     * detail form today -- the inputs still render on a nameserver peer, and what the
     * type actually enforces is {@code enforceFieldAccess} stripping the submitted values
     * plus {@link #validate}. Making the render record-aware is a zenit-cms change.
     */
    @Override
    public @NonNull List<ResourceFieldBinding> fieldBindings() {
        FieldAccess hohenheimOnly = FieldAccess.customRecordAware((ctx, record) ->
            !(record instanceof Row peer) || DnsPeerModel.isHohenheim(peer)
                ? FieldAccess.Decision.EDITABLE
                : FieldAccess.Decision.HIDDEN);
        return List.of(
            ResourceFieldBinding.of(DnsPeerModel.BASE_URL.getName(), hohenheimOnly),
            ResourceFieldBinding.of(DnsPeerModel.API_KEY.getName(), hohenheimOnly));
    }

    /**
     * Exchanges a fresh shared TSIG key with a Hohenheim peer, writing both sides.
     *
     * AIDEV-NOTE: the minted secret is never toasted, logged or echoed -- unlike an API
     * key, BOTH ends store this one, so there is no human who has to read it and a
     * one-time disclosure would only be a place for it to leak.
     */
    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "negotiate_transfer_key"))
            .label(Microcopy.of("negotiate_key").withFilter("scope", "dns_peer"))
            .description(Microcopy.of("negotiate_key_hint").withFilter("scope", "dns_peer"))
            .icon(Icon.of("key"))
            .visibleFor((row, ctx) -> DnsPeerModel.isHohenheim(row))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("negotiate_key").withFilter("scope", "dns_peer"))
                .body(Microcopy.of("negotiate_key_confirm").withFilter("scope", "dns_peer"))
                .build())
            .handler((row, ctx) -> negotiate(row))
            .build());
        return actions;
    }

    private static @NonNull CmsActionResult negotiate(@NonNull Row peer) {
        DnsPeerApi api = DnsPeerApi.forPeer(peer);
        if (api == null) {
            // A nameserver peer, or a Hohenheim peer whose credentials were cleared:
            // there is no channel to negotiate over, so say so instead of failing later.
            return CmsActionResult.errorToast(
                Microcopy.of("negotiate_key_unsupported").withFilter("scope", "dns_peer"));
        }
        String localName = DnsFederationKeys.localName();
        String peerName = String.valueOf(peer.get(DnsPeerModel.NAME));
        String keyName = DnsFederationKeys.keyNameFor(localName, peerName);
        String secret = DnsFederationKeys.mintSecret();

        String confirmed;
        try {
            confirmed = api.negotiateTransferKey(localName, keyName,
                DnsFederationKeys.ALGORITHM, secret);
        }
        catch (DnsPeerApi.PeerApiException refused) {
            return CmsActionResult.errorToast(
                Microcopy.of("negotiate_key_failed").withFilter("scope", "dns_peer")
                    .withArg("reason", refused.getMessage() != null
                        ? refused.getMessage() : refused.toString()));
        }
        if (!keyName.equals(confirmed)) {
            // The peer stored the name IT was told; a different one back means the two
            // sides would look each other up under different names and never transfer.
            return CmsActionResult.errorToast(
                Microcopy.of("negotiate_key_mismatch").withFilter("scope", "dns_peer"));
        }

        peer.set(DnsPeerModel.TSIG_KEY_NAME, keyName);
        peer.set(DnsPeerModel.TSIG_ALGORITHM, DnsFederationKeys.ALGORITHM);
        peer.set(DnsPeerModel.TSIG_SECRET, secret);
        Models.get(DnsPeerModel.class).save(peer);
        return CmsActionResult.refreshWithToast(
            Microcopy.of("negotiate_key_done").withFilter("scope", "dns_peer")
                .withArg("key", keyName));
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        validate(CmsSupport.mutable(coerced), null);
        return super.persistRow(coerced, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        validate(CmsSupport.mutable(coerced), existing);
        super.updateRow(existing, coerced, accessContext);
    }

    /**
     * The declared type decides which channel must be complete: a Hohenheim peer without
     * admin credentials forwards nothing, and a plain nameserver without a transfer host
     * is a peer this server can never reach.
     */
    private static void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        String name = value(coerced, existing, DnsPeerModel.NAME);
        if (name.isEmpty()) {
            throw Violations.ofField("name", name, CmsSupport.violationText("name_required"));
        }
        String algorithm = value(coerced, existing, DnsPeerModel.TSIG_ALGORITHM);
        if (!algorithm.isEmpty() && !DnsTsig.isSupportedAlgorithm(algorithm)) {
            throw Violations.ofField("tsig_algorithm", algorithm,
                CmsSupport.violationText("dns_tsig_algorithm"));
        }
        Object portValue = coerced.get("transfer_port");
        if (portValue instanceof Integer port && (port < 1 || port > 65535)) {
            throw Violations.ofField("transfer_port", port, CmsSupport.violationText("dns_port_range"));
        }

        String type = DnsPeerModel.TYPE_HOHENHEIM.equals(value(coerced, existing, DnsPeerModel.PEER_TYPE))
            ? DnsPeerModel.TYPE_HOHENHEIM : DnsPeerModel.TYPE_NAMESERVER;
        if (DnsPeerModel.TYPE_HOHENHEIM.equals(type)) {
            if (value(coerced, existing, DnsPeerModel.BASE_URL).isEmpty()) {
                throw Violations.ofField("base_url", "",
                    CmsSupport.violationText("dns_peer_base_url_required"));
            }
            if (value(coerced, existing, DnsPeerModel.API_KEY).isEmpty()) {
                throw Violations.ofField("api_key", "",
                    CmsSupport.violationText("dns_peer_api_key_required"));
            }
        }
        else if (value(coerced, existing, DnsPeerModel.TRANSFER_HOST).isEmpty()) {
            throw Violations.ofField("transfer_host", "",
                CmsSupport.violationText("dns_peer_transfer_host_required"));
        }
    }

    /** The submitted value, falling back to the stored one for a field the form omitted. */
    private static @NonNull String value(@NonNull Map<String, Object> coerced, @Nullable Row existing,
                                         @NonNull Field<?, ?> field) {
        Object submitted = coerced.get(field.getName());
        if (submitted != null && !String.valueOf(submitted).trim().isEmpty()) {
            return String.valueOf(submitted).trim();
        }
        if (coerced.containsKey(field.getName())) {
            // Explicitly submitted blank: only a stored SECRET survives it (the form
            // sends secrets back empty to mean "unchanged"), everything else is a clear.
            if (!field.isSecret() || existing == null) {
                return "";
            }
        }
        Object stored = existing != null ? existing.get(field.getName()) : null;
        return stored != null ? String.valueOf(stored).trim() : "";
    }
}
