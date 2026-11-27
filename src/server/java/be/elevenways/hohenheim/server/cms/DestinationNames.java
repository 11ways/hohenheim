package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.traffic.AppDestinations;
import be.elevenways.hohenheim.server.traffic.DestinationKind;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * THE naming of an app's destinations for a viewer: a destination that is a record of this server (another app, a
 * database, a stream site) by that record's name where the viewer may view it, else by its kind's own words and its
 * address.
 *
 * AIDEV-NOTE: the one read the Network tab's card, app_network_read, traffic_read's apps_sending table and the
 * Activity tab's "Apps sending" picture all name destinations through, so no two of them say one destination two
 * ways. A destination on the internet is named by its address alone: Hohenheim sits in no app's name lookups (its
 * DNS server answers its own zones and refuses recursion; an app resolves through its container runtime's resolver
 * and the host's own), so the counters see a lookup only as a destination on port 53, with no name, and nothing here
 * ever asks a reverse lookup. One query per model named, whatever the number of destinations.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class DestinationNames {

    /** Who may be shown a record's name. */
    @FunctionalInterface
    public interface Viewer {

        /** Nobody: every record by its kind's words. */
        Viewer NOBODY = (model, id) -> false;

        boolean mayView(@NonNull Identifier model, int id);

        /** @return the caller's view: a record's name where the caller may view the record; nobody for no caller */
        static @NonNull Viewer of(@Nullable AccessContext caller) {
            return caller == null ? NOBODY
                : (model, id) -> HohenheimAccess.reachesRecord(caller, model, id, HohenheimCapabilities.VIEW);
        }
    }

    private DestinationNames() {
    }

    /** @return the name of every record the destinations name that the viewer may view, by {@link #keyOf} */
    public static @NonNull Map<String, String> of(@NonNull Collection<AppDestinations.Destination> wheres,
                                                  @NonNull Viewer viewer) {
        Map<String, String> names = new HashMap<>();
        if (viewer == Viewer.NOBODY) {
            return names;
        }
        Map<Identifier, List<Integer>> wanted = new HashMap<>();
        for (AppDestinations.Destination where : wheres) {
            Identifier model = modelOf(where.kind());
            if (model != null && where.ref() != null) {
                wanted.computeIfAbsent(model, ignored -> new ArrayList<>()).add(where.ref());
            }
        }
        for (Map.Entry<Identifier, List<Integer>> asked : wanted.entrySet()) {
            Identifier model = asked.getKey();
            List<Integer> ids = asked.getValue();
            List<Row> rows;
            Function<Row, Integer> idOf;
            Function<Row, String> nameOf;
            if (model.equals(InstanceModel.MODEL_ID)) {
                rows = Models.get(InstanceModel.class).find().where(InstanceModel.ID.in(ids)).all();
                idOf = row -> row.get(InstanceModel.ID);
                nameOf = row -> row.get(InstanceModel.NAME);
            } else if (model.equals(DatabaseModel.MODEL_ID)) {
                rows = Models.get(DatabaseModel.class).find().where(DatabaseModel.ID.in(ids)).all();
                idOf = row -> row.get(DatabaseModel.ID);
                nameOf = row -> row.get(DatabaseModel.NAME);
            } else {
                rows = Models.get(SiteModel.class).find().where(SiteModel.ID.in(ids)).all();
                idOf = row -> row.get(SiteModel.ID);
                nameOf = row -> row.get(SiteModel.NAME);
            }
            for (Row row : rows) {
                Integer id = idOf.apply(row);
                String name = nameOf.apply(row);
                if (id != null && name != null && !name.isBlank() && viewer.mayView(model, id)) {
                    names.put(model + "#" + id, name);
                }
            }
        }
        return names;
    }

    /** @return the name {@link #of} found for the destination, null when it names no record the viewer may view */
    public static @Nullable String nameOf(@NonNull Map<String, String> names,
                                          AppDestinations.@NonNull Destination where) {
        return where.ref() == null ? null : names.get(keyOf(where));
    }

    /** @return what the destination is called: the record's name when given, else its kind's words and address */
    public static @NonNull Microcopy shown(AppDestinations.@NonNull Destination where, @Nullable String name) {
        return name != null ? Microcopy.literal(name) : where.kind().unnamed().withArg("address", where.address());
    }

    /** @return the destination's address and port as one reads them, an IPv6 address in brackets */
    public static @NonNull String addressOf(AppDestinations.@NonNull Destination where) {
        return (where.address().indexOf(':') >= 0 ? "[" + where.address() + "]" : where.address()) + ":"
            + where.port();
    }

    /** @return the model a destination's reference names, null for a kind that names no record */
    private static @Nullable Identifier modelOf(@NonNull DestinationKind kind) {
        return switch (kind) {
            case APP, OWN -> InstanceModel.MODEL_ID;
            case DATABASE -> DatabaseModel.MODEL_ID;
            case STREAM -> SiteModel.MODEL_ID;
            case INTERNET, PROXY, SERVER -> null;
        };
    }

    private static @NonNull String keyOf(AppDestinations.@NonNull Destination where) {
        return modelOf(where.kind()) + "#" + where.ref();
    }
}
