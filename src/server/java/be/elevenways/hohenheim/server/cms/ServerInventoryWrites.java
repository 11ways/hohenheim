package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.HostMode;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.incus.IncusEndpoint;
import be.elevenways.hohenheim.server.options.ServerOptions;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteScope;
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
final class ServerInventoryWrites {
    private static final Pattern SSH_TARGET = Pattern.compile(
        "^(?:[A-Za-z0-9_.][A-Za-z0-9_.-]*@)?(?:[A-Za-z0-9_.][A-Za-z0-9_.-]*|\\[[0-9A-Fa-f:]+\\])(?::[0-9]{1,5})?$");
    private ServerInventoryWrites() {}

    /** @param merged the create's values, its create defaults included */
    static Object create(Map<String, Object> submitted, OperationInput.Values merged) {
        Map<String, Object> values = new LinkedHashMap<>(submitted);
        validate(merged, null);
        String token = token(values, merged, null);
        Row row = Models.get(ServerModel.class).createEmptyRow();
        assign(row, values, merged);
        Models.get(ServerModel.class).getResolvedDatasource().withTransaction(tx -> Models.get(ServerModel.class)
                .save(row));
        Object key = row.get(ServerModel.ID);
        report(HostEnrolment.afterCreate(key, token));
        ServerOptions.refresh();
        return key;
    }

    /**
     * @param submitted the entries this write carries, the only ones assigned
     * @param merged the stored host's values with the submission over them, which every check reads
     */
    static void update(Row row, Map<String, Object> submitted, OperationInput.Values merged) {
        Objects.requireNonNull(row);
        Map<String, Object> values = new LinkedHashMap<>(submitted);
        if (ServerParts.local(row)) {
            for (String field : ServerParts.LOCAL_IMMUTABLE) {
                if (values.containsKey(field) && !Objects.equals(Texts.trimmedOrNull(values.get(field)),
                        Texts.trimmedOrNull(row.get(field)))) {
                    throw Violations.ofField(field, values.get(field),
                        HohenheimMicrocopy.VIOLATIONS.of("local_server_immutable"));
                }
            }
            if (Texts.trimmedOrNull(values.get(ServerParts.INCUS_TRUST_TOKEN.getName())) != null) {
                throw Violations.ofField(ServerParts.INCUS_TRUST_TOKEN.getName(), "",
                    HohenheimMicrocopy.VIOLATIONS.of("local_server_immutable"));
            }
            Models.get(ServerModel.class).getResolvedDatasource().withTransaction(tx -> {
                apply(row, values, ServerModel.PUBLIC_IPV4);
                apply(row, values, ServerModel.PUBLIC_IPV6);
                apply(row, values, ServerModel.LAN_PARENT);
                if (values.get(ServerModel.POSTURE.getName()) instanceof String posture) row.set(
                        ServerModel.POSTURE, posture);
                Models.get(ServerModel.class).save(row);
            });
        } else {
            validate(merged, row);
            String token = token(values, merged, row);
            Models.get(ServerModel.class).getResolvedDatasource().withTransaction(tx -> {
                assign(row, values, merged);
                Models.get(ServerModel.class).save(row);
            });
            report(HostEnrolment.afterUpdate(row.get(ServerModel.ID), token));
        }
        ServerOptions.refresh();
    }

    private static void assign(Row row, Map<String, Object> values, OperationInput.Values merged) {
        for (Field<String, ?> field : List.of(ServerModel.NAME, ServerModel.SSH_TARGET,
                ServerModel.PUBLIC_IPV4, ServerModel.PUBLIC_IPV6, ServerModel.LAN_PARENT)) apply(row, values, field);
        if (values.get(ServerModel.RUNTIME.getName()) instanceof String runtime) row.set(ServerModel.RUNTIME, runtime);
        if (values.get(ServerModel.POSTURE.getName()) instanceof String posture) row.set(ServerModel.POSTURE, posture);
        if (values.containsKey(ServerModel.INCUS_URL.getName())) {
            row.set(ServerModel.INCUS_URL, Texts.trimmedOrNull(values.get(ServerModel.INCUS_URL.getName())));
        }
        row.set(ServerModel.MODE, (ServerModel.RUNTIME_DOCKER.equals(runtime(merged, row)) ? HostMode.SSH
                : HostMode.LOCAL).token());
    }

    private static void apply(Row row, Map<String, Object> values, Field<String, ?> field) {
        if (values.containsKey(field.getName())) row.set(field, values.get(field.getName()) == null ? null
            : String.valueOf(values.get(field.getName())));
    }

    /** @return the host's runtime; a blank one reads as the stored row's own answer, a docker host on a create */
    private static String runtime(OperationInput.Values merged, @Nullable Row row) {
        return merged.get(ServerModel.RUNTIME.getName()) instanceof String value && !value.isBlank() ? value
            : row == null ? ServerModel.RUNTIME_DOCKER : ServerModel.runtimeOf(row);
    }

    private static @Nullable String token(Map<String, Object> values, OperationInput.Values merged,
                                          @Nullable Row row) {
        String token = Texts.trimmedOrNull(values.remove(ServerParts.INCUS_TRUST_TOKEN.getName()));
        if (token != null && (!ServerModel.RUNTIME_INCUS.equals(runtime(merged, row))
                || !trimmed(merged.get(ServerModel.INCUS_URL.getName())).startsWith("https://"))) {
            throw Violations.ofField(ServerParts.INCUS_TRUST_TOKEN.getName(), "",
                HohenheimMicrocopy.VIOLATIONS.of("incus_token_needs_https"));
        }
        return token;
    }

    private static void validate(OperationInput.Values merged, @Nullable Row row) {
        String name = trimmed(merged.get(ServerModel.NAME.getName()));
        if (ServerService.LOCAL_HOST_NAME.equals(name)) throw Violations.ofField("name", name,
            HohenheimMicrocopy.VIOLATIONS.of(row == null ? "local_server_reserved" : "local_server_immutable"));
        if (!ServerModel.isValidName(name)) {
            throw Violations.ofField("name", name, HohenheimMicrocopy.VIOLATIONS.of("name_format"));
        }
        String target = trimmed(merged.get(ServerModel.SSH_TARGET.getName()));
        if (ServerModel.RUNTIME_INCUS.equals(runtime(merged, row))) {
            String url = trimmed(merged.get(ServerModel.INCUS_URL.getName()));
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
