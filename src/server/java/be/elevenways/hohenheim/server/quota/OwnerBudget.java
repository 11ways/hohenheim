package be.elevenways.hohenheim.server.quota;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE vocabulary of per-owner quota budgets: each member declares its ledger prefix, its
 * per-owner override column, its global default and the violation a refusal names.
 *
 * AIDEV-NOTE: the prefixes are STORED DATA. Every ledger row a production deployment holds
 * (zenit_quota_ledger.bucket_key) and every stamped {@code quota_bucket} /
 * {@code root_disk_bucket} column starts with one of them, so a spelling change here orphans
 * every reservation already booked. Never rename one; a new budget is a new member.
 *
 * AIDEV-NOTE: one budget may be charged by several dimensions -- {@link #DISK_GB} is spent by
 * both attached disk devices and the instance root disk, because they are one cap. The
 * dimensions that charge a budget are declared in {@link ChargedModel}.
 *
 * Localization: bucket keys are machine tokens; only the refusal is localized content.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public enum OwnerBudget {

    /** Live instances per owner. */
    INSTANCES("hohenheim:instances:", InstanceQuotaModel.MAX_INSTANCES,
        HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER, "quota_reached"),

    /** Workload memory (MB) per owner, at the number the driver enforces as the cap. */
    OWNER_MEMORY("hohenheim:owner_mem_mb:", InstanceQuotaModel.MAX_MEMORY_MB,
        HohenheimSettings.Quota.MAX_MEMORY_MB_PER_OWNER, "memory_quota_reached"),

    /** Disk GB per owner: attached disk devices and root disks together. */
    DISK_GB("hohenheim:disk_gb:", InstanceQuotaModel.MAX_DISK_GB,
        HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER, "disk_quota_reached"),

    /** Extra NICs per owner. */
    NICS("hohenheim:nics:", InstanceQuotaModel.MAX_NICS,
        HohenheimSettings.Quota.MAX_EXTRA_NICS_PER_OWNER, "nic_quota_reached"),

    /** Site records per owner, whether or not the site runs a workload. */
    SITES("hohenheim:sites:", InstanceQuotaModel.MAX_SITES,
        HohenheimSettings.Quota.MAX_SITES_PER_OWNER, "site_quota_reached"),

    /** Managed database records per owner, on top of the instance slot an engine spends. */
    DATABASES("hohenheim:databases:", InstanceQuotaModel.MAX_DATABASES,
        HohenheimSettings.Quota.MAX_DATABASES_PER_OWNER, "database_quota_reached"),

    /** Concurrent live previews per owner; the global setting only, no override column. */
    PREVIEWS("hohenheim:previews:", null,
        HohenheimSettings.Previews.MAX_PER_OWNER, "preview_quota_reached");

    private final @NonNull String prefix;
    private final @Nullable IntegerField overrideColumn;
    private final @NonNull SettingDefinition<Integer> fallback;
    private final @NonNull String violationKey;

    OwnerBudget(@NonNull String prefix, @Nullable IntegerField overrideColumn,
                @NonNull SettingDefinition<Integer> fallback, @NonNull String violationKey) {
        this.prefix = prefix;
        this.overrideColumn = overrideColumn;
        this.fallback = fallback;
        this.violationKey = violationKey;
    }

    /** @return the ledger prefix every bucket of this budget starts with */
    public @NonNull String prefix() {
        return this.prefix;
    }

    /** @return the violation key a refusal names */
    public @NonNull String violationKey() {
        return this.violationKey;
    }

    /** The bucket of one packed subject set (the 191-char fold, one owner). */
    public @NonNull String bucketOf(@NonNull String packedSubjects) {
        return OwnerQuota.bucketOf(this.prefix, packedSubjects);
    }

    /** The operator's bucket, which is also where a stampless row's charge lives. */
    public @NonNull String operatorBucket() {
        return this.bucketOf("");
    }

    /** The packed subject set behind one of this budget's buckets. */
    public @NonNull String packOf(@NonNull String bucket) {
        return OwnerQuota.packOf(this.prefix, bucket);
    }

    /** The cap of one owner; override 0 = nothing allowed, global 0-or-less = uncapped. */
    public @Nullable Integer limitFor(@NonNull String packedSubjects) {
        return OwnerQuota.limitOf(packedSubjects, this.overrideColumn, this.fallback);
    }

    /** How much of an owner's budget is spent (admin surfaces, tests). */
    public long usedBy(@NonNull String packedSubjects) {
        return Quotas.usedOf(this.bucketOf(packedSubjects));
    }

    /**
     * Spend {@code amount} of one bucket against its owner's cap, refusing by name.
     *
     * @throws Violations this budget's violation key
     */
    public void reserve(@NonNull String bucket, long amount) {
        OwnerQuota.reserve(bucket, amount, this.limitFor(this.packOf(bucket)), this.violationKey);
    }
}
