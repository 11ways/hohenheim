package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.KeptFromAssistants;
import be.elevenways.hohenheim.HohenheimVerbs;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsPeerApi;
import be.elevenways.zenit.cms.common.CmsMicrocopy;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationVerb;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.TextField;
import be.elevenways.zenit.common.validation.validator.MaxLength;
import be.elevenways.zenit.common.validation.Violations;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** DNS domain actions share identities and authorizers across their panel placements. */
public final class DnsOperations {
    public static final SubjectType<Row> ZONE = SubjectType.record(DnsZoneModel.REF);
    public static final SubjectType<Row> RECORD = SubjectType.record(DnsRecordModel.REF);
    public static final SubjectType<Row> PEER = SubjectType.record(DnsPeerModel.REF);

    public static final Operation<Row, Void, Integer> DELETE_ZONE =
            Operation.declare(HohenheimIds.id("delete_dns_zone"))
        .happened(OperationSentences.of("delete_dns_zone"))
        .label(CmsMicrocopy.of("delete")).icon(Icon.TRASH).one(ZONE).verb(OperationVerb.DELETE)
        .description(HohenheimMicrocopy.HOHENHEIM.of("delete_dns_zone_description"))
        .gate(OperationGate.open()).facts(OperationFact.DESTRUCTIVE).result(Integer.class)
        .command(CmsCommands.TRANSACTIONAL).register();
    public static final Operation<Row, Void, Integer> DELETE_RECORD =
            Operation.declare(HohenheimIds.id("delete_dns_record"))
        .happened(OperationSentences.of("delete_dns_record"))
        .label(CmsMicrocopy.of("delete")).icon(Icon.TRASH).one(RECORD)
        .notForAssistants(KeptFromAssistants.DNS_RECORD)
        .gate(OperationGate.open()).facts(OperationFact.DESTRUCTIVE).result(Integer.class)
        .command(CmsCommands.TRANSACTIONAL).register();
    public static final Operation<Row, Void, Integer> DELETE_PEER =
            Operation.declare(HohenheimIds.id("delete_dns_peer"))
        .happened(OperationSentences.of("delete_dns_peer"))
        .label(CmsMicrocopy.of("delete")).icon(Icon.TRASH).one(PEER).verb(OperationVerb.DELETE)
        .description(HohenheimMicrocopy.HOHENHEIM.of("delete_dns_peer_description"))
        .gate(OperationGate.open()).facts(OperationFact.DESTRUCTIVE).result(Integer.class)
        .command(CmsCommands.TRANSACTIONAL).register();
    public static final Operation<Row, Void, CmsActionResult> CHECK_HEALTH =
            Operation.declare(HohenheimIds.id("check_dns_health"))
        .happened(OperationSentences.of("check_dns_health"))
        .label(HohenheimMicrocopy.DNS_ZONE.of("check_health"))
        .description(HohenheimMicrocopy.DNS_ZONE.of("check_health_hint"))
        .icon(Icon.of("stethoscope")).one(ZONE).verb(HohenheimVerbs.TEST).gate(OperationGate.open())
        .result(CmsActionResult.class)
        .command(CmsCommands.EXTERNAL).register();
    public static final Operation<Row, Void, CmsActionResult> MINT_DYNAMIC_TOKEN =
            Operation.declare(HohenheimIds.id("dyndns_token"))
        .happened(OperationSentences.of("dyndns_token"))
        .label(HohenheimMicrocopy.DNS_RECORD.of("dyndns_token")).icon(Icon.of("rotate"))
        .one(RECORD).notForAssistants(KeptFromAssistants.DNS_RECORD).gate(OperationGate.open())
        .result(CmsActionResult.class)
        .command(CmsCommands.TRANSACTIONAL).register();
    public static final Operation<Row, Void, CmsActionResult> REVOKE_DYNAMIC_TOKEN =
            Operation.declare(HohenheimIds.id("dyndns_revoke"))
        .happened(OperationSentences.of("dyndns_revoke"))
        .label(HohenheimMicrocopy.DNS_RECORD.of("dyndns_revoke"))
        .description(HohenheimMicrocopy.DNS_RECORD.of("dyndns_revoke_hint"))
        .icon(Icon.of("ban")).one(RECORD).notForAssistants(KeptFromAssistants.DNS_RECORD).gate(OperationGate.open())
        .result(CmsActionResult.class)
        .command(CmsCommands.TRANSACTIONAL).register();
    public static final Operation<Row, Void, CmsActionResult> NEGOTIATE_KEY =
            Operation.declare(HohenheimIds.id("negotiate_transfer_key"))
        .happened(OperationSentences.of("negotiate_transfer_key"))
        .label(HohenheimMicrocopy.DNS_PEER.of("negotiate_key"))
        .description(HohenheimMicrocopy.DNS_PEER.of("negotiate_key_hint"))
        .icon(Icon.of("key")).one(PEER).verb(HohenheimVerbs.NEGOTIATE_KEY).gate(OperationGate.open())
        .result(CmsActionResult.class)
        .command(CmsCommands.EXTERNAL).register();

    /** The most zone-file text one import takes, in characters. */
    public static final int ZONE_TEXT_MAX = 1_048_576;

    /** The zone-file text an import replaces a primary zone's records with. */
    public static final TextField ZONE_TEXT = TextField.builder("zone_text")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("zone_text"))
        .help(HohenheimMicrocopy.HELP.of("dns_zone_text"))
        .required()
        .validator(MaxLength.of(ZONE_TEXT_MAX))
        .build();

    /** Whether an import keeps the file's apex NS records instead of writing the declared nameservers. */
    public static final BooleanField KEEP_NS = BooleanField.builder("keep_ns").defaultValue(false)
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("keep_ns"))
        .help(HohenheimMicrocopy.HELP.of("dns_keep_ns"))
        .build();

    /**
     * Replaces a primary zone's records with those of zone-file text, Hohenheim's own records kept: the zone-file
     * tab's import form and an assistant's import alike, answered as the toast that form shows.
     */
    public static final Operation<Row, OperationInput.Values, CmsActionResult> IMPORT_ZONE =
            Operation.declare(HohenheimIds.id("import_dns_zone"))
        .happened(OperationSentences.of("import_dns_zone"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("import_dns_zone"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("import_dns_zone_description"))
        .icon(Icon.of("file-import")).one(ZONE).gate(OperationGate.open())
        .input(OperationInput.derived(FormSpec.builder().add(ZONE_TEXT).add(KEEP_NS).build()))
        .result(CmsActionResult.class)
        // The import writes its own all-or-nothing transaction and reloads what the zone serves after it.
        .command(CmsCommands.EXTERNAL).register();

    public record RemoteInput(String action, String record_id, String name, String type, String ttl, String value,
                              String priority, String weight, String port, String enabled) {
        Map<String, String> fields() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("name", name); values.put("type", type); values.put("ttl", ttl); values.put("value", value);
            values.put("priority", priority);
            values.put("weight", weight);
            values.put("port", port);
            values.put("enabled", enabled);
            values.values().removeIf(Objects::isNull);
            return values;
        }
    }

    private static FormSpec remoteForm() {
        var builder = FormSpec.builder().add(StringField.builder("action").build())
            .add(StringField.builder("record_id").build());
        for (String field : DnsPeerApi.RECORD_FIELDS) builder.add(StringField.builder(field).build());
        return builder.build();
    }

    public static final Operation<Row, RemoteInput, CmsActionResult> REMOTE_EDIT = Operation
        .declare(HohenheimIds.id("edit_remote_dns_record"))
        .happened(OperationSentences.of("edit_remote_dns_record"))
        .label(HohenheimMicrocopy.DNS_REMOTE.of("save_remote"))
        .one(ZONE).notForAssistants(KeptFromAssistants.DNS_RECORD).gate(OperationGate.open())
        .input(OperationInput.of(remoteForm(), RemoteInput.class, v -> new RemoteInput(
            (String) v.coerced().get("action"), (String) v.coerced().get("record_id"),
            (String) v.coerced().get("name"), (String) v.coerced().get("type"), (String) v.coerced().get("ttl"),
            (String) v.coerced().get("value"), (String) v.coerced().get("priority"),
            (String) v.coerced().get("weight"), (String) v.coerced().get("port"), (String) v.coerced().get("enabled"))))
        .result(CmsActionResult.class).command(CmsCommands.EXTERNAL).register();

    private DnsOperations() {}
    public static void init() { DnsOperationHandlers.init(); }
}
