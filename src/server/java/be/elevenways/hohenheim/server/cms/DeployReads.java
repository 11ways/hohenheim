package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.KeptFromAssistants;
import be.elevenways.hohenheim.instance.InstanceDeploys;
import be.elevenways.hohenheim.instance.InstanceLogOperations;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.instance.InstanceOperationHandlers;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.OperationCall;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.server.operation.RecordReadLines;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An app's deploys and builds read off every panel: the record sources their codes read through, the deploy list, a
 * build's and a deploy's log, and what a {@code read} of one says beyond its source's columns.
 *
 * AIDEV-NOTE: every read here follows the one rule of daemon-worded text, {@link WithheldFailure}, in its off-panel
 * shape ({@link WithheldFailure#ofWork}): a tenant's key reads that a deploy failed, the operator why. The sources
 * project neither a failure reason, a step log, a spec snapshot, an image nor an instance id, so a {@code read}
 * cannot print one; the failure reaches a read only as a read line through that rule.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class DeployReads {

    /**
     * Deploys as their readers may read them: those of an app the reader views, or of a site it manages.
     *
     * AIDEV-NOTE: titled by its commit, never by the model's display field (the image id, operator inventory).
     */
    static final RecordSource<ReleaseOperationModel> DEPLOYS = RecordSource.of(ReleaseOperationModel.REF)
        .project(ReleaseOperationModel.KIND, ReleaseOperationModel.STATUS, ReleaseOperationModel.TRIGGER,
            ReleaseOperationModel.STARTED_AT)
        .title((row, chain) -> InstanceDeploymentsPage.shortSha(row.get(ReleaseOperationModel.COMMIT_SHA)))
        .scopedBy(TenantScopes.DEPLOYS)
        .notForAssistants(KeptFromAssistants.DEPLOY_HISTORY)
        .build();

    /**
     * Builds as their readers may read them: those of an app the reader views, or of a site it manages.
     *
     * AIDEV-NOTE: titled by the model's own titles ("Shop deploy"), which name a build after the record it built for
     * as its reader may read that record; the log and the failure reason are not projected.
     */
    static final RecordSource<BuildOperationModel> BUILDS = RecordSource.of(BuildOperationModel.REF)
        .project(BuildOperationModel.BUILDER_KIND, BuildOperationModel.STATUS, BuildOperationModel.SOURCE_REF,
            BuildOperationModel.STARTED_AT)
        .title()
        .scopedBy(TenantScopes.BUILDS)
        .notForAssistants(KeptFromAssistants.DEPLOY_HISTORY)
        .build();

    static {
        OperationHandlers.attach(InstanceLogOperations.LIST_DEPLOYS)
            .applies(InstanceOperationHandlers::authored)
            .handle(DeployReads::deploys);
        OperationHandlers.attach(InstanceLogOperations.READ_BUILD_LOG).source(BUILDS)
            .handle(call -> Objects.toString(call.subject().get(BuildOperationModel.LOG), ""));
        OperationHandlers.attach(InstanceLogOperations.READ_DEPLOY_LOG).source(DEPLOYS)
            .handle(call -> Objects.toString(WithheldFailure.ofWork(call.subjectAccess())
                .operatorOnly(call.subject().get(ReleaseOperationModel.STEP_LOG)), ""));
        RecordReadLines.register(ReleaseOperationModel.MODEL_ID, DeployReads.class, (keys, access) ->
            lines(Models.get(ReleaseOperationModel.class), ReleaseOperationModel.ID, ReleaseOperationModel.FOR_MODEL,
                ReleaseOperationModel.FOR_ID, ReleaseOperationModel.FAILURE_REASON, keys, access));
        RecordReadLines.register(BuildOperationModel.MODEL_ID, DeployReads.class, (keys, access) ->
            lines(Models.get(BuildOperationModel.class), BuildOperationModel.ID, BuildOperationModel.FOR_MODEL,
                BuildOperationModel.FOR_ID, BuildOperationModel.FAILURE_REASON, keys, access));
    }

    private DeployReads() {
    }

    /** Loads the class, attaching the handlers and the read lines; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }

    /**
     * Registers the two sources the deploy and build codes read through; ManagePanel calls it with its own, and an
     * override, as they are, so a replay against a fresh registry declares them again.
     */
    static void declareSources() {
        RecordSourceRegistry.INSTANCE.override(DEPLOYS);
        RecordSourceRegistry.INSTANCE.override(BUILDS);
    }

    /** @return one page of the app's history as the Deploys tab reads it, every row named by its code */
    private static InstanceLogOperations.@NonNull Deploys deploys(
            @NonNull OperationCall<Row, InstanceLogOperations.DeploysInput> call) {
        AccessContext reader = call.subjectAccess();
        Integer asked = call.input() == null ? null : call.input().page();
        InstanceDeploymentsPage.Lane lane = InstanceDeploymentsPage.lane(call.subject(), WithheldFailure.ofWork(reader),
            reader, asked == null ? 1 : asked);
        Map<Identifier, List<String>> keys = new LinkedHashMap<>();
        for (InstanceDeploymentsPage.Entry entry : lane.entries()) {
            keys.computeIfAbsent(entry.model(), model -> new ArrayList<>()).add(String.valueOf(entry.id()));
        }
        Map<Identifier, Map<String, String>> codes = new LinkedHashMap<>();
        keys.forEach((model, ids) -> codes.put(model, RecordCodes.codes(model, ids, reader)));
        List<InstanceLogOperations.Deploy> deploys = new ArrayList<>();
        for (InstanceDeploymentsPage.Entry entry : lane.entries()) {
            InstanceDeploys.Deploy deploy = entry.deploy();
            String code = codes.getOrDefault(entry.model(), Map.of()).get(String.valueOf(entry.id()));
            deploys.add(new InstanceLogOperations.Deploy(Objects.requireNonNullElse(code, ""), deploy.kindLabel(),
                Objects.requireNonNullElse(deploy.status().label(), deploy.status().value()),
                deploy.commit().isEmpty() ? deploy.ref() : deploy.commit(), deploy.triggerText(),
                deploy.startedAtIso().isEmpty() ? null : Instant.parse(deploy.startedAtIso()), deploy.duration(),
                deploy.detail().error()));
        }
        return new InstanceLogOperations.Deploys(deploys, lane.window().page(), lane.window().pageCount());
    }

    /**
     * A page of deploys' or builds' read lines: the record they are for by its code, and the failure as the work in
     * flight may read it; one read of the rows and one code read per owner kind, whatever the page.
     */
    private static @NonNull Map<String, List<RecordReadLines.Line>> lines(@NonNull Model model,
                                                                         @NonNull IntegerField id,
                                                                         @NonNull StringField forModel,
                                                                         @NonNull IntegerField forId,
                                                                         @NonNull StringField failureReason,
                                                                         @NonNull Collection<String> keys,
                                                                         @NonNull AccessContext access) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (String key : keys) {
            try {
                ids.add(Integer.valueOf(key));
            } catch (NumberFormatException notAKey) {
                // A key naming no row says nothing.
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Row> rows = model.find().where(id.in(ids)).all();
        Map<Identifier, List<String>> owners = new LinkedHashMap<>();
        for (Row row : rows) {
            Identifier owner = Identifier.tryParse(row.get(forModel));
            if (owner != null && ownerWord(owner) != null && row.get(forId) != null) {
                owners.computeIfAbsent(owner, kind -> new ArrayList<>()).add(String.valueOf((Object) row.get(forId)));
            }
        }
        Map<Identifier, Map<String, String>> codes = new LinkedHashMap<>();
        owners.forEach((owner, ownerIds) -> codes.put(owner, RecordCodes.codes(owner, ownerIds, access)));
        WithheldFailure failures = WithheldFailure.ofWork(access);
        Map<String, List<RecordReadLines.Line>> lines = new LinkedHashMap<>();
        for (Row row : rows) {
            List<RecordReadLines.Line> said = new ArrayList<>();
            Identifier owner = Identifier.tryParse(row.get(forModel));
            String ownerCode = owner == null ? null
                : codes.getOrDefault(owner, Map.of()).get(String.valueOf((Object) row.get(forId)));
            if (ownerCode != null) {
                said.add(new RecordReadLines.Line(Objects.requireNonNull(ownerWord(owner)), ownerCode));
            }
            String failure = failures.shown(row.get(failureReason));
            if (!failure.isEmpty()) {
                said.add(new RecordReadLines.Line("failure", failure));
            }
            if (!said.isEmpty()) {
                lines.put(String.valueOf((Object) row.get(id)), said);
            }
        }
        return lines;
    }

    /** @return the assistant's word for the record a deploy or a build is for, null for an owner it names none of */
    private static @Nullable String ownerWord(@NonNull Identifier owner) {
        if (InstanceModel.MODEL_ID.equals(owner)) {
            return "app";
        }
        return SiteModel.MODEL_ID.equals(owner) ? "site" : null;
    }
}
