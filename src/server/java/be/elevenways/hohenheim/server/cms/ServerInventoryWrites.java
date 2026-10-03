package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.HostMode;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.incus.IncusEndpoint;
import be.elevenways.hohenheim.server.options.ServerOptions;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.conduit.Conduit;
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

    static Object create(Map<String, Object> submitted) {
        Map<String, Object> values = new LinkedHashMap<>(submitted);
        validate(values, null);
        String token = token(values, null);
        Row row = Models.get(ServerModel.class).createEmptyRow();
        assign(row, values);
        Models.get(ServerModel.class).getResolvedDatasource().withTransaction(tx -> Models.get(ServerModel.class).save(row));
        Object key = row.get(ServerModel.ID);
        report(HostEnrolment.afterCreate(key, token));
        ServerOptions.refresh();
        return key;
    }

    static void update(Row row, Map<String, Object> submitted) {
        Objects.requireNonNull(row);
        Map<String, Object> values = new LinkedHashMap<>(submitted);
        if (ServerParts.local(row)) {
            for (String field : ServerParts.LOCAL_IMMUTABLE) {
                if (values.containsKey(field) && !Objects.equals(Texts.trimmedOrNull(values.get(field)),
                        Texts.trimmedOrNull(row.get(field)))) {
                    throw Violations.ofField(field, values.get(field), CmsSupport.violationText("local_server_immutable"));
                }
            }
            if (Texts.trimmedOrNull(values.get(ServerParts.INCUS_TRUST_TOKEN.getName())) != null) {
                throw Violations.ofField(ServerParts.INCUS_TRUST_TOKEN.getName(), "", CmsSupport.violationText("local_server_immutable"));
            }
            Models.get(ServerModel.class).getResolvedDatasource().withTransaction(tx -> {
                apply(row, values, ServerModel.PUBLIC_IPV4);
                apply(row, values, ServerModel.PUBLIC_IPV6);
                if (values.get(ServerModel.POSTURE.getName()) instanceof String posture) row.set(ServerModel.POSTURE, posture);
                Models.get(ServerModel.class).save(row);
            });
        } else {
            validate(values, row);
            String token = token(values, row);
            Models.get(ServerModel.class).getResolvedDatasource().withTransaction(tx -> {
                assign(row, values);
                Models.get(ServerModel.class).save(row);
            });
            report(HostEnrolment.afterUpdate(row.get(ServerModel.ID), token));
        }
        ServerOptions.refresh();
    }

    private static void assign(Row row, Map<String, Object> values) {
        for (Field<String, ?> field : List.of(ServerModel.NAME, ServerModel.SSH_TARGET,
                ServerModel.PUBLIC_IPV4, ServerModel.PUBLIC_IPV6)) apply(row, values, field);
        if (values.get(ServerModel.RUNTIME.getName()) instanceof String runtime) row.set(ServerModel.RUNTIME, runtime);
        if (values.get(ServerModel.POSTURE.getName()) instanceof String posture) row.set(ServerModel.POSTURE, posture);
        if (values.containsKey(ServerModel.INCUS_URL.getName())) {
            row.set(ServerModel.INCUS_URL, Texts.trimmedOrNull(values.get(ServerModel.INCUS_URL.getName())));
        }
        row.set(ServerModel.MODE, (ServerModel.RUNTIME_DOCKER.equals(runtime(values, row)) ? HostMode.SSH : HostMode.LOCAL).token());
    }

    private static void apply(Row row, Map<String, Object> values, Field<String, ?> field) {
        if (values.containsKey(field.getName())) row.set(field, values.get(field.getName()) == null ? null
            : String.valueOf(values.get(field.getName())));
    }

    private static String runtime(Map<String, Object> values, @Nullable Row row) {
        return values.get(ServerModel.RUNTIME.getName()) instanceof String value && !value.isBlank() ? value
            : row == null ? ServerModel.RUNTIME_DOCKER : ServerModel.runtimeOf(row);
    }

    private static @Nullable String token(Map<String, Object> values, @Nullable Row row) {
        String token = Texts.trimmedOrNull(values.remove(ServerParts.INCUS_TRUST_TOKEN.getName()));
        if (token != null && (!ServerModel.RUNTIME_INCUS.equals(runtime(values, row))
                || !CmsSupport.textOf(values, row, ServerModel.INCUS_URL).startsWith("https://"))) {
            throw Violations.ofField(ServerParts.INCUS_TRUST_TOKEN.getName(), "", CmsSupport.violationText("incus_token_needs_https"));
        }
        return token;
    }

    private static void validate(Map<String, Object> values, @Nullable Row row) {
        String name = CmsSupport.textOf(values, row, ServerModel.NAME);
        if (ServerService.LOCAL_HOST_NAME.equals(name)) throw Violations.ofField("name", name,
            CmsSupport.violationText(row == null ? "local_server_reserved" : "local_server_immutable"));
        if (name.isEmpty() || !name.matches("[a-z0-9][a-z0-9-]*")) {
            throw Violations.ofField("name", name, CmsSupport.violationText("name_format"));
        }
        String target = CmsSupport.textOf(values, row, ServerModel.SSH_TARGET);
        if (ServerModel.RUNTIME_INCUS.equals(runtime(values, row))) {
            String url = CmsSupport.textOf(values, row, ServerModel.INCUS_URL);
            try { IncusEndpoint.parse(url); } catch (IllegalArgumentException bad) {
                throw Violations.ofField("incus_url", url, CmsSupport.violationText("incus_url_format"));
            }
            if (target.isEmpty()) return;
        }
        if (target.isEmpty() || !SSH_TARGET.matcher(target).matches()) {
            throw Violations.ofField("ssh_target", target, CmsSupport.violationText("ssh_target_format"));
        }
    }

    private static void report(HostEnrolment.Outcome outcome) {
        Microcopy failure = outcome.failure();
        Conduit conduit = RouteScope.currentConduit();
        if (failure != null && conduit != null) HohenheimFlash.warning(conduit, failure);
    }
}
