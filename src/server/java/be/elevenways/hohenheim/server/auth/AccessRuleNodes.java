package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE birth of one node in an access list's rule tree, shared by the Rules tab's add operations and the automation API,
 * THE hashing of a basic-auth password on its way into a rule, shared with the rule form's save, and THE refusal of a
 * generic writer touching a rule only Hohenheim writes.
 *
 * AIDEV-NOTE: the automation API's node is born EMPTY and, unless it is a group, SWITCHED OFF -- a leaf is
 * enforced per request, so a rule that counted the moment it appeared (before its network or its credential was
 * typed) would refuse live traffic between two writes; the API then configures it through {@code AccessRuleParts}'
 * form pipeline. The Rules tab's add operations instead give the node its data and switch at birth, in ONE save, so
 * the model's completeness hook refuses a switched-on node that is not filled in before anything is written.
 * Either way the password passes {@link #withHashedPassword} first: a raw model save would store it in plaintext.
 */
public final class AccessRuleNodes {

    private AccessRuleNodes() {
    }

    /**
     * Append one empty node to a list's tree, switched off unless it is a group; its save is the recorded create.
     *
     * @param parentId the enclosing group, or null for a direct child of the implicit root
     * @return the saved row
     */
    public static @NonNull Row add(int listId, @Nullable Integer parentId,
                                   @NonNull String type) {
        return born(listId, parentId, type, null, AccessRuleModel.TYPE_GROUP.equals(type));
    }

    /**
     * Append one node, already filled in, to a list's tree, at the end of its parent's sibling run; its one save is
     * the recorded create.
     *
     * @param parentId the enclosing group, or null for a direct child of the implicit root
     * @param data     the type's own fields; a basic-auth password in it is hashed here
     * @return the saved row
     * @throws Violations when the data cannot answer a request, or a node that is switched on is not filled in;
     *                    nothing is written then
     */
    public static @NonNull Row add(int listId, @Nullable Integer parentId, @NonNull String type,
                                   @NonNull Map<String, Object> data, boolean enabled) {
        return born(listId, parentId, type, data, enabled);
    }

    /** @param data the type's own fields, null for a node born without any */
    private static @NonNull Row born(int listId, @Nullable Integer parentId, @NonNull String type,
                                     @Nullable Map<String, Object> data, boolean enabled) {
        refuseMadeByHohenheim(null, type, data == null ? Map.of() : data);
        AccessRuleModel model = Models.get(AccessRuleModel.class);
        Row rule = model.createEmptyRow();
        rule.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        rule.set(AccessRuleModel.PARENT_ID, parentId);
        rule.set(AccessRuleModel.TYPE, type);
        if (data != null) {
            rule.set(AccessRuleModel.DATA, withHashedPassword(data));
        }
        // No position: the rule tree appends an unplaced rule after its siblings (AccessRuleModel.TREE).
        rule.set(AccessRuleModel.ENABLED, enabled);
        model.save(rule);
        return rule;
    }

    /**
     * The password is typed in plaintext and stored as an argon2 hash, through the ONE credential home the request-time
     * gate verifies with; an operation hands it over sealed, which is unsealed only to be hashed. A blank or
     * already-hashed value is left as it is (the form's keep-blank restores the stored hash).
     *
     * @return the data with its password hashed, a copy when anything changed
     */
    public static @NonNull Map<String, Object> withHashedPassword(@NonNull Map<String, Object> data) {
        String key = AccessRuleModel.BASIC_AUTH_PASSWORD.getName();
        Object password = data.get(key);
        String plain = password instanceof Secret sealed ? sealed.reveal()
            : password instanceof String typed ? typed : null;
        if (plain == null || (plain.equals(password) && (plain.isBlank() || BasicCredentials.isHashed(plain)))) {
            return data;
        }
        Map<String, Object> hashed = new LinkedHashMap<>(data);
        hashed.put(key, BasicCredentials.hashIfNeeded(plain));
        return hashed;
    }

    /**
     * Refuses a generic rule writer (a rule form, an add, access_rule_set, the automation API) making, retyping or
     * rewriting a rule only Hohenheim writes (AccessRuleModel.madeByHohenheim); switching one on or off stays open.
     *
     * @param stored the rule as stored, null for a new one
     * @param type   the type it is written with
     * @param data   the data it is written with
     * @throws Violations naming {@code type} when the write would make, retype or rewrite such a rule
     */
    public static void refuseMadeByHohenheim(@Nullable Row stored, @NonNull String type,
                                             @NonNull Map<String, Object> data) {
        String storedType = stored == null ? null : stored.get(AccessRuleModel.TYPE);
        boolean storedGuarded = stored != null && AccessRuleModel.madeByHohenheim(storedType);
        // A new type outside the vocabulary is the model's own refusal to answer, unless it retypes a guarded rule.
        boolean writtenGuarded = AccessRuleModel.TYPE.isValidValue(type) && AccessRuleModel.madeByHohenheim(type);
        if (!storedGuarded && !writtenGuarded) return;
        if (stored != null && type.equals(storedType) && data.equals(AccessRuleModel.dataOf(stored))) return;
        throw Violations.ofField(AccessRuleModel.TYPE.getName(), type,
            HohenheimMicrocopy.VIOLATIONS.of("access_rule_made_by_hohenheim"));
    }

    /**
     * @return the submitted parent, or null (the list's implicit root) when it is absent or
     *         names a row that is not a GROUP of THIS list -- a rule may not be parented
     *         onto another list's tree or onto a leaf
     */
    public static @Nullable Integer parentIn(@Nullable String submitted, int listId) {
        Integer parentId = RawValues.parsedInt(submitted);
        if (parentId == null) {
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
