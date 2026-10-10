package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.dns.DnsPeerKeyResponse;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.server.dns.DnsFederationKeys;
import be.elevenways.hohenheim.server.dns.DnsPeerApi;
import be.elevenways.hohenheim.server.dns.DnsTsig;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.protoblast.common.typed.CoreTypes;
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

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * Federation peers: other Hohenheim instances (or plain nameservers) this
 * server transfers zones to or from. A peer bundles the DNS zone-transfer
 * channel (TSIG + host) and, for a Hohenheim peer, the HTTPS admin credentials
 * used to forward edits of zones that peer owns.
 */
public final class DnsPeerParts {

    public static @NonNull PanelResource<Row> admin() {
        DnsOperations.init();
        DnsPeerParts parts = new DnsPeerParts();
        return PanelResource.builder(parts.id(), parts.slug(), DnsOperations.PEER)
            .label(parts.label()).recordLabel(parts.recordLabel()).description(parts.description())
            .icon(parts.icon()).navGroup(parts.navGroup()).navOrder(parts.navOrder()).showInNav(false)
            .standsUnder(HohenheimSlugs.DNS_ZONES)
            .reads(ResourceReads.rows())
            .list(ResourceList.rows(parts.tableSpec()).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(parts.searchFields().toArray(Field<?, ?>[]::new)).build())
            .form(ResourceForm.<Row>of(parts.formSpec())
                .inlineEditable(parts.inlineEditableFields().toArray(Field<?, ?>[]::new)).build())
            .writes(ResourceMutations.rows().create(call -> parts.persistRow(call.values(), call.access()))
                .update(call -> { parts.updateRow(call.record(), call.values(), call.access()); return null; })
                .delete(DnsOperations.DELETE_PEER).build())
            .deleteConfirmation(DeleteConfirmation.<Row>of(parts.deleteConfirmation())
                .forRow((row, request) -> parts.deleteConfirmationFor(row)))
            .actions(List.of(PanelAction.<Row, CmsActionResult>places(DnsOperations.NEGOTIATE_KEY,
                ActionPlacement.ROW, (request, result) -> result.value())
                .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.DNS_PEER.of("negotiate_key"),
                    HohenheimMicrocopy.DNS_PEER.of("negotiate_key_confirm"), ActionStyle.DEFAULT)).build()))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions()).build();
    }

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
        .filter(FilterSpec.leaf(DnsPeerModel.NAME, CoreTypes.CONTAINS)
            .label(FieldLabels.labelFor(DnsPeerModel.NAME)).build())
        .filter(FilterSpec.leaf(DnsPeerModel.ENABLED, CoreTypes.IS_TRUE, CoreTypes.IS_FALSE)
            .label(FieldLabels.labelFor(DnsPeerModel.ENABLED)).build())
        .build();

    public @NonNull Identifier id() { return HohenheimIds.id("dns_peer"); }
    public @NonNull Microcopy label() { return HohenheimMicrocopy.DNS_PEER.of("plural"); }
    public @NonNull Microcopy recordLabel() { return HohenheimMicrocopy.DNS_PEER.of("singular"); }
    public @NonNull String slug() { return HohenheimSlugs.DNS_PEERS; }
    public @NonNull Model model() { return Models.get(DnsPeerModel.class); }
    public @NonNull FormSpec formSpec() { return this.formSpec; }
    public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }

    /** Name and transfer host; both TSIG secrets are secret columns. */
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(DnsPeerModel.NAME, DnsPeerModel.TRANSFER_HOST);
    }

    public @NonNull NavGroup navGroup() { return HohenheimPanel.NETWORK_GROUP; }
    public int navOrder() { return 40; }

    /**
     * Demoted out of the sidebar, so this sentence reaches a reader through the panel
     * index and the related-pages menu of the list that names it.
     */
    public @Nullable Microcopy description() { return CmsSupport.navHint(HohenheimMicrocopy.DNS_PEER); }

    public @NonNull Icon icon() { return Icon.of("handshake"); }

    /**
     * The name only.
     *
     * AIDEV-NOTE: TRANSFER_HOST and TRANSFER_PORT are the live AXFR endpoint this server
     * ships zone data to, and the TSIG triple is the authentication identity it ships it
     * under -- retyping either in a cell re-points or unauthenticates a running transfer
     * relationship. ENABLED is excluded because it arms that relationship. The peer's name
     * is the one thing about it that is purely operator wording.
     */
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(DnsPeerModel.NAME);
    }

    // AIDEV-NOTE: there is deliberately NO record-aware FieldAccess hiding base_url/api_key
    // on a nameserver peer. A binding was the wrong seam: it keys on the STORED record while
    // validate keys on the SUBMITTED type, so switching a peer to Hohenheim was a guaranteed
    // dead end -- the renderer skipped both inputs, enforceFieldAccess stripped them out of
    // the submit, and validate then refused the save for lacking them. The type rule lives at
    // WRITE time instead (see stripCredentialsOfNameserver), keyed on the type the submit
    // declares, which is the only thing both halves can agree on.

    /**
     * Exchanges a fresh shared TSIG key with a Hohenheim peer, writing both sides.
     *
     * AIDEV-NOTE: the minted secret is never toasted, logged or echoed -- unlike an API
     * key, BOTH ends store this one, so there is no human who has to read it and a
     * one-time disclosure would only be a place for it to leak.
     */
    static @NonNull CmsActionResult negotiate(@NonNull Row peer) {
        DnsPeerApi api = DnsPeerApi.forPeer(peer);
        if (api == null) {
            // A nameserver peer, or a Hohenheim peer whose credentials were cleared:
            // there is no channel to negotiate over, so say so instead of failing later.
            return CmsActionResult.errorToast(
                HohenheimMicrocopy.DNS_PEER.of("negotiate_key_unsupported"));
        }
        String localName = DnsFederationKeys.localName();
        String peerName = String.valueOf(peer.get(DnsPeerModel.NAME));
        String keyName = DnsFederationKeys.keyNameFor(localName, peerName);
        String secret = DnsFederationKeys.mintSecret();

        DnsPeerKeyResponse confirmation;
        try {
            confirmation = api.negotiateTransferKey(localName, keyName,
                DnsFederationKeys.ALGORITHM, secret,
                DnsFederationKeys.localTransferHost(), DnsFederationKeys.localTransferPort());
        }
        catch (DnsPeerApi.PeerApiException refused) {
            return CmsActionResult.errorToast(
                HohenheimMicrocopy.DNS_PEER.of("negotiate_key_failed")
                    .withArg("reason", HohenheimViolations.reasonOf(refused)));
        }
        if (!keyName.equals(confirmation.key_name())) {
            // The peer stored the name IT was told; a different one back means the two
            // sides would look each other up under different names and never transfer.
            return CmsActionResult.errorToast(
                HohenheimMicrocopy.DNS_PEER.of("negotiate_key_mismatch"));
        }

        peer.set(DnsPeerModel.TSIG_KEY_NAME, keyName);
        peer.set(DnsPeerModel.TSIG_ALGORITHM, DnsFederationKeys.ALGORITHM);
        peer.set(DnsPeerModel.TSIG_SECRET, secret);
        Models.get(DnsPeerModel.class).save(peer);

        String endpoint = endpointOf(confirmation);
        if (endpoint.isEmpty()) {
            return CmsActionResult.refreshWithToast(
                HohenheimMicrocopy.DNS_PEER.of("negotiate_key_done")
                    .withArg("key", keyName));
        }
        // A kept endpoint is the one outcome the operator must act on: the key works, but
        // the peer still pulls from an address that is not the one we announced.
        return CmsActionResult.refreshWithToast(
            HohenheimMicrocopy.DNS_PEER.of(Boolean.TRUE.equals(confirmation.transfer_kept())
                    ? "negotiate_key_endpoint_kept" : "negotiate_key_done_endpoint")
                .withArg("key", keyName)
                .withArg("endpoint", endpoint));
    }

    /** @return {@code host:port} as the peer now files this instance, or empty when it named none */
    private static @NonNull String endpointOf(@NonNull DnsPeerKeyResponse confirmation) {
        String host = confirmation.transfer_host();
        if (host == null || host.isBlank()) {
            return "";
        }
        Integer port = confirmation.transfer_port();
        return port != null ? host.trim() + ":" + port : host.trim();
    }

    /** The record-less dialog says what a peer delete takes: the transfer relationship. */
    public @NonNull ConfirmationSpec deleteConfirmation() {
        return DeleteConfirmation.body(HohenheimMicrocopy.DNS_PEER.of("delete_confirm"));
    }

    /**
     * The same warning NAMING the primary zones that stop notifying this peer and stop
     * accepting its transfers -- the links die with the peer, silently otherwise.
     */
    public @NonNull ConfirmationSpec deleteConfirmationFor(@NonNull Row record) {
        String zones = DeleteImpact.join(
            DeleteImpact.zonesLinkedToPeer(record.get(DnsPeerModel.ID)));
        Microcopy body = HohenheimMicrocopy.DNS_PEER
            .of(zones.isEmpty() ? "delete_confirm_named" : "delete_confirm_linked")
            .withArg("name", String.valueOf((Object) record.get(DnsPeerModel.NAME)));
        if (!zones.isEmpty()) {
            body = body.withArg("zones", zones);
        }
        return DeleteConfirmation.body(body);
    }

    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> write = stripCredentialsOfNameserver(coerced, null);
        validate(write, null);
        return DnsRowWrites.create(this.model(), this.formSpec(), write, accessContext);
    }

    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> write = stripCredentialsOfNameserver(coerced, existing);
        validate(write, existing);
        DnsRowWrites.update(this.model(), this.formSpec(), existing, write, accessContext);
    }

    /**
     * Blanks the edit-forwarding credentials whenever the type this write DECLARES is a plain
     * nameserver, so they can neither be smuggled in nor survive a demotion.
     *
     * AIDEV-NOTE: keyed on the SUBMITTED type, which is what lets the opposite direction work
     * -- promoting a peer to Hohenheim carries base_url/api_key in the same submit and keeps
     * them. Only a write that names peer_type or one of the two columns is touched, because a
     * partial write (the inline cell lane hands exactly one entry) means LEAVE ALONE. The
     * secret arm is safe here: FormSecrets.restore has already put the stored api_key back
     * before this runs, so writing "" clears it rather than reading as "unchanged".
     *
     * @return the map to write: a blanked copy when the rule fires, the input itself otherwise
     */
    private static @NonNull Map<String, Object> stripCredentialsOfNameserver(
            @NonNull Map<String, Object> coerced, @Nullable Row existing) {
        boolean declaresType = coerced.containsKey(DnsPeerModel.PEER_TYPE.getName());
        boolean carriesCredentials = coerced.containsKey(DnsPeerModel.BASE_URL.getName())
            || coerced.containsKey(DnsPeerModel.API_KEY.getName());
        if (!declaresType && !carriesCredentials) {
            return coerced;
        }
        String type = declaresType
            ? String.valueOf(coerced.get(DnsPeerModel.PEER_TYPE.getName()))
            : (existing != null ? DnsPeerModel.typeOf(existing) : DnsPeerModel.TYPE_NAMESERVER);
        if (DnsPeerModel.TYPE_HOHENHEIM.equals(type)) {
            return coerced;
        }
        Map<String, Object> write = CmsSupport.mutable(coerced);
        write.put(DnsPeerModel.BASE_URL.getName(), "");
        write.put(DnsPeerModel.API_KEY.getName(), "");
        return write;
    }

    /**
     * The declared type decides which channel must be complete: a Hohenheim peer without
     * admin credentials forwards nothing, and a plain nameserver without a transfer host
     * is a peer this server can never reach.
     */
    private static void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        String name = value(coerced, existing, DnsPeerModel.NAME);
        if (name.isEmpty()) {
            throw Violations.ofField("name", name, HohenheimMicrocopy.VIOLATIONS.of("name_required"));
        }
        String algorithm = value(coerced, existing, DnsPeerModel.TSIG_ALGORITHM);
        if (!algorithm.isEmpty() && !DnsTsig.isSupportedAlgorithm(algorithm)) {
            throw Violations.ofField("tsig_algorithm", algorithm,
                HohenheimMicrocopy.VIOLATIONS.of("dns_tsig_algorithm"));
        }
        Object portValue = coerced.get("transfer_port");
        if (portValue instanceof Integer port && (port < 1 || port > 65535)) {
            throw Violations.ofField("transfer_port", port, HohenheimMicrocopy.VIOLATIONS.of("dns_port_range"));
        }

        String type = DnsPeerModel.TYPE_HOHENHEIM.equals(value(coerced, existing, DnsPeerModel.PEER_TYPE))
            ? DnsPeerModel.TYPE_HOHENHEIM : DnsPeerModel.TYPE_NAMESERVER;
        if (DnsPeerModel.TYPE_HOHENHEIM.equals(type)) {
            if (value(coerced, existing, DnsPeerModel.BASE_URL).isEmpty()) {
                throw Violations.ofField("base_url", "",
                    HohenheimMicrocopy.VIOLATIONS.of("dns_peer_base_url_required"));
            }
            if (value(coerced, existing, DnsPeerModel.API_KEY).isEmpty()) {
                throw Violations.ofField("api_key", "",
                    HohenheimMicrocopy.VIOLATIONS.of("dns_peer_api_key_required"));
            }
        }
        else if (value(coerced, existing, DnsPeerModel.TRANSFER_HOST).isEmpty()) {
            throw Violations.ofField("transfer_host", "",
                HohenheimMicrocopy.VIOLATIONS.of("dns_peer_transfer_host_required"));
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
        return trimmed(stored);
    }
}
