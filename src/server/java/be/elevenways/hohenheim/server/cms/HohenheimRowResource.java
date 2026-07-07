package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Base for the Hohenheim admin resources: every mutation writes an audit-log
 * entry and (when {@link #reloadsProxy()}) rebuilds the proxy routing table.
 */
public abstract class HohenheimRowResource extends RowResource {

    /** The audit-log resource-type token for this resource's records. */
    protected abstract @NonNull String auditResourceType();

    /** Whether configuration changes to this resource affect proxy routing. */
    protected boolean reloadsProxy() {
        return true;
    }

    /** The human label used in audit entries; defaults to the row's "name" column. */
    protected @Nullable String auditName(@NonNull Row row) {
        Object name = row.has("name") ? row.get("name") : null;
        return name != null ? String.valueOf(name) : null;
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Object pk = super.persistRow(coerced, accessContext);
        afterPersist(pk, coerced, accessContext);
        Row saved = loadRow(pk, accessContext);
        CmsSupport.audit(accessContext, AuditLogModel.ACTION_CREATED, auditResourceType(),
            pk, saved != null ? auditName(saved) : null);
        if (reloadsProxy()) {
            CmsSupport.reloadProxy();
        }
        return pk;
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        super.updateRow(existing, coerced, accessContext);
        CmsSupport.audit(accessContext, AuditLogModel.ACTION_UPDATED, auditResourceType(),
            rowKey(existing), auditName(existing));
        if (reloadsProxy()) {
            CmsSupport.reloadProxy();
        }
    }

    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        String name = auditName(existing);
        Object key = rowKey(existing);
        super.deleteRow(existing, accessContext);
        CmsSupport.audit(accessContext, AuditLogModel.ACTION_DELETED, auditResourceType(), key, name);
        if (reloadsProxy()) {
            CmsSupport.reloadProxy();
        }
    }

    /** Hook after a successful create, before the audit entry. */
    protected void afterPersist(@NonNull Object pk, @NonNull Map<String, Object> coerced,
                                @NonNull AccessContext accessContext) {
    }

    /** The coerced map arrives immutable; overrides that stage extra values copy it first. */
    protected static @NonNull Map<String, Object> mutable(@NonNull Map<String, Object> coerced) {
        return new java.util.LinkedHashMap<>(coerced);
    }
}
