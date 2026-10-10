package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.proxy.AccessRuleTree;
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * THE protected-path invariant on the model write pipeline: the path stored canonically (the dispatcher's own
 * spelling), a usable prefix, a list that actually protects, and one row per (site, path), for every writer, not just
 * the CMS form, for the reason {@link SiteDomainRouteInvariant#installRouteInvariant} spells out.
 *
 * AIDEV-NOTE: moved out of the legacy ProtectedPathResource unchanged when the protected paths moved onto parts
 * ({@link ProtectedPathParts}): a schema hook is not a resource part. Installed by HohenheimWriteHooks.
 *
 * AIDEV-NOTE: "a list that actually protects" is {@link AccessRuleTree#admitsEveryone}, the gate's own reading of the
 * rows. It holds from BOTH sides: a path may not be pointed at an open list, and a rule or list write may not open a
 * list that protects a path. A list that was already open before the write stays writable (decision J2 of the admin
 * redesign plan: existing open paths keep serving and are flagged, {@link #isOpen}, never broken by an upgrade).
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class ProtectedPathInvariant {

    private ProtectedPathInvariant() {
    }

    private static volatile boolean protectionInvariantInstalled;

    /** @return whether this protected path's list lets every visitor through, as the gate would compile it */
    public static boolean isOpen(@NonNull Row protectedPath) {
        Object listId = protectedPath.get(ProtectedPathModel.ACCESS_LIST_ID);
        return listId instanceof Integer id && admitsEveryone(id, null, rulesOf(id));
    }

    /** Installs the invariant once per JVM. */
    public static synchronized void install() {
        if (protectionInvariantInstalled) {
            return;
        }
        protectionInvariantInstalled = true;
        AccessRuleModel.SCHEMA.addBeforeValidateHook(context -> {
            Row rule = context.getRow();
            if (rule != null) {
                refuseOpeningRuleSave(rule);
            }
        });
        AccessRuleModel.SCHEMA.addBeforeRemoveHook(ProtectedPathInvariant::refuseOpeningRuleRemoval);
        AccessListModel.SCHEMA.addBeforeValidateHook(context -> {
            Row list = context.getRow();
            if (list != null && list.has(AccessListModel.SATISFY.getName()) && list.get(AccessListModel.ID) != null) {
                int id = list.get(AccessListModel.ID);
                List<Row> rules = rulesOf(id);
                refuseOpening(id, admitsEveryone(id, null, rules),
                    admitsEveryone(id, list.get(AccessListModel.SATISFY), rules));
            }
        });
        ProtectedPathModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            if (row.has(ProtectedPathModel.PATH.getName())) {
                Object raw = row.get(ProtectedPathModel.PATH);
                String canonical = SiteDispatcher.normalizeRoutePath(
                    raw != null ? String.valueOf(raw) : null);
                // Canonical null means "/" or blank: guarding everything is the site's own
                // access list, and a stored null would silently guard NOTHING here.
                if (canonical == null) {
                    throw Violations.ofField(ProtectedPathModel.PATH.getName(), raw,
                        HohenheimMicrocopy.VIOLATIONS.of("protected_path_required"));
                }
                if (!Objects.equals(raw, canonical)) {
                    row.set(ProtectedPathModel.PATH, canonical);
                }
            }
            refuseIncompleteOrDuplicate(row);
        });
    }

    /** Site and list required, and the (site, path) pair unclaimed. */
    private static void refuseIncompleteOrDuplicate(@NonNull Row row) {
        Model model = Models.get(ProtectedPathModel.class);
        Row stored = StoredRows.of(model, row);

        Object siteIdValue = row.afterWrite(ProtectedPathModel.SITE_ID, stored);
        if (!(siteIdValue instanceof Integer siteId)) {
            throw Violations.ofField(ProtectedPathModel.SITE_ID.getName(), siteIdValue,
                HohenheimMicrocopy.VIOLATIONS.of("site_required"));
        }
        Object listId = row.afterWrite(ProtectedPathModel.ACCESS_LIST_ID, stored);
        if (!(listId instanceof Integer list)) {
            throw Violations.ofField(ProtectedPathModel.ACCESS_LIST_ID.getName(), listId,
                HohenheimMicrocopy.VIOLATIONS.of("access_list_required"));
        }
        // Only a NEW pointer is judged: a stored path already on an open list keeps serving (J2).
        if (row.changes(ProtectedPathModel.ACCESS_LIST_ID, stored) && admitsEveryone(list, null, rulesOf(list))) {
            throw Violations.ofField(ProtectedPathModel.ACCESS_LIST_ID.getName(), listId,
                HohenheimMicrocopy.VIOLATIONS.of("access_list_admits_everyone").withArg("list", listName(list)));
        }
        Object path = row.afterWrite(ProtectedPathModel.PATH, stored);
        if (path == null || String.valueOf(path).isBlank()) {
            throw Violations.ofField(ProtectedPathModel.PATH.getName(), path,
                HohenheimMicrocopy.VIOLATIONS.of("protected_path_required"));
        }
        Object ownId = stored != null ? stored.get(ProtectedPathModel.ID) : null;
        for (Row candidate : model.find()
                .where(ProtectedPathModel.SITE_ID.eq(siteId))
                .and(ProtectedPathModel.PATH.eq(String.valueOf(path))).all()) {
            if (!Objects.equals(candidate.get(ProtectedPathModel.ID), ownId)) {
                throw Violations.ofField(ProtectedPathModel.PATH.getName(), path,
                    HohenheimMicrocopy.VIOLATIONS.of("protected_path_taken"));
            }
        }
    }

    /** A rule save (the form, the on/off toggle, a new rule) may not leave a protecting list open. */
    private static void refuseOpeningRuleSave(@NonNull Row rule) {
        Model rules = Models.get(AccessRuleModel.class);
        Object id = rule.get(AccessRuleModel.ID);
        Row stored = StoredRows.of(rules, rule);
        if (!(rule.afterWrite(AccessRuleModel.ACCESS_LIST_ID, stored) instanceof Integer listId)) {
            return;
        }
        Row written = stored != null ? new Row(stored) : new Row();
        for (String field : List.of(AccessRuleModel.ACCESS_LIST_ID.getName(), AccessRuleModel.PARENT_ID.getName(),
                AccessRuleModel.TYPE.getName(), AccessRuleModel.DATA.getName(), AccessRuleModel.ENABLED.getName())) {
            if (rule.has(field)) {
                written.set(field, rule.get(field));
            }
        }
        if (!written.has(AccessRuleModel.ENABLED.getName())) {
            written.set(AccessRuleModel.ENABLED, AccessRuleModel.ENABLED.getDefaultValue());
        }
        List<Row> before = rulesOf(listId);
        List<Row> after = new ArrayList<>();
        for (Row candidate : before) {
            if (id == null || !Objects.equals(candidate.get(AccessRuleModel.ID), id)) {
                after.add(candidate);
            }
        }
        after.add(written);
        refuseOpening(listId, admitsEveryone(listId, null, before), admitsEveryone(listId, null, after));
    }

    /** A rule delete takes its subtree, and may not leave a protecting list open either. */
    private static void refuseOpeningRuleRemoval(@NonNull RemoveFromDatasource context) {
        Set<Object> doomed = new HashSet<>();
        Set<Integer> lists = new LinkedHashSet<>();
        for (Row rule : context.doomedRows()) {
            doomed.add(rule.get(AccessRuleModel.ID));
            if (rule.get(AccessRuleModel.ACCESS_LIST_ID) instanceof Integer listId) {
                lists.add(listId);
            }
        }
        for (Integer listId : lists) {
            List<Row> before = rulesOf(listId);
            List<Row> after = new ArrayList<>(before);
            // A removed group takes its descendants with it, as the tree's delete policy does.
            boolean removed = true;
            while (removed) {
                removed = after.removeIf(rule -> doomed.contains(rule.get(AccessRuleModel.ID))
                    || doomed.contains(rule.get(AccessRuleModel.PARENT_ID)) && doomed.add(rule.get(AccessRuleModel.ID)));
            }
            refuseOpening(listId, admitsEveryone(listId, null, before), admitsEveryone(listId, null, after));
        }
    }

    /**
     * @throws Violations {@code access_list_would_open}, naming the first path the list protects, when a write turns
     *                    a protecting list open; a list that was already open is left to its owner (J2)
     */
    private static void refuseOpening(int listId, boolean openBefore, boolean openAfter) {
        if (openBefore || !openAfter) {
            return;
        }
        Row path = Models.get(ProtectedPathModel.class).find()
            .where(ProtectedPathModel.ACCESS_LIST_ID.eq(listId)).first();
        if (path == null) {
            return;
        }
        throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("access_list_would_open")
            .withArg("list", listName(listId))
            .withArg("path", String.valueOf((Object) path.get(ProtectedPathModel.PATH))));
    }

    /**
     * @param satisfy the list's root mode after the write, null to read the stored one
     * @return whether these rows, under that mode, let every visitor through
     */
    private static boolean admitsEveryone(int listId, @Nullable String satisfy, @NonNull List<Row> rules) {
        String mode = satisfy;
        if (mode == null) {
            Row list = Models.get(AccessListModel.class).findById(listId);
            mode = list != null ? list.get(AccessListModel.SATISFY) : null;
        }
        return AccessRuleTree.admitsEveryone(mode, rules);
    }

    private static @NonNull List<Row> rulesOf(int listId) {
        return Models.get(AccessRuleModel.class).findForAccessList(listId);
    }

    private static @NonNull String listName(int listId) {
        Row list = Models.get(AccessListModel.class).findById(listId);
        return list != null ? String.valueOf((Object) list.get(AccessListModel.NAME)) : "#" + listId;
    }
}
