package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.dns.DnsRecordDto;
import be.elevenways.hohenheim.dns.DnsRecordSetOutcome;
import be.elevenways.hohenheim.dns.DnsRecordView;
import be.elevenways.hohenheim.model.DnsDyndnsCredentialModel;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.HostnameAuthority;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.dns.DnsFederationKeys;
import be.elevenways.hohenheim.server.dns.DnsPeerApi;
import be.elevenways.hohenheim.server.dns.DnsNames;
import be.elevenways.hohenheim.server.dns.DnsRecordCodec;
import be.elevenways.hohenheim.server.dns.DnsZoneFiles;
import be.elevenways.hohenheim.server.dns.DnsValueException;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.SecondaryZoneService;
import be.elevenways.hohenheim.server.dns.DynamicDnsService;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.edit.FormJsonSchema;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.JsonSchemaType;
import be.elevenways.zenit.common.edit.JsonValue;
import be.elevenways.zenit.common.edit.Plain;
import be.elevenways.zenit.common.edit.submit.JsonSchemaValidator;
import be.elevenways.zenit.common.edit.submit.JsonSchemaValidator.Keyword;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A zone's records read and set by their natural key, a name and a type: one call makes each name and type hold
 * exactly the values it gives, all entries in one write with one serial bump.
 *
 * The record pipeline does the writing (DnsRecordEdits' checks, DnsRowWrites' create and update, the record
 * model's tenant and generated-row guards), so a value, a name or a right refused in the panel is refused here in the
 * same words; only the diff of a name and type's rows and the single serial bump are this class's. A made dynamic DNS
 * token is never answered: it is parked for the caller's account and shown once on the zone's Records tab
 * (TokenDisclosures). A secondary zone's records are kept on its owning peer: a set is judged here and applied there
 * whole over the peer API ({@link #forward}, received by {@link #applyForwarded}), a read answers the owner's
 * records, or the local copy when the owner cannot be asked.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class DnsRecordSets {

    /** The argument listing the names and types to set. */
    public static final String RECORDS = "records";

    private static final String NAME = "name";
    private static final String TYPE = "type";
    private static final String TTL = "ttl";
    private static final String REMOVE = "remove";
    private static final String VALUES = "values";
    private static final String VALUE = "value";
    private static final String PRIORITY = "priority";
    private static final String WEIGHT = "weight";
    private static final String PORT = "port";
    private static final String ENABLED = "enabled";
    private static final String DYNDNS = "dyndns";

    /** The dyndns values: keep (or make) a token, make a new one, drop it. */
    private static final String DYNDNS_ON = "on";
    private static final String DYNDNS_NEW = "new_token";
    private static final String DYNDNS_OFF = "off";

    /** The largest TTL a record takes, as the record form refuses a larger one. */
    private static final int MAX_TTL = 604_800;

    /** The largest priority, weight and port an MX or SRV record takes. */
    private static final int MAX_NUMBER = 65_535;

    /** Each entry: a name and type, their TTL, and the values they hold, or remove. */
    public static final JsonValue ENTRIES = JsonValue.of(RECORDS).schema(entries()).required()
        .help(HohenheimMicrocopy.HELP.of("dns_records_set")).build();

    /** The set's input: the entries as given. */
    public record SetInput(@NonNull List<?> records) {
    }

    /**
     * The read's input.
     *
     * @param name         only the records of this name, null for every name
     * @param type         only the records of this type, null for every type
     * @param as_zone_text the whole zone as zone-file text instead of one line per record
     */
    public record ListInput(@Nullable String name, @Nullable String type, @Nullable Boolean as_zone_text) {
    }

    private static final StringField LIST_NAME = StringField.builder(NAME)
        .help(HohenheimMicrocopy.HELP.of("dns_records_list_name")).build();
    private static final EnumField LIST_TYPE = typeField();
    private static final BooleanField AS_ZONE_TEXT = BooleanField.builder("as_zone_text").defaultValue(false)
        .help(HohenheimMicrocopy.HELP.of("dns_records_as_zone_text")).build();

    /** Sets names and types of a zone to exactly the values given, or removes them, in one write. */
    public static final Operation<Row, SetInput, String> SET = Operation.declare(HohenheimIds.id("set_dns_records"))
        .happened(OperationSentences.of("set_dns_records"))
        .label(HohenheimMicrocopy.DNS_RECORD.of("set_records"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("set_dns_records_description"))
        .icon(Icon.of("pen"))
        .one(DnsOperations.ZONE)
        // The rows' own guards judge who may write which name and type (TenantWrites), as on the panel's form.
        .gate(OperationGate.open())
        .input(OperationInput.of(FormSpec.builder().add(ENTRIES).build(), SetInput.class,
            values -> new SetInput((List<?>) Objects.requireNonNull(values.get(RECORDS)))))
        .result(String.class)
        .facts(OperationFact.IDEMPOTENT)
        // Its own transaction for a primary zone's rows (write); a secondary zone's set waits on the owner, which a
        // database transaction must not be held open for.
        .command(CmsCommands.EXTERNAL)
        .register();

    /** Reads a zone's records, or the whole zone as zone-file text. */
    public static final Operation<Row, ListInput, String> LIST = Operation.declare(HohenheimIds.id("list_dns_records"))
        .label(HohenheimMicrocopy.DNS_RECORD.of("list_records"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("list_dns_records_description"))
        .happened(OperationSentences.of("list_dns_records"))
        .icon(Icon.of("list-ul"))
        .one(DnsOperations.ZONE)
        .gate(OperationGate.open())
        .input(OperationInput.of(FormSpec.builder().add(Plain.of(LIST_NAME)).add(Plain.of(LIST_TYPE))
                .add(Plain.of(AS_ZONE_TEXT)).build(), ListInput.class,
            values -> new ListInput(values.get(LIST_NAME), values.get(LIST_TYPE), values.get(AS_ZONE_TEXT))))
        .result(String.class)
        .facts(OperationFact.READ_ONLY)
        .register();

    /**
     * The zones a caller names: every one for an administrator, the ones holding his names for anyone else. It projects
     * only the key: a handler reads the zone it was handed whole ({@link #whole}).
     */
    private static final RecordSource<DnsZoneModel> ZONES = RecordSource.of(DnsZoneModel.REF)
        .projectPrimaryKey().scopedBy(TenantScopes.DNS_ZONES).build();

    static {
        OperationHandlers.attach(SET).source(ZONES).handle(call -> set(whole(call.subject()),
            Objects.requireNonNull(call.input()), call.access()));
        OperationHandlers.attach(LIST).source(ZONES).handle(call -> list(whole(call.subject()),
            Objects.requireNonNull(call.input()), Objects.requireNonNull(call.access(), "a read has its caller")));
    }

    /** @return the zone as stored, every column, after the caller's read admitted it */
    private static @NonNull Row whole(@NonNull Row zone) {
        return Objects.requireNonNull(Models.get(DnsZoneModel.class).findById(zone.get(DnsZoneModel.ID)),
            "an admitted zone is stored");
    }

    private DnsRecordSets() {
    }

    /** Installs the handlers; idempotent through the class initializer. */
    public static void init() {
    }

    /** @return the record types, as the record form offers them */
    private static @NonNull EnumField typeField() {
        EnumField.Builder type = EnumField.builder(TYPE).help(HohenheimMicrocopy.HELP.of("dns_records_list_type"));
        for (EnumField.EnumValue each : DnsRecordModel.TYPE.getValues().values()) {
            type.value(each.getKey(), Objects.requireNonNull(each.getMicrocopy(), "every record type is worded"));
        }
        return type.build();
    }

    /** @return the entries' shape: each a name, a type, a TTL, removal and the values with their extras */
    private static @NonNull Map<String, Object> entries() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(VALUE, FormJsonSchema.property(JsonSchemaType.STRING, null, null));
        value.put(PRIORITY, bounded(JsonSchemaType.INTEGER, 0, MAX_NUMBER));
        value.put(WEIGHT, bounded(JsonSchemaType.INTEGER, 0, MAX_NUMBER));
        value.put(PORT, bounded(JsonSchemaType.INTEGER, 0, MAX_NUMBER));
        value.put(ENABLED, FormJsonSchema.property(JsonSchemaType.BOOLEAN, null, null));
        value.put(DYNDNS, FormJsonSchema.property(JsonSchemaType.STRING, List.of(DYNDNS_ON, DYNDNS_NEW, DYNDNS_OFF),
            null));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(NAME, FormJsonSchema.property(JsonSchemaType.STRING, null, null));
        entry.put(TYPE, FormJsonSchema.property(JsonSchemaType.STRING, DnsRecordModel.ALL_TYPES, null));
        entry.put(TTL, bounded(JsonSchemaType.INTEGER, 0, MAX_TTL));
        entry.put(REMOVE, FormJsonSchema.property(JsonSchemaType.BOOLEAN, null, null));
        entry.put(VALUES, FormJsonSchema.array(FormJsonSchema.object(value, List.of(VALUE))));
        Map<String, Object> list = new LinkedHashMap<>(FormJsonSchema.array(FormJsonSchema.object(entry,
            List.of(NAME, TYPE))));
        list.put(Keyword.MIN_ITEMS.token(), 1);
        return list;
    }

    private static @NonNull Map<String, Object> bounded(@NonNull JsonSchemaType type, int least, int most) {
        Map<String, Object> node = new LinkedHashMap<>(FormJsonSchema.property(type, null, null));
        node.put(Keyword.MINIMUM.token(), least);
        node.put(Keyword.MAXIMUM.token(), most);
        return node;
    }

    /**
     * Makes each entry's name and type hold exactly its values ({@link #write}) on a primary zone, or on the server
     * keeping a secondary zone's records ({@link #forward}).
     *
     * @return one line per entry saying what it added, changed, kept and removed
     * @throws Violations in the record form's own words for a refused name, type, value or right; nothing is written
     */
    private static @NonNull String set(@NonNull Row zone, @NonNull SetInput input, @Nullable AccessContext access) {
        AccessContext caller = Objects.requireNonNull(access, "a write has its caller");
        if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
            return forward(zone, input.records(), caller);
        }
        requireTokenShownTo(input, caller);
        // The rows and the parked tokens commit together, as they did when the whole call was one transaction.
        return Models.get(DnsRecordModel.class).getResolvedDatasource().inTransaction(() -> {
            Written written = write(zone, input.records(), caller);
            List<String> said = said(written.outcomes(), caller);
            if (!written.tokens().isEmpty()) {
                said.addAll(parkTokens(caller, zone.get(DnsZoneModel.ID), written.tokens(), written.made()));
            }
            return String.join("\n", said);
        });
    }

    /**
     * What one write did to a zone's names and types, and the dynamic DNS tokens it made.
     *
     * @param tokens one line per token made, naming its record, shown only to the caller's account
     * @param made   the name and type of each token made, in the same order
     */
    private record Written(@NonNull List<DnsRecordSetOutcome> outcomes, @NonNull List<String> tokens,
                           @NonNull List<String> made) {
    }

    /**
     * Makes each entry's name and type of a primary zone hold exactly its values: a stored value given again is kept
     * (its TTL, extras and switch written when they differ), one not given is removed, a new one added; removed rows
     * go first, so a CNAME replacing other records is judged against what stays.
     *
     * @throws Violations in the record form's own words for a refused name, type, value or right
     */
    private static @NonNull Written write(@NonNull Row zone, @NonNull List<?> records,
                                          @NonNull AccessContext caller) {
        int zoneId = zone.get(DnsZoneModel.ID);
        DnsRecordParts parts = new DnsRecordParts();
        Model model = parts.model();
        List<DnsRecordSetOutcome> outcomes = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        List<String> made = new ArrayList<>();
        boolean wrote = false;
        for (Object given : records) {
            Map<?, ?> entry = (Map<?, ?>) given;
            String type = String.valueOf(entry.get(TYPE));
            String owner = ownerOf(entry);
            List<Row> stored = model.find().where(DnsRecordModel.ZONE_ID.eq(zoneId))
                .where(DnsRecordModel.NAME.eq(owner)).where(DnsRecordModel.TYPE.eq(type)).all();
            if (Boolean.TRUE.equals(entry.get(REMOVE))) {
                for (Row row : stored) model.delete(row);
                wrote |= !stored.isEmpty();
                outcomes.add(new DnsRecordSetOutcome(owner, type, 0, 0, 0, stored.size(), true));
                continue;
            }
            List<?> values = entry.get(VALUES) instanceof List<?> list ? list : List.of();
            if (values.isEmpty()) {
                throw Violations.ofField(VALUES, null, HohenheimMicrocopy.VIOLATIONS.of("dns_value_required"));
            }
            Map<String, Map<?, ?>> wanted = new LinkedHashMap<>();
            for (Object each : values) {
                Map<?, ?> value = (Map<?, ?>) each;
                if (wanted.put(storedValue(type, String.valueOf(value.get(VALUE))), value) != null) {
                    throw Violations.ofField(VALUE, value.get(VALUE),
                        HohenheimMicrocopy.VIOLATIONS.of("dns_record_duplicate"));
                }
            }
            Map<String, Row> kept = new LinkedHashMap<>();
            int removed = 0;
            for (Row row : stored) {
                String value = Objects.toString(row.get(DnsRecordModel.VALUE), "");
                if (wanted.containsKey(value) && !kept.containsKey(value)) {
                    kept.put(value, row);
                } else {
                    model.delete(row);
                    removed++;
                }
            }
            int added = 0;
            int changed = 0;
            for (Map.Entry<String, Map<?, ?>> each : wanted.entrySet()) {
                Map<String, Object> written = rowValues(zoneId, owner, type, entry.get(TTL), each.getValue());
                Row row = kept.get(each.getKey());
                Object recordId;
                if (row == null) {
                    DnsRecordEdits.validate(written, null, model);
                    recordId = DnsRowWrites.create(model, parts.formSpec(), written, caller);
                    added++;
                } else {
                    recordId = row.get(DnsRecordModel.ID);
                    if (differs(row, written)) {
                        DnsRecordEdits.validate(written, row, model);
                        DnsRowWrites.update(model, parts.formSpec(), row, written, caller);
                        changed++;
                    }
                }
                String token = keyDynamic(type, each.getValue(), Integer.valueOf(String.valueOf(recordId)), caller);
                if (token != null) {
                    tokens.add(owner + " " + type + " " + each.getKey() + ": " + token);
                    made.add(owner + " " + type);
                }
            }
            wrote |= added + changed + removed > 0;
            outcomes.add(new DnsRecordSetOutcome(owner, type, added, changed, kept.size() - changed, removed, false));
        }
        // As the panel's writers, a zone's serial moves only when one of its records did.
        if (wrote) DnsZoneStore.INSTANCE.bumpSerialAndReload(zoneId);
        return new Written(outcomes, tokens, made);
    }

    /** @return the entry's name as the record pipeline stores it */
    private static @NonNull String ownerOf(@NonNull Map<?, ?> entry) {
        String owner = DnsNames.normalizeOwner(String.valueOf(entry.get(NAME)));
        if (owner == null) {
            throw Violations.ofField(NAME, entry.get(NAME), HohenheimMicrocopy.VIOLATIONS.of("dns_name_format"));
        }
        return owner;
    }

    /** @return one line per entry saying what it added, changed, kept and removed, in the caller's words */
    private static @NonNull List<String> said(@NonNull List<DnsRecordSetOutcome> outcomes,
                                              @NonNull AccessContext caller) {
        List<String> said = new ArrayList<>();
        for (DnsRecordSetOutcome outcome : outcomes) {
            said.add(caller.text(outcome.dropped()
                ? HohenheimMicrocopy.DNS_RECORD.of("set_removed").withArg("name", outcome.name())
                    .withArg("type", outcome.type()).withArg("count", outcome.removed())
                : HohenheimMicrocopy.DNS_RECORD.of("set_said").withArg("name", outcome.name())
                    .withArg("type", outcome.type()).withArg("added", outcome.added())
                    .withArg("changed", outcome.changed()).withArg("kept", outcome.kept())
                    .withArg("removed", outcome.removed())));
        }
        return said;
    }

    /**
     * Sets a secondary zone's records on the Hohenheim peer keeping them, over the peer API the Records tab forwards
     * its edits by: judged here, applied there whole or not at all, and this server's copy pulled at once.
     *
     * AIDEV-NOTE: the peer's key is an operator's there, so THIS server decides who may, before anything is sent: a
     * non-administrator is asked the record rule a write here asks ({@link TenantWrites#checkRecordCandidate} over
     * this zone's origin), where only hostname authority can admit him, since a per-record grant names a row and
     * this zone's rows live on the owner. A dynamic DNS token is refused: one made there would be shown to the
     * peer's key, never to the caller. An older peer, which lacks the route, answers 404: the refusal of a zone
     * kept elsewhere, as before.
     *
     * @return the owner's answer per name and type, in the caller's words
     * @throws Violations for an entry the caller may not set, or the owner refusing, unreachable or unanswered
     */
    private static @NonNull String forward(@NonNull Row zone, @NonNull List<?> records,
                                           @NonNull AccessContext caller) {
        Row peer = Models.get(DnsPeerModel.class).findById(zone.get(DnsZoneModel.PRIMARY_PEER_ID));
        DnsPeerApi api = DnsPeerApi.forPeer(peer);
        if (api == null) {
            throw keptElsewhere(peer, caller);
        }
        int zoneId = zone.get(DnsZoneModel.ID);
        for (Object given : records) {
            Map<?, ?> entry = (Map<?, ?>) given;
            refuseDynamic(entry);
            TenantWrites.checkRecordCandidate(zoneId, ownerOf(entry), String.valueOf(entry.get(TYPE)), null);
        }
        String person = TokenDisclosures.shownTo(caller);
        List<DnsRecordSetOutcome> outcomes;
        try {
            outcomes = api.setRecords(zone.get(DnsZoneModel.ORIGIN), records, DnsFederationKeys.localName(),
                person.isEmpty() ? null : person);
        } catch (DnsPeerApi.PeerApiException refused) {
            throw forwardRefused(refused, Objects.requireNonNull(peer, "a client has its peer"), caller);
        }
        List<String> said = said(outcomes, caller);
        if (!SecondaryZoneService.pullNow(zoneId)) {
            said.add(caller.text(HohenheimMicrocopy.DNS_RECORD.of("forward_copy_later")));
        }
        return String.join("\n", said);
    }

    /** @return the refusal of a set on a zone whose records another server keeps, naming it to an administrator */
    private static @NonNull Violations keptElsewhere(@Nullable Row peer, @NonNull AccessContext caller) {
        // Which server keeps a zone's records is installation knowledge: only an administrator is told.
        return Violations.ofForm(peer == null || !HohenheimAccess.isAdmin(caller)
            ? HohenheimMicrocopy.VIOLATIONS.of("record_secondary_zone")
            : HohenheimMicrocopy.VIOLATIONS.of("record_secondary_zone_owner")
                .withArg("peer", String.valueOf(peer.get(DnsPeerModel.NAME))));
    }

    /** @return the refusal of a forwarded set the owner refused, could not be asked or did not answer */
    private static @NonNull Violations forwardRefused(DnsPeerApi.@NonNull PeerApiException refused, @NonNull Row peer,
                                                      @NonNull AccessContext caller) {
        if (refused.status() == 404 || refused.status() == 405) {
            return keptElsewhere(peer, caller);
        }
        if (refused.getViolationKey() != null) {
            String field = refused.getViolationField();
            return Violations.ofField(field == null ? RECORDS : field, null,
                HohenheimMicrocopy.VIOLATIONS.of(refused.getViolationKey()));
        }
        boolean admin = HohenheimAccess.isAdmin(caller);
        String server = admin ? String.valueOf(peer.get(DnsPeerModel.NAME))
            : caller.text(HohenheimMicrocopy.DNS_RECORD.of("owner_unnamed"));
        String reason = admin ? String.valueOf(refused.getMessage()) : "";
        if (refused.status() == 409) {
            return Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("dns_forward_not_primary")
                .withArg("peer", server));
        }
        if (refused.status() == 0) {
            return Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of(refused.sent() ? "dns_forward_unanswered"
                : "dns_forward_unreachable").withArg("peer", server).withArg("reason", reason));
        }
        return Violations.ofForm(HohenheimMicrocopy.DNS_REMOTE.of("peer_call_failed").withArg("reason",
            admin ? reason : String.valueOf(refused.status())));
    }

    /** Refuses an entry asking a dynamic DNS token of a zone whose records another server keeps. */
    private static void refuseDynamic(@NonNull Map<?, ?> entry) {
        if (entry.get(VALUES) instanceof List<?> values) {
            for (Object value : values) {
                Object dyndns = value instanceof Map<?, ?> map ? map.get(DYNDNS) : null;
                if (dyndns != null) {
                    throw Violations.ofField(DYNDNS, dyndns, HohenheimMicrocopy.VIOLATIONS.of("dns_forward_dyndns"));
                }
            }
        }
    }

    /**
     * Applies a record set a peer forwarded for its copy of this primary zone, whole or not at all, and records in the
     * zone's history which server sent it and for whom.
     *
     * @param records the entries as the peer sent them, checked against the tool's own shape first
     * @param server  the forwarding server's name for itself
     * @param person  the person it was done for there, null for none
     * @return one outcome per entry, in the order sent
     * @throws Violations for entries out of shape, a dynamic DNS token asked, or an entry the record pipeline refuses
     */
    public static @NonNull List<DnsRecordSetOutcome> applyForwarded(@NonNull Row zone, @Nullable Object records,
                                                                    @NonNull AccessContext caller,
                                                                    @NonNull String server, @Nullable String person) {
        Violations shape = new Violations();
        JsonSchemaValidator.validate(Objects.requireNonNull(ENTRIES.schema(), "the entries declare a schema"), records,
            RECORDS, RECORDS, shape);
        if (!shape.isEmpty()) {
            throw shape;
        }
        List<?> entries = (List<?>) Objects.requireNonNull(records);
        for (Object entry : entries) refuseDynamic((Map<?, ?>) entry);
        int zoneId = zone.get(DnsZoneModel.ID);
        Map<String, Object> told = new LinkedHashMap<>();
        told.put(HohenheimActivityAction.SERVER_KEY, server);
        if (person != null && !person.isBlank()) told.put(HohenheimActivityAction.PERSON_KEY, person);
        return Models.get(DnsRecordModel.class).getResolvedDatasource().inTransaction(() -> {
            List<DnsRecordSetOutcome> outcomes = write(zone, entries, caller).outcomes();
            History.event(Models.get(DnsZoneModel.class), zoneId,
                Change.fact(HohenheimActivityAction.RECORDS_FORWARDED, told));
            return outcomes;
        });
    }

    /** @return whether a value asks a token be kept or made */
    private static boolean asksToken(@NonNull Map<?, ?> value) {
        Object dyndns = value.get(DYNDNS);
        return DYNDNS_ON.equals(dyndns) || DYNDNS_NEW.equals(dyndns);
    }

    /**
     * Refuses, before anything is written, a call asking a token while no account stands behind the caller: a made
     * token is shown only to that account, so it would be shown to nobody.
     */
    private static void requireTokenShownTo(@NonNull SetInput input, @NonNull AccessContext caller) {
        if (TokenDisclosures.accountOf(caller)) return;
        for (Object given : input.records()) {
            if (((Map<?, ?>) given).get(VALUES) instanceof List<?> values) {
                for (Object value : values) {
                    if (asksToken((Map<?, ?>) value)) {
                        throw Violations.ofField(DYNDNS, ((Map<?, ?>) value).get(DYNDNS),
                            HohenheimMicrocopy.VIOLATIONS.of("dns_dyndns_no_account"));
                    }
                }
            }
        }
    }

    /**
     * Keeps, makes or drops one record's dynamic DNS token as its value asks, with the panel's own right to.
     *
     * @return the token made, null when none was
     * @throws Violations for a token asked of a record that is no address, DomainRefusal for a caller who may not
     */
    private static @Nullable String keyDynamic(@NonNull String type, @NonNull Map<?, ?> value, int recordId,
                                               @NonNull AccessContext caller) {
        Object dyndns = value.get(DYNDNS);
        if (dyndns == null) return null;
        if (!DnsRecordModel.isAddressType(type)) {
            throw Violations.ofField(DYNDNS, dyndns, HohenheimMicrocopy.VIOLATIONS.of("dns_dyndns_address_only"));
        }
        if (!DnsOperationHandlers.keysDynamic(caller, recordId)) {
            throw new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "dyndns of record " + recordId + " refused",
                HohenheimMicrocopy.VIOLATIONS.of("dns_dyndns_refused"));
        }
        boolean held = DynamicDnsService.credentialFor(recordId) != null;
        if (DYNDNS_OFF.equals(dyndns)) {
            if (held) DnsRecordParts.revokeDynamic(recordId);
            return null;
        }
        return DYNDNS_NEW.equals(dyndns) || !held ? DnsRecordParts.mintDynamic(recordId) : null;
    }

    /** @return one line per token made, saying who sees it once, where and until when; never the token itself */
    private static @NonNull List<String> parkTokens(@NonNull AccessContext caller, int zoneId,
                                                    @NonNull List<String> tokens, @NonNull List<String> made) {
        Instant until = Objects.requireNonNull(TokenDisclosures.DYNDNS.park(caller, zoneId,
            String.join("\n", tokens)), "an account stands behind a caller asking a token");
        List<String> lines = new ArrayList<>();
        for (String each : made) {
            lines.add(caller.text(HohenheimMicrocopy.DNS_RECORD.of("dyndns_parked").withArg("record", each)
                .withArg("account", TokenDisclosures.shownTo(caller)).withArg("until", until)
                .withArg("url", DnsRecordParts.dyndnsUpdateUrl())));
        }
        return lines;
    }

    /** @return the value as the record pipeline stores it, or as given when the pipeline will refuse it anyway */
    private static @NonNull String storedValue(@NonNull String type, @NonNull String value) {
        try {
            return DnsRecordCodec.storedValue(type, value.trim());
        } catch (RuntimeException | DnsValueException refused) {
            return value.trim();
        }
    }

    /** @return the row values the record form would submit for one value of a name and type */
    private static @NonNull Map<String, Object> rowValues(int zoneId, @NonNull String owner, @NonNull String type,
                                                          @Nullable Object ttl, @NonNull Map<?, ?> value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(DnsRecordModel.ZONE_ID.getName(), zoneId);
        values.put(DnsRecordModel.NAME.getName(), owner);
        values.put(DnsRecordModel.TYPE.getName(), type);
        values.put(DnsRecordModel.VALUE.getName(), String.valueOf(value.get(VALUE)).trim());
        values.put(DnsRecordModel.TTL.getName(), RawValues.parsedInt(ttl));
        values.put(DnsRecordModel.ENABLED.getName(), !Boolean.FALSE.equals(value.get(ENABLED)));
        // The type's own extras, shaped as the record form's DATA sub-form is (the one home of that shape).
        values.put(DnsRecordModel.DATA.getName(), DnsRecordModel.dataFor(type,
            DnsRecordModel.dataInt(value, PRIORITY), DnsRecordModel.dataInt(value, WEIGHT),
            DnsRecordModel.dataInt(value, PORT)));
        return values;
    }

    /** @return whether a kept row's TTL, switch or extras differ from what the entry gives */
    private static boolean differs(@NonNull Row row, @NonNull Map<String, Object> values) {
        Map<?, ?> data = (Map<?, ?>) values.get(DnsRecordModel.DATA.getName());
        return !Objects.equals(row.get(DnsRecordModel.TTL), values.get(DnsRecordModel.TTL.getName()))
            || !Objects.equals(row.get(DnsRecordModel.ENABLED), values.get(DnsRecordModel.ENABLED.getName()))
            || !Objects.equals(DnsRecordModel.priorityOf(row), DnsRecordModel.dataInt(data, PRIORITY))
            || !Objects.equals(DnsRecordModel.weightOf(row), DnsRecordModel.dataInt(data, WEIGHT))
            || !Objects.equals(DnsRecordModel.portOf(row), DnsRecordModel.dataInt(data, PORT));
    }

    /**
     * @return one line per record the caller may see, as the panel's record list scopes them (an administrator every
     *     one): name, type, TTL, the value as a resolver prints it and what is notable about it; or the whole zone as
     *     zone-file text
     * @throws DomainRefusal FORBIDDEN for zone-file text asked by someone who is no administrator
     * @throws Violations for a name or type given beside zone-file text
     */
    private static @NonNull String list(@NonNull Row zone, @NonNull ListInput input, @NonNull AccessContext access) {
        if (Boolean.TRUE.equals(input.as_zone_text())) {
            if (input.name() != null || input.type() != null) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("dns_list_text_whole"));
            }
            if (!HohenheimAccess.isAdmin(access)) {
                Microcopy words = HohenheimMicrocopy.VIOLATIONS.of("dns_list_text_admin");
                throw new DomainRefusal(ZenitRefusalReason.FORBIDDEN, words.describe(), words);
            }
            return DnsZoneFiles.export(zone);
        }
        // A secondary zone's records are read where they are kept, scoped as its rows would be here.
        if (DnsZoneModel.ROLE_SECONDARY.equals(DnsZoneModel.roleOf(zone))) {
            return secondaryList(zone, input, access);
        }
        int zoneId = zone.get(DnsZoneModel.ID);
        var query = Models.get(DnsRecordModel.class).find().where(DnsRecordModel.ZONE_ID.eq(zoneId));
        // The records list's own read scope: names the caller answers for and records granted to them to view.
        Criteria seen = TenantScopes.DNS_RECORDS.criteria(access);
        if (seen != null) query = query.where(seen);
        if (input.name() != null) {
            String owner = DnsNames.normalizeOwner(input.name());
            query = query.where(DnsRecordModel.NAME.eq(owner == null ? input.name() : owner));
        }
        if (input.type() != null) query = query.where(DnsRecordModel.TYPE.eq(input.type()));
        List<Row> rows = query.orderBy(DnsRecordModel.NAME, SortOrder.ASC).orderBy(DnsRecordModel.TYPE, SortOrder.ASC)
            .all();
        Set<Integer> dynamic = Models.get(DnsDyndnsCredentialModel.class).find()
            .countBy(DnsDyndnsCredentialModel.RECORD_ID, rows.stream().map(row -> row.get(DnsRecordModel.ID))
                .filter(Objects::nonNull).toList()).keySet();
        String zoneTtl = String.valueOf(DnsZoneModel.defaultTtlOf(zone));
        List<String> lines = new ArrayList<>();
        for (Row row : rows) {
            Integer ttl = row.get(DnsRecordModel.TTL);
            String name = row.get(DnsRecordModel.NAME);
            String type = row.get(DnsRecordModel.TYPE);
            StringBuilder line = new StringBuilder().append(name).append(' ').append(type).append(' ')
                .append(ttl != null ? String.valueOf(ttl) : access.text(HohenheimMicrocopy.DNS_RECORD
                    .of("ttl_zone_default").withArg("ttl", zoneTtl)))
                .append(' ').append(DnsRecordModel.presentationValue(row));
            if (Boolean.FALSE.equals(row.get(DnsRecordModel.ENABLED))) {
                line.append(" (").append(access.text(HohenheimMicrocopy.DNS_RECORD.of("listed_disabled"))).append(')');
            }
            String generated = row.get(DnsRecordModel.GENERATED_BY);
            if (generated != null) {
                line.append(" (").append(access.text(HohenheimMicrocopy.DNS_RECORD.of("listed_generated")
                    .withArg("source", generated))).append(')');
            }
            if (dynamic.contains(row.get(DnsRecordModel.ID))) {
                line.append(" (").append(access.text(HohenheimMicrocopy.DNS_RECORD.of("listed_dynamic"))).append(')');
            }
            lines.add(line.toString());
        }
        return lines.isEmpty() ? access.text(HohenheimMicrocopy.DNS_RECORD.of("listed_none")) : String.join("\n",
            lines);
    }

    /**
     * @return a secondary zone's records as its owning peer answers them, else as this server's copy serves them,
     *     which of the two said first; one line per record as {@link #list} says them, of only the names a caller who
     *     is no administrator manages
     */
    private static @NonNull String secondaryList(@NonNull Row zone, @NonNull ListInput input,
                                                 @NonNull AccessContext access) {
        String owner = input.name() == null ? null : DnsNames.normalizeOwner(input.name());
        String wantedName = input.name() == null ? null : owner == null ? input.name() : owner;
        String origin = zone.get(DnsZoneModel.ORIGIN);
        boolean admin = HohenheimAccess.isAdmin(access);
        // The record lanes' own read rule for a name: hostname authority, asked once per name of the page.
        HostnameAuthority.Snapshot authority = admin ? null : HostnameAuthority.Snapshot.memoized(access);
        List<String> lines = new ArrayList<>();
        String zoneTtl = String.valueOf(DnsZoneModel.defaultTtlOf(zone));
        DnsZoneRecordsPage.OwnerRead read = DnsZoneRecordsPage.readOwner(zone);
        if (read.records() != null) {
            Row peer = Models.get(DnsPeerModel.class).findById(zone.get(DnsZoneModel.PRIMARY_PEER_ID));
            lines.add(access.text(HohenheimMicrocopy.DNS_RECORD.of("listed_from_owner").withArg("peer",
                admin && peer != null ? String.valueOf(peer.get(DnsPeerModel.NAME))
                    : access.text(HohenheimMicrocopy.DNS_RECORD.of("owner_unnamed")))));
            for (DnsRecordDto record : read.records()) {
                if (!listed(record.name(), record.type(), wantedName, input.type())
                    || !seen(authority, access, origin, record.name())) continue;
                StringBuilder line = new StringBuilder().append(record.name()).append(' ').append(record.type())
                    .append(' ').append(record.ttl() != null ? String.valueOf(record.ttl()) : access.text(
                        HohenheimMicrocopy.DNS_RECORD.of("ttl_zone_default").withArg("ttl", zoneTtl)))
                    .append(' ').append(DnsRecordModel.presentationValue(record.type(), Objects.toString(
                        record.value(), ""), record.priority(), record.weight(), record.port()));
                if (!record.enabled()) {
                    line.append(" (").append(access.text(HohenheimMicrocopy.DNS_RECORD.of("listed_disabled")))
                        .append(')');
                }
                if (record.managed_by() != null) {
                    line.append(" (").append(access.text(HohenheimMicrocopy.DNS_RECORD.of("listed_generated")
                        .withArg("source", record.managed_by()))).append(')');
                }
                lines.add(line.toString());
            }
        } else {
            // Why the owner could not be asked names its address: only an administrator is told.
            lines.add(access.text(HohenheimMicrocopy.DNS_RECORD.of("listed_from_copy").withArg("reason",
                access.text(admin ? Objects.requireNonNull(read.notice(), "an unread owner says why")
                    : HohenheimMicrocopy.DNS_RECORD.of("owner_unreached")))));
            for (DnsRecordView record : DnsZoneRecordsPage.replicaRecords(origin)) {
                if (!listed(record.name(), record.type(), wantedName, input.type())
                    || !seen(authority, access, origin, record.name())) continue;
                lines.add(record.name() + " " + record.type() + " " + record.ttl() + " " + record.value());
            }
        }
        return lines.size() == 1 ? lines.getFirst() + "\n" + access.text(HohenheimMicrocopy.DNS_RECORD
            .of("listed_none")) : String.join("\n", lines);
    }

    /** @return whether the caller may see a record of this name: an administrator always, else by hostname authority */
    private static boolean seen(HostnameAuthority.@Nullable Snapshot authority, @NonNull AccessContext access,
                                @NonNull String origin, @Nullable String name) {
        return authority == null || HostnameAuthority.canManageOwnerName(authority, access,
            DnsNames.absolute(origin, Objects.toString(name, "")));
    }

    /** @return whether a record of this name and type is one the read asks for */
    private static boolean listed(@Nullable String name, @Nullable String type, @Nullable String wantedName,
                                  @Nullable String wantedType) {
        return (wantedName == null || wantedName.equals(name)) && (wantedType == null || wantedType.equals(type));
    }
}
