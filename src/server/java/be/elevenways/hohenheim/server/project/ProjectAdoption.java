package be.elevenways.hohenheim.server.project;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one-time heal that moves EXISTING per-user-granted records into projects:
 * every distinct non-empty manage-subject set found on live sites/instances becomes
 * one project whose members are exactly those subjects, and each record's manage
 * grant moves to the project's group. Reach is preserved by construction -- a user
 * could see a record iff its subject was in the set, and it is a member of the new
 * project iff its subject was in the set. Everything KEYED on the old owner packing
 * follows in the same pass: per-owner quota override rows, each live instance's
 * charged quota buckets (BOTH dimensions -- the slot and the workload memory --
 * released and re-reserved uncapped, because a heal must never refuse), and the
 * released-claim quarantine ledger's former-owner column.
 *
 * Operator-owned records (EMPTY subject set) are deliberately untouched: wrapping
 * the operator in a project would flip the empty-set-equality that admin wildcard/
 * carve-out routing is built on.
 */
public final class ProjectAdoption {

    /** What the heal did, for the boot log and the honesty report. */
    public record Result(int projectsCreated, int sitesAdopted, int instancesAdopted,
                         int quotaOverridesRewritten, int bucketsMoved, int claimsRewritten) {

        public int recordsAdopted() {
            return this.sitesAdopted + this.instancesAdopted;
        }

        @Override
        public String toString() {
            return "projects=" + this.projectsCreated + " sites=" + this.sitesAdopted
                + " instances=" + this.instancesAdopted
                + " quotaOverrides=" + this.quotaOverridesRewritten
                + " bucketsMoved=" + this.bucketsMoved
                + " claimsRewritten=" + this.claimsRewritten;
        }
    }

    private ProjectAdoption() {
    }

    /** Run the heal over every live site and instance; idempotent (adopted sets skip). */
    public static @NonNull Result run() {
        // packed old subject set -> the records (per model) carrying exactly that set
        Map<String, Set<String>> subjectsByPack = new LinkedHashMap<>();
        Map<String, List<Object>> sitesByPack = new LinkedHashMap<>();
        Map<String, List<Object>> instancesByPack = new LinkedHashMap<>();

        collect(Models.get(SiteModel.class), SiteModel.MODEL_ID, SiteModel.DELETED_AT.getName(),
            subjectsByPack, sitesByPack);
        collect(Models.get(InstanceModel.class), InstanceModel.MODEL_ID,
            InstanceModel.DELETED_AT.getName(), subjectsByPack, instancesByPack);

        int projectsCreated = 0;
        int sitesAdopted = 0;
        int instancesAdopted = 0;
        int quotaRewritten = 0;
        int bucketsMoved = 0;
        int claimsRewritten = 0;

        for (Map.Entry<String, Set<String>> entry : subjectsByPack.entrySet()) {
            String oldPack = entry.getKey();
            Set<String> subjects = entry.getValue();

            Row project = createProjectFor(subjects);
            projectsCreated++;
            String newPack = HohenheimAccess.packSubjects(Projects.ownerSubjectsOf(project));

            for (Object siteId : sitesByPack.getOrDefault(oldPack, List.of())) {
                Projects.adoptRecord(project, SiteModel.MODEL_ID, siteId);
                sitesAdopted++;
            }
            for (Object instanceId : instancesByPack.getOrDefault(oldPack, List.of())) {
                Projects.adoptRecord(project, InstanceModel.MODEL_ID, instanceId);
                bucketsMoved += moveChargedBucket(instanceId, newPack);
                instancesAdopted++;
            }

            quotaRewritten += rewriteQuotaOverride(oldPack, newPack);
            claimsRewritten += rewriteReleasedClaims(oldPack, newPack);
        }

        Result result = new Result(projectsCreated, sitesAdopted, instancesAdopted,
            quotaRewritten, bucketsMoved, claimsRewritten);
        Blast.log("PROJECTS: adoption heal:", result.toString());
        return result;
    }

    // -- collection -----------------------------------------------------------

    private static void collect(@NonNull Model model, @NonNull Identifier modelId,
                                @NonNull String deletedAtColumn,
                                @NonNull Map<String, Set<String>> subjectsByPack,
                                @NonNull Map<String, List<Object>> recordsByPack) {
        for (Row row : model.find().all()) {
            if (row.get(deletedAtColumn) != null) {
                continue;
            }
            Object id = row.get(model.getPrimaryKeyField().getName());
            if (id == null) {
                continue;
            }
            Set<String> subjects = HohenheimAccess.manageSubjectsOf(modelId, id);
            if (subjects == null || subjects.isEmpty()) {
                // Unreadable stays untouched (a heal must not guess); empty is the
                // operator, deliberately not a project.
                continue;
            }
            if (Projects.projectForSubjects(subjects) != null) {
                // Already project-owned: idempotence.
                continue;
            }
            String pack = HohenheimAccess.packSubjects(subjects);
            subjectsByPack.putIfAbsent(pack, subjects);
            recordsByPack.computeIfAbsent(pack, ignored -> new ArrayList<>()).add(id);
        }
    }

    // -- the moves ------------------------------------------------------------

    private static @NonNull Row createProjectFor(@NonNull Set<String> subjects) {
        Model projects = Models.get(ProjectModel.class);
        Row project = projects.createEmptyRow();
        project.set(ProjectModel.NAME, nameFor(subjects));
        projects.save(project);
        Row saved = projects.findById(project.get(ProjectModel.ID));

        for (String subject : subjects) {
            int separator = subject.indexOf(':');
            String type = subject.substring(0, separator);
            int id = Integer.parseInt(subject.substring(separator + 1));
            if ("user".equals(type)) {
                Projects.addMember(saved, id);
            } else if ("group".equals(type)) {
                Projects.addMemberGroup(saved, id);
            }
        }
        return saved;
    }

    /**
     * Move the instance's owner charges into the project's buckets and restamp.
     *
     * AIDEV-NOTE: the ledger move itself belongs to InstanceQuota, because an owner charge
     * has TWO dimensions (the slot and the M088 workload memory) and a copy here only ever
     * moved the slot -- leaving the old owner charged for a workload it no longer holds and
     * the new owner charged nothing. Never re-spell a bucket move at a call site.
     */
    private static int moveChargedBucket(@NonNull Object instanceId, @NonNull String newPack) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.findById(instanceId);
        if (row == null || row.get(InstanceModel.DELETED_AT) != null) {
            return 0;
        }
        int moved = InstanceQuota.moveOwnerCharges(row, newPack);
        if (moved == 0) {
            return 0;
        }
        // Set-based on purpose: the quota hook must not re-run on a bookkeeping restamp.
        instances.find().where(InstanceModel.ID.eq(row.get(InstanceModel.ID)))
            .assign(InstanceModel.QUOTA_BUCKET, InstanceQuota.bucketKeyOf(newPack)).updateAll();
        return moved;
    }

    private static int rewriteQuotaOverride(@NonNull String oldPack, @NonNull String newPack) {
        Model overrides = Models.get(InstanceQuotaModel.class);
        Row override = overrides.find().where(InstanceQuotaModel.SUBJECTS.eq(oldPack)).first();
        if (override == null) {
            return 0;
        }
        if (overrides.find().where(InstanceQuotaModel.SUBJECTS.eq(newPack)).first() != null) {
            Blast.log("PROJECTS: adoption left quota override", override.get(InstanceQuotaModel.ID),
                "unmoved; the project already has one");
            return 0;
        }
        override.set(InstanceQuotaModel.SUBJECTS, newPack);
        overrides.save(override);
        return 1;
    }

    private static int rewriteReleasedClaims(@NonNull String oldPack, @NonNull String newPack) {
        Model ledger = Models.get(ReleasedRouteClaimModel.class);
        int rewritten = 0;
        for (Row claim : ledger.find()
                .where(ReleasedRouteClaimModel.FORMER_SUBJECTS.eq(oldPack)).all()) {
            claim.set(ReleasedRouteClaimModel.FORMER_SUBJECTS, newPack);
            ledger.save(claim);
            rewritten++;
        }
        return rewritten;
    }

    /** A human name from the member subjects: display names/titles joined by " + ". */
    private static @NonNull String nameFor(@NonNull Set<String> subjects) {
        StringBuilder name = new StringBuilder();
        for (String subject : subjects) {
            String part = HohenheimAccess.subjectLabel(subject);
            if (name.length() > 0) {
                name.append(" + ");
            }
            name.append(part);
        }
        String value = name.length() == 0 ? "Adopted project" : name.toString();
        return value.length() > 150 ? value.substring(0, 150) : value;
    }
}
