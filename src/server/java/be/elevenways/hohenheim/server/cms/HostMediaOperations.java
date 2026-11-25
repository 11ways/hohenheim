package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.server.instance.InstallMedia;
import be.elevenways.hohenheim.server.instance.InstallMediaFetches;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * An Incus host's install media fetched from a URL or deleted, as operations: the Install media tab's forms and the
 * assistant's tool run the same two steps.
 *
 * AIDEV-NOTE: every refusal of a fetch is decided on the calling thread (requireFetchable); the download then runs as
 * the background job the tab renders. The activity row of a fetch is the job's, written when the medium reads back.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class HostMediaOperations {

    /** The medium's name on the host. */
    public static final StringField NAME = StringField.builder("name")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("media_name"))
        .help(HohenheimMicrocopy.HELP.of("media_name")).required().build();

    /** Where a fetch downloads the ISO from. */
    public static final StringField URL = StringField.builder("url")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("media_url"))
        .help(HohenheimMicrocopy.HELP.of("media_url")).required().build();

    /**
     * @param name the medium's name
     * @param url  where to fetch it from
     */
    public record Fetch(@Nullable String name, @Nullable String url) {}

    /** @param name the medium's name */
    public record Removal(@Nullable String name) {}

    private static final OperationGate MEDIA = OperationGate.permission(HohenheimSources.MEDIA_MANAGE);

    private static final InstallMedia INSTALL_MEDIA = new InstallMedia();

    /** Starts fetching an ISO from a public URL onto an Incus host. */
    public static final Operation<Row, Fetch, Microcopy> FETCH = Operation.declare(HohenheimIds.id("fetch_host_media"))
        .happened(OperationSentences.of("fetch_host_media"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("fetch_host_media"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("fetch_host_media_description"))
        .one(SubjectType.record(HostModel.REF)).gate(MEDIA)
        .input(OperationInput.of(FormSpec.builder().add(NAME).add(URL).build(), Fetch.class,
            values -> new Fetch(values.get(NAME), values.get(URL))))
        .result(Microcopy.class)
        .facts(OperationFact.REACHES_OUTSIDE)
        .command(CmsCommands.EXTERNAL)
        .register();

    /** Deletes an install medium no app has attached from an Incus host. */
    public static final Operation<Row, Removal, Microcopy> DELETE = Operation.declare(HohenheimIds.id(
            "delete_host_media"))
        .happened(OperationSentences.of("delete_host_media"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("delete_host_media"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("delete_host_media_description"))
        .one(SubjectType.record(HostModel.REF)).gate(MEDIA)
        .input(OperationInput.of(FormSpec.builder().add(NAME).build(), Removal.class,
            values -> new Removal(values.get(NAME))))
        .result(Microcopy.class)
        .facts(OperationFact.DESTRUCTIVE, OperationFact.REACHES_OUTSIDE)
        .command(CmsCommands.EXTERNAL)
        .register();

    static {
        OperationHandlers.attach(FETCH).applies(HostModel::isIncus).handle(call -> {
            Fetch medium = Objects.requireNonNull(call.input());
            return fetch(call.subject(), Objects.requireNonNull(medium.name()).trim(),
                Objects.requireNonNull(medium.url()).trim());
        });
        OperationHandlers.attach(DELETE).applies(HostModel::isIncus).handle(call -> delete(call.subject(),
            Objects.requireNonNull(Objects.requireNonNull(call.input()).name()).trim()));
    }

    private HostMediaOperations() {
    }

    /**
     * Refuses what cannot be fetched, then starts the download in the background.
     *
     * @return the words saying the fetch started
     */
    public static @NonNull Microcopy fetch(@NonNull Row host, @NonNull String name, @NonNull String url) {
        INSTALL_MEDIA.requireFetchable(host, name, url);
        InstallMediaFetches.start(host, name, progress -> INSTALL_MEDIA.transfer(host, name, url, progress,
            progress::importing));
        return HohenheimMicrocopy.SERVER_MEDIA.of("media_fetch_started").withArg("name", name);
    }

    /**
     * Deletes the medium, records it on the host and redraws every open Install media tab.
     *
     * @return the words saying it was deleted
     */
    public static @NonNull Microcopy delete(@NonNull Row host, @NonNull String name) {
        INSTALL_MEDIA.delete(host, name);
        Integer hostId = host.get(HostModel.ID);
        History.event(Models.get(HostModel.class), hostId, Change.fact(HohenheimActivityAction.MEDIA_DELETED,
            Map.of(HohenheimActivityAction.NAME_KEY, name)));
        HostMediaTab.poolChanged(hostId);
        return HohenheimMicrocopy.SERVER_MEDIA.of("media_deleted").withArg("name", name);
    }
}
