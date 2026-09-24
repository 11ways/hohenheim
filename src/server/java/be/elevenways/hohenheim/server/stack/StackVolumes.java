package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The daemon volumes a stack MATERIALIZES: which names it declares, whether a volume the
 * daemon lists is really this stack's, and the refusal of a declaration whose volume name
 * another stack already uses.
 *
 * AIDEV-NOTE: the naming scheme {@code <stack handle>-<mount>} is ambiguous -- both halves
 * may contain '-', so stack "a" mounting "b-c" and stack "a-b" mounting "c" materialize the
 * SAME volume. It is NOT renamed: production volumes carry these names and must keep
 * resolving. Instead the two consequences are closed separately. A destructive purge removes
 * EXACTLY the names this stack declares and only when the volume's own labels attribute it
 * to this stack (it used to sweep every volume starting with the stack's prefix, which is
 * another stack's data whenever one stack's name is a dash-extension of another's). And a
 * NEW declaration whose materialized name another stack already uses is refused at the
 * write funnel, so no further collision can be authored.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class StackVolumes {

    private static boolean installed;

    private StackVolumes() {
    }

    // -- declared names ---------------------------------------------------------

    /** The mounts one service record declares (enabled or not), in its stored order. */
    static @NonNull List<StackSpec.MountSpec> mountsOf(@NonNull Row serviceRow) {
        List<StackSpec.MountSpec> mounts = new ArrayList<>();
        for (Row mount : serviceRow.getRecords(StackServiceModel.MOUNTS)) {
            StackSpec.MountSpec read = StackSpec.MountSpec.read(mount::get);
            if (read != null) {
                mounts.add(read);
            }
        }
        return mounts;
    }

    /** The volume names the stack itself creates for these mounts (tmpfs and adopted ones excluded). */
    static @NonNull Set<String> materializedNames(@NonNull String stackName,
                                                 @NonNull Collection<StackSpec.MountSpec> mounts) {
        Set<String> names = new LinkedHashSet<>();
        for (StackSpec.MountSpec mount : mounts) {
            if (mount.materialized()) {
                names.add(StackInstances.volumeName(stackName, mount));
            }
        }
        return names;
    }

    /** The adopted (external) volume names these mounts reference. */
    private static @NonNull Set<String> adoptedNames(@NonNull Collection<StackSpec.MountSpec> mounts) {
        Set<String> names = new LinkedHashSet<>();
        for (StackSpec.MountSpec mount : mounts) {
            if (!StackServiceModel.MOUNT_TMPFS.equals(mount.type()) && mount.externalName() != null) {
                names.add(mount.externalName());
            }
        }
        return names;
    }

    /**
     * Every volume name the stack materializes: each service RECORD's mounts (a disabled
     * service's volume is still the stack's), plus whatever the given specs declare (the
     * deployed snapshot can name a mount a record has since dropped).
     */
    public static @NonNull Set<String> declaredBy(int stackId, @NonNull String stackName,
                                                  @NonNull Collection<StackSpec> alsoDeclared) {
        Set<String> names = new LinkedHashSet<>();
        for (Row service : Models.get(StackServiceModel.class).findByStackId(stackId)) {
            names.addAll(materializedNames(stackName, mountsOf(service)));
        }
        for (StackSpec spec : alsoDeclared) {
            for (StackSpec.ServiceSpec service : spec.services()) {
                names.addAll(materializedNames(stackName, service.mounts()));
            }
        }
        return names;
    }

    // -- the purge ---------------------------------------------------------------

    /**
     * Remove exactly the named volumes, each only when its labels attribute it to this
     * stack. A name the daemon does not list is already gone; a volume that is not
     * attributably ours is left in place and logged -- we never remove what we cannot
     * prove we created.
     *
     * @return the volumes that were removed
     * @throws IOException when the daemon cannot list or refuses a removal (still attached)
     */
    public static @NonNull List<String> purge(@NonNull DockerClient docker, int stackId,
                                              @NonNull String stackName,
                                              @NonNull Set<String> names) throws IOException {
        Map<String, Map<?, ?>> listed = new LinkedHashMap<>();
        for (Object entry : docker.listVolumes()) {
            if (entry instanceof Map<?, ?> volume && volume.get("Name") instanceof String name) {
                listed.put(name, volume.get("Labels") instanceof Map<?, ?> labels ? labels : Map.of());
            }
        }
        List<String> removed = new ArrayList<>();
        for (String name : names) {
            Map<?, ?> labels = listed.get(name);
            if (labels == null) {
                continue;
            }
            if (!ownedByStack(labels, stackId, stackName)) {
                Blast.log("STACK: kept volume", name, "- stack", stackName, "declares it, but its"
                    + " labels attribute it to someone else (a colliding volume name); remove it"
                    + " by hand if it really is debris");
                continue;
            }
            docker.removeVolume(name, true);
            removed.add(name);
            Blast.log("STACK: removed volume", name);
        }
        return removed;
    }

    /**
     * Whether a volume's labels attribute it to this stack: created by one of its lowered
     * service instances (the instance owner labels every lowered volume is born with), or by
     * the pre-lowering deployer (the stack's own owner pair, or its name label).
     */
    static boolean ownedByStack(@NonNull Map<?, ?> labels, int stackId, @NonNull String stackName) {
        if (stackName.equals(labels.get(StackInstances.LEGACY_LABEL_STACK))) {
            return true;
        }
        // Both owner shapes are asked through the ONE ownership test, controller token
        // included: another controller's stack or instance #N is not ours to remove.
        OwnerLabels.Owner owner = OwnerLabels.parse(labels);
        if (OwnerLabels.matches(owner, StackModel.MODEL_ID, stackId)) {
            return true;
        }
        Integer instanceId = OwnerLabels.isOurs(owner) ? OwnerLabels.instanceIdOf(owner) : null;
        if (instanceId == null) {
            return false;
        }
        // findById, not a live-only query: by purge time the service instances are
        // already soft-deleted, and their rows are exactly the evidence asked for.
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        return instance != null
            && StackServiceModel.MODEL_ID.toString().equals(instance.get(InstanceModel.GENERATED_FOR_MODEL))
            && StackInstances.settingsOf(instance).get(StackServiceKind.STACK_ID.getName())
                instanceof Number owningStack
            && owningStack.intValue() == stackId;
    }

    // -- the declaration refusal -------------------------------------------------

    /**
     * Refuse names that would make this stack share a volume with ANOTHER stack: a name it
     * would materialize that another stack materializes or adopts, or a name it would adopt
     * that another stack materializes. Two stacks adopting the same external volume is a
     * deliberate share and passes; so does a volume shared between services of one stack.
     *
     * @param materialized the NEW names this write would materialize
     * @param adopted      the NEW external names this write would adopt
     * @throws Violations {@code stack_volume_name_taken} on {@code field}
     */
    static void requireNoCollision(int stackId, @NonNull Set<String> materialized,
                                   @NonNull Set<String> adopted, @NonNull String field) {
        if (materialized.isEmpty() && adopted.isEmpty()) {
            return;
        }
        Map<Integer, String> stackNames = new LinkedHashMap<>();
        for (Row stack : Models.get(StackModel.class).find().all()) {
            Integer id = stack.get(StackModel.ID);
            String name = stack.get(StackModel.NAME);
            if (id != null && id != stackId && name != null && !name.isBlank()) {
                stackNames.put(id, name);
            }
        }
        for (Row service : Models.get(StackServiceModel.class).find().all()) {
            Integer otherStackId = service.get(StackServiceModel.STACK_ID);
            String otherName = otherStackId == null ? null : stackNames.get(otherStackId);
            if (otherName == null) {
                continue;
            }
            List<StackSpec.MountSpec> mounts = mountsOf(service);
            Set<String> theirMaterialized = materializedNames(otherName, mounts);
            Set<String> theirAdopted = adoptedNames(mounts);
            for (String name : materialized) {
                if (theirMaterialized.contains(name) || theirAdopted.contains(name)) {
                    throw taken(field, name, otherName);
                }
            }
            for (String name : adopted) {
                if (theirMaterialized.contains(name)) {
                    throw taken(field, name, otherName);
                }
            }
        }
    }

    private static @NonNull Violations taken(@NonNull String field, @NonNull String volume,
                                             @NonNull String otherStack) {
        return Violations.ofField(field, volume, Microcopy.of("stack_volume_name_taken")
            .withFilter("scope", "violations")
            .withArg("volume", volume)
            .withArg("stack", otherStack));
    }

    /**
     * Install the collision refusal on the stack and stack-service write funnels (MODULES
     * stage); idempotent.
     *
     * AIDEV-NOTE: only names a write INTRODUCES are judged -- a mount list re-saved
     * unchanged, or an unrelated field edited, passes even where an older build already let
     * two stacks collide. Refusing those would lock an operator out of editing a stack that
     * is running today; the purge's label check is what keeps such a pair safe.
     */
    static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        StackServiceModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null || !row.schemaRecords().dirty().contains(
                    StackServiceModel.MOUNTS.getName())) {
                return;   // this write stages no mount list: nothing new is declared
            }
            Integer serviceId = row.has(StackServiceModel.ID.getName())
                ? row.get(StackServiceModel.ID) : null;
            Row stored = serviceId == null ? null
                : Models.get(StackServiceModel.class).findById(serviceId);
            Integer stackId = row.has(StackServiceModel.STACK_ID.getName())
                ? row.get(StackServiceModel.STACK_ID)
                : stored != null ? stored.get(StackServiceModel.STACK_ID) : null;
            String stackName = stackNameOf(stackId);
            if (stackId == null || stackName == null) {
                return;
            }
            List<StackSpec.MountSpec> declared = mountsOf(row);
            List<StackSpec.MountSpec> before = stored == null ? List.of() : mountsOf(stored);
            Set<String> materialized = materializedNames(stackName, declared);
            materialized.removeAll(materializedNames(stackName, before));
            Set<String> adopted = adoptedNames(declared);
            adopted.removeAll(adoptedNames(before));
            requireNoCollision(stackId, materialized, adopted,
                StackServiceModel.MOUNTS.getName());
        });

        StackModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null || !row.has(StackModel.NAME.getName())
                    || !row.has(StackModel.ID.getName())) {
                return;   // no name in this write, or a new stack (no services yet)
            }
            Integer stackId = row.get(StackModel.ID);
            String renamed = row.get(StackModel.NAME);
            String stored = stackNameOf(stackId);
            if (stackId == null || renamed == null || renamed.equals(stored)) {
                return;
            }
            // A rename re-derives EVERY materialized name of the stack, so all are new.
            Set<String> materialized = new LinkedHashSet<>();
            for (Row service : Models.get(StackServiceModel.class).findByStackId(stackId)) {
                materialized.addAll(materializedNames(renamed, mountsOf(service)));
            }
            requireNoCollision(stackId, materialized, Set.of(), StackModel.NAME.getName());
        });
    }

    private static @Nullable String stackNameOf(@Nullable Integer stackId) {
        if (stackId == null) {
            return null;
        }
        Row stack = Models.get(StackModel.class).findById(stackId);
        return stack == null ? null : stack.get(StackModel.NAME);
    }
}
