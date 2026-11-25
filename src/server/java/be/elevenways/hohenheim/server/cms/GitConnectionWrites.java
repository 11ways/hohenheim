package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.source.GitProviderKind;
import be.elevenways.hohenheim.server.source.GitProviderKinds;
import be.elevenways.hohenheim.server.source.GithubProviderKind;
import be.elevenways.hohenheim.server.source.SshProviderKind;
import be.elevenways.hohenheim.source.GitProviderOperations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.CmsMicrocopy;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RowSave;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Plain;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.OperationVerb;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.FieldRedaction;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.history.ChangeOp;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.Availability;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.PatchBase;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.server.operation.RecordReadLines;
import be.elevenways.zenit.server.security.SshKeyPair;
import be.elevenways.zenit.server.security.SshPublicKey;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A git connection created or changed by its settings as an assistant names them, through the git connection panel
 * resource's own create and save, so its tenant freeze, its shared refusal, its ownership grant and its new SSH key
 * decide as they do for the panel's form.
 *
 * AIDEV-NOTE: an SSH connection created without a private key gets a new Ed25519 pair ({@link #generateKey}, the
 * panel resources' save hook), so the person adds its public key to the repository as a read-only deploy key; a
 * public key is no secret, so the answer, a read and the panel's form show it. A pasted key shows its public key only
 * when it is an OpenSSH file (SshKeyPair.publicOf).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class GitConnectionWrites {

    /** The argument naming how Hohenheim reaches the repositories, by the kind's own path ({@code ssh}). */
    public static final String KIND = "kind";

    /** The argument making a new key pair for an SSH connection. */
    public static final String REGENERATE_KEY = "regenerate_key";

    /** The line a read and an answer name the public key by. */
    public static final String PUBLIC_KEY = "public_key";

    private static final String GITHUB_APP = "github_app";
    private static final String GITHUB_INSTALLATION = "github_installation";
    private static final String GITHUB_APP_KEY = "github_app_key";

    /** The connection's own columns, by the argument naming each; the settings of its kind are {@link #SETTINGS}. */
    private static final Map<String, Field<?, ?>> COLUMNS = orderedColumns();

    /** The settings of a kind, by the argument naming each, written into the connection's settings map. */
    private static final Map<String, Field<?, ?>> SETTINGS = Map.of(GITHUB_APP, GithubProviderKind.APP_ID,
        GITHUB_INSTALLATION, GithubProviderKind.APP_INSTALLATION_ID, SshProviderKind.REPOSITORY.getName(),
        SshProviderKind.REPOSITORY);

    /** The arguments a kind that clones with a token reads, and those a GitHub connection alone reads. */
    private static final List<String> TOKEN_ARGUMENTS = List.of(GitProviderModel.BASE_URL.getName(),
        GitProviderModel.ACCESS_TOKEN.getName());
    private static final List<String> GITHUB_ARGUMENTS = List.of(GITHUB_APP, GITHUB_INSTALLATION, GITHUB_APP_KEY);

    /** The arguments a kind that clones with a key reads. */
    private static final List<String> KEY_ARGUMENTS = List.of(SshProviderKind.REPOSITORY.getName(),
        GitProviderModel.SSH_PRIVATE_KEY.getName(), REGENERATE_KEY);

    /** A create has no subject to key its command by: the request id alone answers the first result. */
    private static final OperationCommand CREATES = OperationCommand.perVerb().onDatasource("default");

    /** Creates a git connection. */
    public static final Operation<Void, OperationInput.Values, Integer> CREATE =
        Operation.declare(HohenheimIds.id("create_git_connection"))
            .label(HohenheimMicrocopy.HOHENHEIM.of("create_git_connection"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("create_git_connection_description"))
            .happened(ChangeOp.CREATE.happened())
            .noSubject()
            // The connection resource's create decides who may, as for the panel's form.
            .gate(OperationGate.open())
            .input(OperationInput.derived(form(true)))
            .result(Integer.class)
            .createdRecord(GitProviderModel.MODEL_ID, id -> id)
            .facts(OperationFact.IDEMPOTENT)
            .command(CREATES)
            .register();

    /** Changes what the call sends of a git connection; every other setting stays as it is. */
    public static final Operation<Row, OperationInput.Values, String> CHANGE =
        Operation.declare(HohenheimIds.id("change_git_connection"))
            .label(HohenheimMicrocopy.HOHENHEIM.of("change_git_connection"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("change_git_connection_description"))
            .happened(ChangeOp.UPDATE.happened())
            .one(GitProviderOperations.PROVIDER)
            .gate(OperationGate.open())
            .input(OperationInput.derived(form(false)))
            .result(String.class)
            .patchable()
            .facts(OperationFact.IDEMPOTENT)
            .command(CmsCommands.TRANSACTIONAL)
            .register();

    /**
     * Removes a git connection, the panel's own Delete of one (its form lane's removal, the tenant freeze judging who
     * may); refused by name while an app builds from it.
     */
    public static final Operation<Row, Void, Integer> DELETE =
        Operation.declare(HohenheimIds.id("delete_git_connection"))
            .label(CmsMicrocopy.of("delete"))
            .description(HohenheimMicrocopy.HOHENHEIM.of("delete_git_connection_description"))
            .happened(ChangeOp.DELETE.happened())
            .icon(Icon.TRASH)
            .one(GitProviderOperations.PROVIDER)
            .verb(OperationVerb.DELETE)
            .gate(OperationGate.open().subjectCapability(HohenheimCapabilities.MANAGE))
            .result(Integer.class)
            .facts(OperationFact.DESTRUCTIVE)
            .command(CmsCommands.TRANSACTIONAL)
            .register();

    static {
        OperationHandlers.attach(CREATE).handle(call -> created(Objects.requireNonNull(call.input()),
            Objects.requireNonNull(call.access(), "a create has its caller")));
        OperationHandlers.attach(CHANGE)
            .patchBase(PatchBase.ofValues(CHANGE, (connection, access) -> stored(Objects.requireNonNull(connection))))
            .handle(call -> changed(call.subject(), Objects.requireNonNull(call.input()), call.patched(),
                Objects.requireNonNull(call.access(), "a change has its caller")));
        OperationHandlers.attach(DELETE)
            .availability(Availability.batch((connections, access) -> DeleteImpact.gitConnectionsInUse(connections)))
            .handle(call -> {
                Models.get(GitProviderModel.class).delete(call.subject());
                return 1;
            });
        RecordReadLines.register(GitProviderModel.MODEL_ID, GitConnectionWrites.class, (keys, access) -> {
            Map<String, List<RecordReadLines.Line>> lines = new HashMap<>();
            for (Row connection : Models.get(GitProviderModel.class).find()
                    .where(GitProviderModel.ID.in(keys.stream().map(Integer::valueOf).toList())).all()) {
                SshPublicKey key = publicKeyOf(connection);
                if (key != null) {
                    lines.put(String.valueOf((Object) connection.get(GitProviderModel.ID)),
                        List.of(new RecordReadLines.Line(PUBLIC_KEY, key.fileLine())));
                }
            }
            return lines;
        });
    }

    private GitConnectionWrites() {
    }

    private static @NonNull Map<String, Field<?, ?>> orderedColumns() {
        Map<String, Field<?, ?>> columns = new LinkedHashMap<>();
        columns.put(GitProviderModel.NAME.getName(), GitProviderModel.NAME);
        columns.put(GitProviderModel.BASE_URL.getName(), GitProviderModel.BASE_URL);
        columns.put(GitProviderModel.ACCESS_TOKEN.getName(), GitProviderModel.ACCESS_TOKEN);
        columns.put(GITHUB_APP_KEY, GitProviderModel.APP_PRIVATE_KEY_PEM);
        columns.put(GitProviderModel.SSH_PRIVATE_KEY.getName(), GitProviderModel.SSH_PRIVATE_KEY);
        columns.put(GitProviderModel.SHARED.getName(), GitProviderModel.SHARED);
        return columns;
    }

    /** @param creating whether the form is the create's, which alone lacks {@link #REGENERATE_KEY} */
    private static @NonNull FormSpec form(boolean creating) {
        FormSpec.Builder form = FormSpec.builder();
        form.add(KindSettingsWrites.entryOf(GitProviderModel.NAME));
        form.add(Select.of(StringField.builder(KIND).help(HohenheimMicrocopy.HELP.of("git_connection_kind")).build())
            .options(OptionSource.supplied(GitConnectionWrites::kindOptions)).build());
        for (Map.Entry<String, Field<?, ?>> column : COLUMNS.entrySet()) {
            if (column.getValue() == GitProviderModel.NAME) continue;
            form.add(KindSettingsWrites.entryOf(column.getValue()).named(column.getKey()));
        }
        form.add(KindSettingsWrites.entryOf(GithubProviderKind.APP_ID).named(GITHUB_APP));
        form.add(KindSettingsWrites.entryOf(GithubProviderKind.APP_INSTALLATION_ID).named(GITHUB_INSTALLATION));
        // The SSH kind's repository is required by its own settings form; a call of another kind never sends it.
        form.add(Plain.of(StringField.builder(SshProviderKind.REPOSITORY.getName())
            .help(HohenheimMicrocopy.HELP.of("git_connection_repository")).build()));
        if (!creating) {
            form.add(Plain.of(BooleanField.builder(REGENERATE_KEY).defaultValue(false)
                .help(HohenheimMicrocopy.HELP.of("git_connection_regenerate_key")).build()));
        }
        return form.build();
    }

    /** @return the declared kinds, each by its own path ({@code github}, {@code ssh}) */
    private static @NonNull List<FieldOption<String>> kindOptions() {
        List<FieldOption<String>> options = new ArrayList<>();
        GitProviderKinds.declaredKinds().stream().sorted(Comparator.comparing(Identifier::getPath)).forEach(id ->
            options.add(FieldOption.of(id.getPath(), Objects.requireNonNull(GitProviderKinds.getHandler(
                id.toString())).getLabel())));
        return options;
    }

    /** @return the stored kind a kind's path names, null for none */
    private static @Nullable Identifier kindOf(@Nullable Object path) {
        for (Identifier id : GitProviderKinds.declaredKinds()) {
            if (id.getPath().equals(path)) return id;
        }
        return null;
    }

    /**
     * Creates the connection through the caller's git connection resource.
     *
     * @throws Violations for an argument its kind does not read
     */
    private static @NonNull Integer created(OperationInput.@NonNull Values input, @NonNull AccessContext caller) {
        Identifier kind = kindOf(input.get(KIND));
        // A create is judged by what it gives: an argument left out, blank or false asks nothing of the kind.
        Set<String> sent = new LinkedHashSet<>();
        input.coerced().forEach((name, value) -> {
            if (value != null && !Boolean.FALSE.equals(value) && !String.valueOf(value).isBlank()) sent.add(name);
        });
        refuseUnread(kind, sent);
        refuseShared(sent, input, caller);
        Map<String, Object> raw = new LinkedHashMap<>();
        if (kind != null) raw.put(GitProviderModel.KIND.getName(), kind.toString());
        COLUMNS.forEach((argument, column) -> {
            Object value = revealed(input.get(argument));
            if (value != null && !value.equals(column.getDefaultValue())) raw.put(column.getName(), value);
        });
        Map<String, Object> settings = new LinkedHashMap<>();
        SETTINGS.forEach((argument, field) -> {
            if (input.get(argument) != null) settings.put(field.getName(), input.get(argument));
        });
        raw.put(GitProviderModel.SETTINGS.getName(), settings);
        Panel panel = panelOf(caller);
        Object key = KindSettingsWrites.speaking(COLUMNS, () -> ResourceWrites.create(panel,
            connections(panel), raw, caller));
        return Integer.valueOf(String.valueOf(key));
    }

    /**
     * Writes only what the call sent over the connection, through the caller's git connection resource; a new key
     * pair replaces the private key when the call asks one.
     *
     * @return the code and the names of the settings written, and the public key a new pair has
     * @throws Violations for an argument the connection's kind does not read, or a key both given and asked made
     */
    private static @NonNull String changed(@NonNull Row connection, OperationInput.@NonNull Values input,
                                           @Nullable Set<String> patched, @NonNull AccessContext caller) {
        Set<String> sent = new LinkedHashSet<>(patched != null ? patched : input.coerced().keySet());
        boolean regenerate = Boolean.TRUE.equals(input.get(REGENERATE_KEY));
        if (!regenerate) sent.remove(REGENERATE_KEY);
        Identifier kind = sent.contains(KIND) ? kindOf(input.get(KIND))
            : Identifier.tryParse(String.valueOf((Object) connection.get(GitProviderModel.KIND)));
        refuseUnread(kind, sent);
        String privateKey = GitProviderModel.SSH_PRIVATE_KEY.getName();
        if (regenerate && sent.contains(privateKey)) {
            throw Violations.ofField(REGENERATE_KEY, true, HohenheimMicrocopy.VIOLATIONS.of("git_connection_key_twice"));
        }
        refuseShared(sent, input, caller);
        Map<String, Object> raw = new LinkedHashMap<>();
        if (sent.contains(KIND) && kind != null) raw.put(GitProviderModel.KIND.getName(), kind.toString());
        COLUMNS.forEach((argument, column) -> {
            if (sent.contains(argument)) raw.put(column.getName(), revealed(input.get(argument)));
        });
        SshKeyPair pair = regenerate ? SshKeyPair.generate(keyComment(connection.get(GitProviderModel.NAME))) : null;
        if (pair != null) raw.put(privateKey, pair.privateKey());
        // The settings map is written whole: the stored one, with what the call sent over it.
        Map<String, Object> settings = new LinkedHashMap<>(RawValues.map(connection.get(GitProviderModel.SETTINGS)));
        boolean settingsSent = sent.contains(KIND);
        for (Map.Entry<String, Field<?, ?>> setting : SETTINGS.entrySet()) {
            if (!sent.contains(setting.getKey())) continue;
            Object value = input.get(setting.getKey());
            if (value == null) settings.remove(setting.getValue().getName());
            else settings.put(setting.getValue().getName(), value);
            settingsSent = true;
        }
        if (settingsSent) raw.put(GitProviderModel.SETTINGS.getName(), settings);
        String code = RecordCodes.codeUnjudged(GitProviderModel.MODEL_ID,
            String.valueOf((Object) connection.get(GitProviderModel.ID)));
        if (raw.isEmpty()) return KindSettingsWrites.changedLine(code, "");
        Panel panel = panelOf(caller);
        KindSettingsWrites.speaking(COLUMNS, () -> ResourceWrites.update(panel, connections(panel),
            Objects.requireNonNull(connection.get(GitProviderModel.ID)), connection, raw, caller));
        String line = KindSettingsWrites.changedLine(code, String.join(", ", sent));
        return pair == null ? line : line + "\n" + PUBLIC_KEY + ": " + pair.publicKey().fileLine();
    }

    /** Refuses {@code shared} from anyone but an administrator, whose declaration alone offers a connection to all. */
    private static void refuseShared(@NonNull Set<String> sent, OperationInput.@NonNull Values input,
                                     @NonNull AccessContext caller) {
        String shared = GitProviderModel.SHARED.getName();
        if (sent.contains(shared) && !HohenheimAccess.isAdmin(caller)) {
            throw Violations.ofField(shared, input.get(shared),
                HohenheimMicrocopy.VIOLATIONS.of("tenant_field_frozen"));
        }
    }

    /** @return a secret argument's text, as the panel's form posts it; any other value as it is */
    private static @Nullable Object revealed(@Nullable Object value) {
        return value instanceof Secret secret ? secret.reveal() : value;
    }

    /**
     * Refuses an argument the connection's kind never reads, in one sentence naming the arguments and the kinds that
     * read them.
     *
     * @param kind the kind the connection is or becomes, null for one not named
     */
    private static void refuseUnread(@Nullable Identifier kind, @NonNull Set<String> sent) {
        GitProviderKind handler = kind == null ? null : GitProviderKinds.getHandler(kind.toString());
        if (handler == null) return;
        List<String> tokenKinds = new ArrayList<>();
        List<String> keyKinds = new ArrayList<>();
        GitProviderKinds.declaredKinds().stream().sorted(Comparator.comparing(Identifier::getPath)).forEach(id -> {
            boolean key = Objects.requireNonNull(GitProviderKinds.getHandler(id.toString())).clonesWithKey();
            (key ? keyKinds : tokenKinds).add(id.getPath());
        });
        refuseUnread(sent, TOKEN_ARGUMENTS, !handler.clonesWithKey(), tokenKinds);
        refuseUnread(sent, GITHUB_ARGUMENTS, GithubProviderKind.ID.equals(kind),
            List.of(GithubProviderKind.ID.getPath()));
        refuseUnread(sent, KEY_ARGUMENTS, handler.clonesWithKey(), keyKinds);
    }

    private static void refuseUnread(@NonNull Set<String> sent, @NonNull List<String> arguments, boolean read,
                                     @NonNull List<String> kinds) {
        if (read || arguments.stream().noneMatch(sent::contains)) return;
        String given = arguments.stream().filter(sent::contains).findFirst().orElseThrow();
        Microcopy words = HohenheimMicrocopy.VIOLATIONS.of("git_connection_applies")
            .withArg("arguments", String.join(", ", arguments)).withArg("kinds", String.join(", ", kinds));
        throw Violations.ofField(given, null, words);
    }

    /** @return the connection's stored values in its change input; a secret is left out, never read into a call */
    private static @NonNull Map<String, Object> stored(@NonNull Row connection) {
        Map<String, Object> values = new LinkedHashMap<>();
        COLUMNS.forEach((argument, column) -> values.put(argument, FieldRedaction.hidesWholeValue(column) ? null
            : connection.get(column.getName())));
        Identifier kind = Identifier.tryParse(String.valueOf((Object) connection.get(GitProviderModel.KIND)));
        values.put(KIND, kind == null ? null : kind.getPath());
        Map<String, Object> settings = RawValues.map(connection.get(GitProviderModel.SETTINGS));
        SETTINGS.forEach((argument, field) -> values.put(argument, settings.get(field.getName())));
        values.put(REGENERATE_KEY, false);
        return values;
    }

    /**
     * Gives an SSH connection saved without a private key a new Ed25519 pair: the panel's form and a tool's create
     * alike, so the person adds its public key to the repository as a read-only deploy key.
     */
    static void generateKey(@NonNull RowSave save) {
        Row row = save.row();
        GitProviderKind kind = GitProviderKinds.getHandler(row.get(GitProviderModel.KIND));
        Object held = row.get(GitProviderModel.SSH_PRIVATE_KEY);
        if (!save.isCreate() || kind == null || !kind.clonesWithKey()
                || held != null && !String.valueOf(held).isBlank()) {
            return;
        }
        row.set(GitProviderModel.SSH_PRIVATE_KEY, SshKeyPair.generate(keyComment(row.get(GitProviderModel.NAME)))
            .privateKey());
    }

    /** @return the public key of the connection's private key, null for none or for a key it cannot be read from */
    public static @Nullable SshPublicKey publicKeyOf(@NonNull Row connection) {
        GitProviderKind kind = GitProviderKinds.getHandler(connection.get(GitProviderModel.KIND));
        return kind == null || !kind.clonesWithKey() ? null
            : SshKeyPair.publicOf(connection.get(GitProviderModel.SSH_PRIVATE_KEY));
    }

    /** @return the comment a generated key's public line ends in: where it was made and for which connection */
    private static @NonNull String keyComment(@Nullable Object name) {
        String named = name == null ? "" : String.valueOf(name).strip();
        return named.isEmpty() ? "hohenheim" : "hohenheim " + named;
    }

    /** @return the operator's panel for an administrator, the /manage panel for anyone else */
    private static @NonNull Panel panelOf(@NonNull AccessContext caller) {
        return HohenheimAccess.isAdmin(caller) ? ApiConduits.adminPanel() : ApiConduits.managePanel();
    }

    /** @return the panel's git connection resource, the one whose form the panel posts */
    private static @NonNull PanelResource<Row> connections(@NonNull Panel panel) {
        return Objects.requireNonNull(CmsSupport.declaredRowEntry(panel, HohenheimSlugs.GIT_PROVIDERS),
            "the panel declares its git connections");
    }
}
