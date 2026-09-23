package be.elevenways.hohenheim.server.cms;

import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * A row resource whose every create and update first passes ONE validate-and-canonicalize step.
 *
 * AIDEV-NOTE: the step receives a MUTABLE copy of the partial write, because the coerced map the
 * CMS hands over is immutable (CoercedWrite) and canonical values -- a trimmed name, a normalized
 * path -- must be written BACK so the stored value is the validated one. It is still PARTIAL: an
 * absent key means leave alone, so read a sibling through {@link CmsSupport#valueOf} or the
 * existing row, never straight off the map.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
abstract class ValidatedRowResource extends RowResource {

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        this.validate(values, null);
        return super.persistRow(values, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        this.validate(values, existing);
        super.updateRow(existing, values, accessContext);
    }

    /**
     * Refuse the write or canonicalize it in place.
     *
     * @param values   a mutable copy of the partial write
     * @param existing the stored row, or null on a create
     * @throws be.elevenways.zenit.common.validation.Violations when the write is refused
     */
    protected abstract void validate(@NonNull Map<String, Object> values, @Nullable Row existing);
}
