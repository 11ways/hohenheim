package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.HostMode;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.server.docker.HostService;
import be.elevenways.hohenheim.server.incus.IncusEndpoint;
import be.elevenways.hohenheim.server.options.HostOptions;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteScope;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * The host enrollment save envelope: commit inventory first, then spend the one-use token outside its transaction.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class HostInventoryWrites {
    private static final Pattern SSH_TARGET = Pattern.compile(
        "^(?:[A-Za-z0-9_.][A-Za-z0-9_.-]*@)?(?:[A-Za-z0-9_.][A-Za-z0-9_.-]*|\\[[0-9A-Fa-f:]+\\])(?::[0-9]{1,5})?$");
    private HostInventoryWrites() {}

    /** @param merged the create's values, its create defaults included */
    static Object create(Map<String, Object> submitted, OperationInput.Values merged) {
        Map<String, Object> values = new LinkedHashMap<>(submitted);
        validate(merged, null);
        String token = token(values, merged, null);
        Row row = Models.get(HostModel.class).createEmptyRow();
        assign(row, values, merged);
        Models.get(HostModel.class).getResolvedDatasource().withTransaction(tx -> Models.get(HostModel.class)
                .save(row));
        Object key = row.get(HostModel.ID);
        report(HostEnrolment.afterCreate(key, token));
        HostOptions.refresh();
        return key;
    }

    /**
     * @param submitted the entries this write carries, the only ones assigned
     * @param merged the stored host's values with the submission over them, which every check reads
     */
    static void update(Row row, Map<String, Object> submitted, OperationInput.Values merged) {
        Objects.requireNonNull(row);
        Map<String, Object> values = new LinkedHashMap<>(submitted);
        if (HostParts.local(row)) {
            for (String field : HostParts.LOCAL_IMMUTABLE) {
                if (values.containsKey(field) && !Objects.equals(Texts.trimmedOrNull(values.get(field)),
                        Texts.trimmedOrNull(row.get(field)))) {
                    throw Violations.ofField(field, values.get(field),
                        HohenheimMicrocopy.VIOLATIONS.of("local_server_immutable"));
                }
            }
            if (tokenText(values.get(HostParts.INCUS_TRUST_TOKEN.getName())) != null) {
                throw Violations.ofField(HostParts.INCUS_TRUST_TOKEN.getName(), "",
                    HohenheimMicrocopy.VIOLATIONS.of("local_server_immutable"));
            }
            Models.get(HostModel.class).getResolvedDatasource().withTransaction(tx -> {
                apply(row, values, HostModel.PUBLIC_IPV4);
                apply(row, values, HostModel.PUBLIC_IPV6);
                apply(row, values, HostModel.LAN_PARENT);
                if (values.get(HostModel.POSTURE.getName()) instanceof String posture) row.set(
                        HostModel.POSTURE, posture);
                Models.get(HostModel.class).save(row);
            });
        } else {
            validate(merged, row);
            String token = token(values, merged, row);
            Models.get(HostModel.class).getResolvedDatasource().withTransaction(tx -> {
                assign(row, values, merged);
                Models.get(HostModel.class).save(row);
            });
            report(HostEnrolment.afterUpdate(row.get(HostModel.ID), token));
        }
        HostOptions.refresh();
    }

    private static void assign(Row row, Map<String, Object> values, OperationInput.Values merged) {
        for (Field<String, ?> field : List.of(HostModel.NAME, HostModel.SSH_TARGET,
                HostModel.PUBLIC_IPV4, HostModel.PUBLIC_IPV6, HostModel.LAN_PARENT)) apply(row, values, field);
        if (values.get(HostModel.RUNTIME.getName()) instanceof String runtime) row.set(HostModel.RUNTIME, runtime);
        if (values.get(HostModel.POSTURE.getName()) instanceof String posture) row.set(HostModel.POSTURE, posture);
        if (values.containsKey(HostModel.INCUS_URL.getName())) {
            row.set(HostModel.INCUS_URL, Texts.trimmedOrNull(values.get(HostModel.INCUS_URL.getName())));
        }
        row.set(HostModel.MODE, (HostModel.RUNTIME_DOCKER.equals(runtime(merged, row)) ? HostMode.SSH
                : HostMode.LOCAL).token());
    }

    private static void apply(Row row, Map<String, Object> values, Field<String, ?> field) {
        if (values.containsKey(field.getName())) row.set(field, values.get(field.getName()) == null ? null
            : String.valueOf(values.get(field.getName())));
    }

    /** @return the host's runtime; a blank one reads as the stored row's own answer, a docker host on a create */
    private static String runtime(OperationInput.Values merged, @Nullable Row row) {
        return merged.get(HostModel.RUNTIME.getName()) instanceof String value && !value.isBlank() ? value
            : row == null ? HostModel.RUNTIME_DOCKER : HostModel.runtimeOf(row);
    }

    private static @Nullable String token(Map<String, Object> values, OperationInput.Values merged,
                                          @Nullable Row row) {
        String token = tokenText(values.remove(HostParts.INCUS_TRUST_TOKEN.getName()));
        if (token != null && (!HostModel.RUNTIME_INCUS.equals(runtime(merged, row))
                || !trimmed(merged.get(HostModel.INCUS_URL.getName())).startsWith("https://"))) {
            throw Violations.ofField(HostParts.INCUS_TRUST_TOKEN.getName(), "",
                HohenheimMicrocopy.VIOLATIONS.of("incus_token_needs_https"));
        }
        return token;
    }

    /** The pipeline seals the secret token entry, so its text is revealed here, never stringified ("[secret]"). */
    private static @Nullable String tokenText(@Nullable Object raw) {
        return raw instanceof Secret secret ? Texts.trimmedOrNull(secret.reveal()) : Texts.trimmedOrNull(raw);
    }

    private static void validate(OperationInput.Values merged, @Nullable Row row) {
        String name = trimmed(merged.get(HostModel.NAME.getName()));
        if (HostService.LOCAL_HOST_NAME.equals(name)) throw Violations.ofField("name", name,
            HohenheimMicrocopy.VIOLATIONS.of(row == null ? "local_server_reserved" : "local_server_immutable"));
        if (!HostModel.isValidName(name)) {
            throw Violations.ofField("name", name, HohenheimMicrocopy.VIOLATIONS.of("name_format"));
        }
        String target = trimmed(merged.get(HostModel.SSH_TARGET.getName()));
        if (HostModel.RUNTIME_INCUS.equals(runtime(merged, row))) {
            String url = trimmed(merged.get(HostModel.INCUS_URL.getName()));
            try { IncusEndpoint.parse(url); } catch (IllegalArgumentException bad) {
                throw Violations.ofField("incus_url", url, HohenheimMicrocopy.VIOLATIONS.of("incus_url_format"));
            }
            if (target.isEmpty()) return;
        }
        if (target.isEmpty() || !SSH_TARGET.matcher(target).matches()) {
            throw Violations.ofField("ssh_target", target, HohenheimMicrocopy.VIOLATIONS.of("ssh_target_format"));
        }
    }

    private static void report(HostEnrolment.Outcome outcome) {
        Microcopy failure = outcome.failure();
        Conduit conduit = RouteScope.currentConduit();
        if (failure != null && conduit != null) HohenheimFlash.warning(conduit, failure);
    }
}
