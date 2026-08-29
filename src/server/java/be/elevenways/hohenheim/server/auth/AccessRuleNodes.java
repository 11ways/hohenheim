package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * THE birth of one node in an access list's rule tree, shared by the Rules tab's add form
 * and the automation API.
 *
 * AIDEV-NOTE: a node is born EMPTY and, unless it is a group, SWITCHED OFF -- a leaf is
 * enforced per request, so a rule that counted the moment it appeared (before its network
 * or its credential was typed) would refuse live traffic between two writes. A group
 * carries nothing that can be half-typed, so it starts on. Whatever configures the node
 * afterwards must go through {@code AccessRuleResource}'s form pipeline: that is where the
 * basic-auth password is argon2-hashed, and a raw model save would store it in plaintext.
 */
public final class AccessRuleNodes {

    private AccessRuleNodes() {
    }

    /**
     * Append one node to a list's tree, at the end of its parent's sibling run.
     *
     * @param parentId the enclosing group, or null for a direct child of the implicit root
     * @param origin the accountability origin recorded for the creation
     * @return the saved row
     */
    public static @NonNull Row add(int listId, @Nullable Integer parentId,
                                   @NonNull String type, @NonNull String origin) {
        AccessRuleModel model = Models.get(AccessRuleModel.class);
        Row rule = model.createEmptyRow();
        rule.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        rule.set(AccessRuleModel.PARENT_ID, parentId);
        rule.set(AccessRuleModel.TYPE, type);
        rule.set(AccessRuleModel.SORT, model.findChildren(listId, parentId).size());
        rule.set(AccessRuleModel.ENABLED, AccessRuleModel.TYPE_GROUP.equals(type));
        model.save(rule);
        ActivityLog.record(model, rule.get(AccessRuleModel.ID), "created", origin);
        return rule;
    }

    /**
     * @return the submitted parent, or null (the list's implicit root) when it is absent or
     *         names a row that is not a GROUP of THIS list -- a rule may not be parented
     *         onto another list's tree or onto a leaf
     */
    public static @Nullable Integer parentIn(@Nullable String submitted, int listId) {
        if (submitted == null || submitted.isBlank()) {
            return null;
        }
        int parentId;
        try {
            parentId = Integer.parseInt(submitted.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
        List<Row> candidates = Models.get(AccessRuleModel.class).find()
            .where(AccessRuleModel.ID.eq(parentId))
            .and(AccessRuleModel.ACCESS_LIST_ID.eq(listId))
            .and(AccessRuleModel.TYPE.eq(AccessRuleModel.TYPE_GROUP))
            .all();
        return candidates.isEmpty() ? null : parentId;
    }
}
