package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.instance.ReadinessKind;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.server.instance.variable.VariableTypes;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned template export/import: a public catalog must never be stranded as
 * unportable rows. The wire format is PLAIN JSON produced and consumed through the DRY
 * JSON dialect ({@code Zenit.DRY.stringify}/{@code parse} over hand-shaped maps) --
 * deliberately NOT the typed DRY wire: an import is an UNTRUSTED external edge, so the
 * shape is hand-validated field by field and no typed revival ever runs on it.
 *
 * Integrity is a sha256 over the canonical template body ({@code stringify} of the
 * template map), declared inside the document and re-verified on import; a signature
 * scheme (operator keypairs) is NOT built -- checksummed, honest about it. Approval is
 * NEVER imported: every imported template lands unapproved, and an operator act is what
 * makes it tenant-selectable.
 */
public final class TemplatePortability {

    /** The document's self-identification. */
    public static final String FORMAT = "hohenheim-instance-template";

    /** The one format version this build writes and reads. */
    public static final int FORMAT_VERSION = 1;

    // -- export ---------------------------------------------------------------

    /** The full export document of one template, as a JSON string. */
    public @NonNull String export(@NonNull Row template) {
        Map<String, Object> body = templateBody(template);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", FORMAT);
        document.put("format_version", FORMAT_VERSION);
        document.put("exported_at", Instant.now().toString());
        Map<String, Object> checksum = new LinkedHashMap<>();
        checksum.put("algorithm", "sha256");
        checksum.put("value", checksumOf(body));
        document.put("checksum", checksum);
        document.put("template", body);
        return Zenit.DRY.stringify(document);
    }

    /** The canonical template body every checksum is computed over. */
    private @NonNull Map<String, Object> templateBody(@NonNull Row template) {
        int templateId = template.get(InstanceTemplateModel.ID);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", template.get(InstanceTemplateModel.NAME));
        body.put("description", template.get(InstanceTemplateModel.DESCRIPTION));
        body.put("kind", template.get(InstanceTemplateModel.KIND));
        body.put("version", template.get(InstanceTemplateModel.VERSION));
        body.put("settings", template.get(InstanceTemplateModel.SETTINGS));
        body.put("install_image", template.get(InstanceTemplateModel.INSTALL_IMAGE));
        body.put("install_script", template.get(InstanceTemplateModel.INSTALL_SCRIPT));
        body.put("update_script", template.get(InstanceTemplateModel.UPDATE_SCRIPT));
        body.put("reinstall_policy", template.get(InstanceTemplateModel.REINSTALL_POLICY));
        // The KIND rides with the line: without it an export of a console-readiness
        // template re-imports on the column default and its line is never read again.
        body.put("readiness_kind", template.get(InstanceTemplateModel.READINESS_KIND));
        body.put("readiness_line", template.get(InstanceTemplateModel.READINESS_LINE));
        body.put("readiness_target", template.get(InstanceTemplateModel.READINESS_TARGET));
        body.put("stop_command", template.get(InstanceTemplateModel.STOP_COMMAND));

        List<Map<String, Object>> variables = new ArrayList<>();
        for (Row declared : Models.get(InstanceTemplateVariableModel.class)
                .findByTemplateId(templateId)) {
            Map<String, Object> variable = new LinkedHashMap<>();
            variable.put("key", declared.get(InstanceTemplateVariableModel.KEY));
            variable.put("label", declared.get(InstanceTemplateVariableModel.LABEL));
            variable.put("description", declared.get(InstanceTemplateVariableModel.DESCRIPTION));
            variable.put("type", declared.get(InstanceTemplateVariableModel.TYPE));
            variable.put("settings", declared.get(InstanceTemplateVariableModel.SETTINGS));
            variable.put("required", declared.get(InstanceTemplateVariableModel.REQUIRED));
            variable.put("default_value", declared.get(InstanceTemplateVariableModel.DEFAULT_VALUE));
            variables.add(variable);
        }
        body.put("variables", variables);

        List<Map<String, Object>> files = new ArrayList<>();
        for (Row file : Models.get(InstanceTemplateFileModel.class).findByTemplateId(templateId)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("container_path", file.get(InstanceTemplateFileModel.CONTAINER_PATH));
            entry.put("content", file.get(InstanceTemplateFileModel.CONTENT));
            entry.put("mode", file.get(InstanceTemplateFileModel.MODE));
            files.add(entry);
        }
        body.put("files", files);
        return body;
    }

    /**
     * The checksum is computed over a CANONICAL form of the body, not its literal
     * bytes: keys sorted, nulls dropped, integral numbers folded to longs -- because
     * the verifier re-serializes what it PARSED, and a JSON round trip legitimately
     * changes number types and key order without changing meaning. Anything that DOES
     * change meaning (a value, a key, an entry) still changes the digest.
     */
    private static @NonNull String checksumOf(@NonNull Map<String, Object> body) {
        return SecureTokens.sha256Hex(Zenit.DRY.stringify(canonical(body)));
    }

    private static @Nullable Object canonical(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            java.util.TreeMap<String, Object> out = new java.util.TreeMap<>();
            map.forEach((key, entry) -> {
                Object canonical = canonical(entry);
                if (canonical != null) {
                    out.put(String.valueOf(key), canonical);
                }
            });
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(canonical(item));
            }
            return out;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            if (asDouble == Math.rint(asDouble) && !Double.isInfinite(asDouble)) {
                return number.longValue();
            }
            return asDouble;
        }
        return String.valueOf(value);
    }

    // -- import ---------------------------------------------------------------

    /**
     * Parse, verify and persist an exported document. The created template is ALWAYS
     * unapproved; {@code source} records where the operator says it came from.
     *
     * @return the created template row id
     * @throws Violations for an unparseable document, wrong format/version, checksum
     *         mismatch, unknown kind or variable type, or malformed entries
     */
    public int importDocument(@NonNull String json, @Nullable String source) {
        Object parsed;
        try {
            parsed = Zenit.DRY.parse(json);
        } catch (RuntimeException unparseable) {
            throw Violations.ofForm(violationText("template_import_unparseable"));
        }
        if (!(parsed instanceof Map<?, ?> document)) {
            throw Violations.ofForm(violationText("template_import_unparseable"));
        }
        if (!FORMAT.equals(document.get("format"))) {
            throw Violations.ofForm(violationText("template_import_format"));
        }
        Object version = document.get("format_version");
        if (!(version instanceof Number number) || number.intValue() != FORMAT_VERSION) {
            throw Violations.ofForm(violationText("template_import_version")
                .withArg("version", String.valueOf(version))
                .withArg("supported", FORMAT_VERSION));
        }
        if (!(document.get("template") instanceof Map<?, ?> rawBody)) {
            throw Violations.ofForm(violationText("template_import_unparseable"));
        }
        Map<String, Object> body = castMap(rawBody);

        // Integrity: the declared checksum must match the body as parsed. A missing or
        // wrong checksum is a refusal, never a shrug -- unverifiable provenance on an
        // artifact that will run code is exactly what this gate exists for.
        String declared = document.get("checksum") instanceof Map<?, ?> checksum
            && checksum.get("value") instanceof String value ? value : null;
        String computed = checksumOf(body);
        if (declared == null || !SecureTokens.constantTimeEquals(declared, computed)) {
            throw Violations.ofForm(violationText("template_import_checksum"));
        }

        String name = str(body.get("name"));
        if (name.isEmpty()) {
            throw Violations.ofField("name", name, violationText("name_required"));
        }
        String kind = str(body.get("kind"));
        Identifier kindId = Identifier.tryParse(kind);
        if (kindId == null || InstanceKindRegistry.REGISTRY.get(kindId) == null) {
            throw Violations.ofField("kind", kind,
                violationText("instance_kind_unknown").withArg("kind", kind));
        }

        // Registered is not the same question as authorable: an imported document must not
        // land a kind the create form deliberately no longer offers.
        InstanceKinds.requireAuthorable(kind);

        List<Map<String, Object>> variables = entryList(body.get("variables"));
        for (Map<String, Object> variable : variables) {
            String key = str(variable.get("key"));
            if (!key.matches("^[A-Z][A-Z0-9_]*$")) {
                throw Violations.ofField("key", key, violationText("variable_key_format"));
            }
            String type = str(variable.get("type"));
            if (VariableTypes.getHandler(type) == null) {
                throw Violations.ofField("type", type,
                    violationText("variable_type_unknown").withArg("type", type));
            }
        }
        List<Map<String, Object>> files = entryList(body.get("files"));
        for (Map<String, Object> file : files) {
            String path = str(file.get("container_path"));
            if (!path.startsWith("/") || path.contains("..")) {
                throw Violations.ofField("container_path", path,
                    violationText("file_path_absolute"));
            }
        }

        InstanceTemplateModel templates = Models.get(InstanceTemplateModel.class);
        Row template = templates.createEmptyRow();
        template.set(InstanceTemplateModel.NAME, name);
        template.set(InstanceTemplateModel.DESCRIPTION, str(body.get("description")));
        template.set(InstanceTemplateModel.KIND, kind);
        template.set(InstanceTemplateModel.SETTINGS,
            body.get("settings") instanceof Map<?, ?> settings ? castMap(settings) : Map.of());
        template.set(InstanceTemplateModel.VERSION,
            body.get("version") instanceof Number v && v.intValue() > 0 ? v.intValue() : 1);
        template.set(InstanceTemplateModel.INSTALL_IMAGE, str(body.get("install_image")));
        template.set(InstanceTemplateModel.INSTALL_SCRIPT, str(body.get("install_script")));
        template.set(InstanceTemplateModel.UPDATE_SCRIPT, str(body.get("update_script")));
        template.set(InstanceTemplateModel.REINSTALL_POLICY,
            InstanceTemplateModel.REINSTALL_CLEAR.equals(body.get("reinstall_policy"))
                ? InstanceTemplateModel.REINSTALL_CLEAR : InstanceTemplateModel.REINSTALL_PRESERVE);
        // An unknown token fails CLOSED to the column default rather than to a guess;
        // the model then refuses the document if it also carried a readiness line.
        ReadinessKind readinessKind = ReadinessKind.forToken(str(body.get("readiness_kind")));
        template.set(InstanceTemplateModel.READINESS_KIND,
            readinessKind == null ? ReadinessKind.PORT.token() : readinessKind.token());
        template.set(InstanceTemplateModel.READINESS_LINE, str(body.get("readiness_line")));
        template.set(InstanceTemplateModel.READINESS_TARGET, str(body.get("readiness_target")));
        template.set(InstanceTemplateModel.STOP_COMMAND, str(body.get("stop_command")));
        // NEVER approved by import -- that is the operator's explicit act.
        template.set(InstanceTemplateModel.SOURCE,
            source == null || source.isBlank() ? "import" : source.trim());
        template.set(InstanceTemplateModel.SOURCE_CHECKSUM, computed);
        template.set(InstanceTemplateModel.IMPORTED_AT, Instant.now());
        templates.save(template);
        int templateId = template.get(InstanceTemplateModel.ID);

        InstanceTemplateVariableModel variableModel = Models.get(InstanceTemplateVariableModel.class);
        for (Map<String, Object> variable : variables) {
            Row row = variableModel.createEmptyRow();
            row.set(InstanceTemplateVariableModel.TEMPLATE_ID, templateId);
            row.set(InstanceTemplateVariableModel.KEY, str(variable.get("key")));
            row.set(InstanceTemplateVariableModel.LABEL, str(variable.get("label")));
            row.set(InstanceTemplateVariableModel.DESCRIPTION, str(variable.get("description")));
            row.set(InstanceTemplateVariableModel.TYPE, str(variable.get("type")));
            row.set(InstanceTemplateVariableModel.SETTINGS,
                variable.get("settings") instanceof Map<?, ?> settings ? castMap(settings) : Map.of());
            row.set(InstanceTemplateVariableModel.REQUIRED,
                Boolean.TRUE.equals(variable.get("required")));
            row.set(InstanceTemplateVariableModel.DEFAULT_VALUE, str(variable.get("default_value")));
            variableModel.save(row);
        }

        InstanceTemplateFileModel fileModel = Models.get(InstanceTemplateFileModel.class);
        for (Map<String, Object> file : files) {
            Row row = fileModel.createEmptyRow();
            row.set(InstanceTemplateFileModel.TEMPLATE_ID, templateId);
            row.set(InstanceTemplateFileModel.CONTAINER_PATH, str(file.get("container_path")));
            row.set(InstanceTemplateFileModel.CONTENT, str(file.get("content")));
            String mode = str(file.get("mode"));
            row.set(InstanceTemplateFileModel.MODE, mode.isEmpty() ? "0644" : mode);
            fileModel.save(row);
        }
        return templateId;
    }

    // -- plumbing -------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static @NonNull List<Map<String, Object>> entryList(@Nullable Object value) {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    entries.add(castMap(map));
                }
            }
        }
        return entries;
    }

    private static @NonNull String str(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Microcopy violationText(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
