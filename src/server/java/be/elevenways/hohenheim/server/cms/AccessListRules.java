package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.server.proxy.AccessRuleTree;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Some access lists and their rules, read in two statements however many lists: THE read behind whether a protected
 * path is open and how one gets in, for one path or a whole page of them.
 *
 * AIDEV-NOTE: a list page asks it once for its rows (ProtectedPathParts' page batch), so the Protection tab and a
 * site's Private paths cost the same statements for 2 paths as for 20; the one-record readers
 * ({@link ProtectedPathInvariant#isOpen}, {@link AccessRuleSummaries#wayInOf(Row)}) are a read of one.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class AccessListRules {

    private final @NonNull Map<Integer, Row> lists;
    private final @NonNull Map<Integer, List<Row>> rules;

    private AccessListRules(@NonNull Map<Integer, Row> lists, @NonNull Map<Integer, List<Row>> rules) {
        this.lists = lists;
        this.rules = rules;
    }

    /** @return the lists of these ids and their rules, each list's in its stored order */
    static @NonNull AccessListRules of(@NonNull Collection<Integer> listIds) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Integer id : listIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        Map<Integer, Row> lists = new HashMap<>();
        Map<Integer, List<Row>> rules = new HashMap<>();
        if (ids.isEmpty()) {
            return new AccessListRules(lists, rules);
        }
        for (Row list : Models.get(AccessListModel.class).find().where(AccessListModel.ID.in(ids)).all()) {
            lists.put(list.get(AccessListModel.ID), list);
        }
        // The order of AccessRuleModel.findForAccessList, per list: the gate reads a list's rules in it.
        for (Row rule : Models.get(AccessRuleModel.class).find().where(AccessRuleModel.ACCESS_LIST_ID.in(ids))
                .orderBy(AccessRuleModel.SORT, SortOrder.ASC).orderBy(AccessRuleModel.ID, SortOrder.ASC).all()) {
            rules.computeIfAbsent(rule.get(AccessRuleModel.ACCESS_LIST_ID), ignored -> new ArrayList<>()).add(rule);
        }
        return new AccessListRules(lists, rules);
    }

    /** @return the lists these protected paths follow, with their rules */
    static @NonNull AccessListRules ofPaths(@NonNull Collection<Row> paths) {
        List<Integer> ids = new ArrayList<>();
        for (Row path : paths) {
            ids.add(path.get(ProtectedPathModel.ACCESS_LIST_ID));
        }
        return of(ids);
    }

    /** @return the list's rules in their stored order, none for a list this read does not hold */
    @NonNull List<Row> rulesOf(@Nullable Integer listId) {
        List<Row> held = listId == null ? null : this.rules.get(listId);
        return held == null ? List.of() : held;
    }

    /** @return whether the list lets every visitor through, as the gate would compile it */
    boolean admitsEveryone(int listId) {
        Row list = this.lists.get(listId);
        return AccessRuleTree.admitsEveryone(list != null ? list.get(AccessListModel.SATISFY) : null,
            rulesOf(listId));
    }

    /** @return whether this protected path's list lets every visitor through */
    boolean isOpen(@NonNull Row path) {
        Object listId = path.get(ProtectedPathModel.ACCESS_LIST_ID);
        return listId instanceof Integer id && admitsEveryone(id);
    }

    /** @return the list's name, "#id" for one this read does not hold */
    @NonNull String nameOf(@Nullable Integer listId) {
        Row list = listId == null ? null : this.lists.get(listId);
        return list == null ? "#" + listId : Objects.toString(list.get(AccessListModel.NAME), "#" + listId);
    }
}
