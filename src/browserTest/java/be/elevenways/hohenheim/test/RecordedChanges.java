package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.activity.ActivityAction;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.history.ActionModel;
import be.elevenways.zenit.common.orm.history.ChangeFactModel;
import be.elevenways.zenit.common.orm.history.ChangeModel;
import be.elevenways.zenit.common.orm.history.ChangeOp;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.test.support.HistoryTestSupport;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A record's stored history changes as Hohenheim's tests assert them: each change beside its action header and facts,
 * read as written through {@link HistoryTestSupport} (no reader's gate).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class RecordedChanges {

    private RecordedChanges() {
    }

    /** One stored change of a record, with the action it belongs to and its facts by kind id. */
    public record Recorded(@NonNull Row change, @NonNull Row action, @NonNull Map<String, @Nullable Object> facts) {

        public @NonNull ChangeOp op() {
            return Objects.requireNonNull(ChangeOp.fromStored(this.change.get(ChangeModel.OP)), "change op");
        }

        public boolean has(@NonNull ActivityAction kind) {
            return this.facts.containsKey(kind.id().toString());
        }

        /** @return the fact's body, an empty map for a fact without one; null when the change has no such fact */
        @SuppressWarnings("unchecked")
        public @Nullable Map<String, Object> fact(@NonNull ActivityAction kind) {
            if (!has(kind)) {
                return null;
            }
            Object body = this.facts.get(kind.id().toString());
            return body instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }

        public @Nullable String actor() {
            return this.action.get(ActionModel.ACTOR);
        }

        public @Nullable String origin() {
            return this.action.get(ActionModel.ORIGIN);
        }

        public @NonNull String actionId() {
            return this.change.get(ChangeModel.ACTION_ID);
        }

        public @NonNull Map<String, Map<String, Object>> delta() {
            return HistoryTestSupport.deltaOf(this.change);
        }
    }

    /** @return every stored change of the record, oldest first */
    public static @NonNull List<Recorded> of(@NonNull Model model, @NonNull Object recordId) {
        return recorded(model.getResolvedDatasource(), HistoryTestSupport.changesOf(model, recordId));
    }

    /** @return every stored change of a subject (a model or a declared non-model subject), whatever its record */
    public static @NonNull List<Recorded> about(@NonNull Datasource datasource, @NonNull Identifier subject) {
        return recorded(datasource, new ChangeModel(datasource).find().noCache().withoutFindHooks()
            .where(ChangeModel.MODEL.eq(subject.toString())).orderBy(ChangeModel.ID, SortOrder.ASC).all());
    }

    private static @NonNull List<Recorded> recorded(@NonNull Datasource datasource, @NonNull List<Row> changes) {
        List<Recorded> recorded = new ArrayList<>();
        for (Row change : changes) {
            String actionId = change.get(ChangeModel.ACTION_ID);
            Row action = Objects.requireNonNull(HistoryTestSupport.actionOf(datasource, actionId),
                "action " + actionId);
            recorded.add(new Recorded(change, action, HistoryTestSupport.factsOf(datasource, change)));
        }
        return recorded;
    }

    /** Deletes a fixture action written straight into the tables ({@code HistoryFixtureRows}), its changes and facts. */
    public static void forget(@NonNull Datasource datasource, @NonNull String actionId) {
        for (Row change : new ChangeModel(datasource).find().where(ChangeModel.ACTION_ID.eq(actionId)).all()) {
            new ChangeFactModel(datasource).find().where(ChangeFactModel.CHANGE_ID.eq(change.get(ChangeModel.ID)))
                .delete();
        }
        new ChangeModel(datasource).find().where(ChangeModel.ACTION_ID.eq(actionId)).delete();
        new ActionModel(datasource).find().where(ActionModel.ID.eq(actionId)).delete();
    }

    /** @return the record's changes of this op, oldest first */
    public static @NonNull List<Recorded> of(@NonNull Model model, @NonNull Object recordId, @NonNull ChangeOp op) {
        return of(model, recordId).stream().filter(recorded -> recorded.op() == op).toList();
    }

    /** @return the record's changes carrying this fact, oldest first */
    public static @NonNull List<Recorded> withFact(@NonNull Model model, @NonNull Object recordId,
                                                   @NonNull ActivityAction kind) {
        return of(model, recordId).stream().filter(recorded -> recorded.has(kind)).toList();
    }
}
