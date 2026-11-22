package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.instance.VolumeOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.operation.ZenitPlacementSurface;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.http.body.FormSubmissionRawValues;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.server.instance.InstanceApi.onVisibleInstance;

/**
 * The volume lane of the instance API (v1): an app's declared volumes listed, declared, redeclared and deleted through
 * the Volumes tab's own operations.
 *
 * AIDEV-NOTE: no authorization decision here, InstanceApi's rule 1. The declare, redeclare and delete operations
 * ({@link VolumeOperations}) gate on the admin permission, the declare also on the app's {@code config} capability,
 * and their handlers ({@code VolumeDeclarationOperations}) ask {@code config} again; this class only resolves the
 * route's app at {@code view} (the uniform 404, and the read-only /manage twin's scope for the listing) and the volume
 * by name inside it.
 *
 * AIDEV-NOTE: the operations are invoked directly, never through ResourceWrites over the admin volume entry: that
 * entry's parent is FIXED and a fixed parent is read from the request's {@code parent} query, which an API route does
 * not carry. The route's app is {@code fixed} on the input instead, so a body naming another app is refused
 * {@code fixed} rather than declaring a volume there.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class InstanceVolumeApi {

    private InstanceVolumeApi() {
    }

    static void init() {
        // The operations' handlers, attached where the Volumes tab attaches them; idempotent.
        VolumeDeclarationOperations.init();
        HohenheimEndpoints.API_INSTANCE_VOLUMES.setHandler(onVisibleInstance((conduit, ctx, row) -> {
            int instanceId = row.get(InstanceModel.ID);
            List<Map<String, Object>> volumes = new ArrayList<>();
            for (Row volume : InstanceVolumes.declaredFor(instanceId)) {
                volumes.add(projection(volume));
            }
            return ApiConduits.json(Map.of("id", instanceId, "volumes", volumes));
        }));

        HohenheimEndpoints.API_INSTANCE_VOLUME_DECLARE.setHandler(onVisibleInstance((conduit, ctx, row) -> {
            int instanceId = row.get(InstanceModel.ID);
            Integer volumeId;
            try {
                volumeId = OperationPipeline.invoke(OperationRequest.of(VolumeOperations.CREATE,
                        ZenitPlacementSurface.HTTP_API)
                    .caller(ctx)
                    .fixed(Map.of(InstanceVolumeModel.INSTANCE_ID.getName(), String.valueOf(instanceId)))
                    .form(FormSubmissionRawValues.fromConduit(conduit))).value();
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (DomainRefusal refused) {
                return ApiConduits.refusal(conduit, refused, row);
            }
            Row volume = volumeId == null ? null : Models.get(InstanceVolumeModel.class).findById(volumeId);
            if (volume == null) {
                conduit.notFound();
                return null;
            }
            return ApiConduits.json(Map.of("id", instanceId, "status", "declared", "volume", projection(volume)));
        }));

        HohenheimEndpoints.API_INSTANCE_VOLUME_REDECLARE.setHandler(onVisibleInstance((conduit, ctx, row) -> {
            int instanceId = row.get(InstanceModel.ID);
            Map<String, Object> form = FormSubmissionRawValues.fromConduit(conduit);
            Row volume = namedVolume(conduit, instanceId, form);
            if (volume == null) {
                return null;
            }
            try {
                // Patched over the stored declaration: an entry left out keeps its value, as the panel's form does.
                OperationPipeline.invoke(OperationRequest.of(VolumeOperations.UPDATE, ZenitPlacementSurface.HTTP_API)
                    .caller(ctx)
                    .subjects(List.of(volume))
                    .patch(form));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (DomainRefusal refused) {
                return ApiConduits.refusal(conduit, refused, row);
            }
            Row redeclared = Models.get(InstanceVolumeModel.class).findById(volume.get(InstanceVolumeModel.ID));
            return ApiConduits.json(Map.of("id", instanceId, "status", "redeclared",
                "volume", projection(redeclared != null ? redeclared : volume)));
        }));

        HohenheimEndpoints.API_INSTANCE_VOLUME_DESTROY.setHandler(onVisibleInstance((conduit, ctx, row) -> {
            int instanceId = row.get(InstanceModel.ID);
            Row volume = namedVolume(conduit, instanceId, FormSubmissionRawValues.fromConduit(conduit));
            if (volume == null) {
                return null;
            }
            String name = volume.get(InstanceVolumeModel.NAME);
            try {
                // The Volumes tab's delete with data: the directory goes at the host first, then the declaration.
                OperationPipeline.invoke(OperationRequest.of(VolumeOperations.DESTROY, ZenitPlacementSurface.HTTP_API)
                    .caller(ctx)
                    .subjects(List.of(volume)));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (DomainRefusal refused) {
                return ApiConduits.refusal(conduit, refused, row);
            }
            return ApiConduits.json(Map.of("id", instanceId, "status", "deleted", "name", name));
        }));
    }

    /**
     * The volume of the route's app the body names; an unnamed, unknown or other app's volume is the route's 404.
     *
     * @return the volume row, or null when the response has already been ended
     */
    private static @Nullable Row namedVolume(@NonNull Conduit conduit, int instanceId,
                                             @NonNull Map<String, Object> form) {
        String name = HandlerSupport.submittedString(form, InstanceVolumeModel.NAME.getName());
        Row volume = name.isEmpty() ? null : Models.get(InstanceVolumeModel.class).find()
            .where(InstanceVolumeModel.INSTANCE_ID.eq(instanceId))
            .where(InstanceVolumeModel.NAME.eq(name))
            .first();
        if (volume == null) {
            conduit.notFound();
        }
        return volume;
    }

    /**
     * THE enumerated view of a volume: its declaration and its last measured use. The host directory stays out, as the
     * instance projection keeps the placement host out.
     */
    private static @NonNull Map<String, Object> projection(@NonNull Row volume) {
        VolumeOperations.Declaration declaration = VolumeDeclarationOperations.declaration(volume);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", declaration.name());
        entry.put("container_path", declaration.container_path());
        entry.put("quota_mb", declaration.quota_mb());
        entry.put("exclusive", Boolean.TRUE.equals(declaration.exclusive()));
        entry.put("used_bytes", volume.get(InstanceVolumeModel.USED_BYTES));
        Object observed = volume.get(InstanceVolumeModel.OBSERVED_AT);
        entry.put("observed_at", observed == null ? null : String.valueOf(observed));
        return entry;
    }
}
