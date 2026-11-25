package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.instance.InstanceVariableOperations;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SiteRedirectModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.site.ProtectPath;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.zenit.common.coerce.PrimitiveCoercion;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.JsonValue;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Plain;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationForms;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.revision.MarkedRevisions;
import be.elevenways.zenit.common.orm.revision.RevisionMark;
import be.elevenways.zenit.common.orm.revision.RevisionMarkModel;
import be.elevenways.zenit.common.orm.revision.RevisionMarks;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A site's parts beside its settings as operations: its protected paths and path redirects through their panel
 * resources ({@link PanelRecordWrites}), and the marks on its saved revisions, set by their natural key.
 *
 * AIDEV-NOTE: a protected path given people, networks or a sign-in provider points at the dedicated list the site's
 * own "Protect a path" writes (SiteOperationHandlers.dedicatedList), made in the create's one command before the path
 * row. A mark is named by its site, revision and kind over core's MarkedRevisions, the writer core's own mark
 * operations run; placing a label or a hold again replaces its words, which MarkedRevisions alone refuses as placed.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SitePartWrites {

    /** The argument naming the site a part belongs to. */
    public static final String SITE = "site";

    /** The argument giving the people who may pass, each a username and a password. */
    public static final String CREDENTIALS = "credentials";

    /** The argument naming the sign-in provider visitors must pass. */
    public static final String SIGN_IN_PROVIDER = SiteKindWrites.SIGN_IN_PROVIDER;

    /** A person's username, in each entry of the credentials list. */
    static final String USERNAME = "username";

    /** A person's password, in each entry of the credentials list. */
    static final String PASSWORD = "password";

    /** The argument lifting what a call names instead of setting it. */
    static final String REMOVE = "remove";

    /** The argument giving where a path redirect sends visitors (its stored target). */
    public static final String TARGET_URL = "target_url";

    private static final String MARK = "mark";
    private static final String REVISION = "revision";
    private static final String TEXT = "text";

    /**
     * The people who may pass, one username and password each: the shape protected_path_set and sign_in_provider_set
     * both take; a password is written, never shown again.
     */
    public static final JsonValue CREDENTIAL_LIST = JsonValue.of(CREDENTIALS).schema(credentialShape())
        .help(HohenheimMicrocopy.HELP.of("credential_list")).build();

    private static final FormEntry PROVIDER_PICK = OperationForms.recordId(SIGN_IN_PROVIDER,
        HohenheimMicrocopy.HELP.of("protected_path_sign_in_provider"), SiteAuthProviderModel.MODEL_ID, false);

    /**
     * A protected path: made under its site, guarding a path prefix by a list picked or made from the people, networks
     * or provider given; a change moves the path or points it at another list.
     */
    public static final PanelRecordWrites PROTECTED_PATHS = PanelRecordWrites.of("protected_path",
            ProtectedPathModel.MODEL_ID, SubjectType.record(ProtectedPathModel.REF), ProtectedPathModel.ID,
            HohenheimSlugs.PROTECTED_PATHS)
        .parent(SITE, HohenheimMicrocopy.HELP.of("protected_path_site"), ProtectedPathModel.SITE_ID,
            SiteModel.MODEL_ID)
        .settings(List.of(ProtectedPathModel.PATH))
        .link(SiteKindWrites.ACCESS_LIST, HohenheimMicrocopy.HELP.of("protected_path_list"),
            ProtectedPathModel.ACCESS_LIST_ID, AccessListModel.MODEL_ID, false)
        .fillsOnCreate(List.of(CREDENTIAL_LIST, ProtectPath.NETWORK_LIST, PROVIDER_PICK), SitePartWrites::protection)
        .build();

    /** A path redirect: made under its site, its path, match, target and status changing after. */
    public static final PanelRecordWrites PATH_REDIRECTS = PanelRecordWrites.of("path_redirect",
            SiteRedirectModel.MODEL_ID, SubjectType.record(SiteRedirectModel.REF), SiteRedirectModel.ID,
            HohenheimSlugs.REDIRECTS)
        .parent(SITE, HohenheimMicrocopy.HELP.of("path_redirect_site"), SiteRedirectModel.SITE_ID, SiteModel.MODEL_ID)
        .settings(List.of(SiteRedirectModel.FROM_PATH))
        .setting("path_match", SiteRedirectModel.MATCH)
        .setting(TARGET_URL, SiteRedirectModel.TARGET)
        .setting("status", SiteRedirectModel.HTTP_STATUS, HohenheimMicrocopy.HELP.of("path_redirect_status"))
        .build();

    /** What a mark asks: the revision, the kind of mark by its word, its words and whether it is lifted instead. */
    private static final FormSpec MARK_FORM = FormSpec.builder()
        .add(Plain.of(IntegerField.builder().name(REVISION).nullable(true)
            .help(HohenheimMicrocopy.HELP.of("revision_mark_revision")).build()))
        .add(Select.of(StringField.builder(MARK).required().help(HohenheimMicrocopy.HELP.of("revision_mark_kind"))
            .build()).options(OptionSource.supplied(SitePartWrites::markOptions)).build())
        .add(Plain.of(StringField.builder(TEXT).help(HohenheimMicrocopy.HELP.of("revision_mark_text")).build()))
        .add(Plain.of(BooleanField.builder(REMOVE).defaultValue(false)
            .help(HohenheimMicrocopy.HELP.of("revision_mark_remove")).build()))
        .build();

    /**
     * Places or lifts one mark on one saved revision of a site (a hold also on the whole site), answering the marks in
     * force there; a hold needs the hold right, a pin or a label the right to change the site.
     */
    public static final Operation<Row, OperationInput.Values, String> SET_MARK = Operation.declare(
            HohenheimIds.id("set_site_revision_mark"))
        .label(HohenheimMicrocopy.HOHENHEIM.of("set_site_revision_mark"))
        .description(HohenheimMicrocopy.HOHENHEIM.of("set_site_revision_mark_description"))
        .happened(OperationSentences.of("set_site_revision_mark"))
        .icon(Icon.of("thumbtack"))
        .one(SiteOperations.SITE)
        .gate(OperationGate.open())
        .input(OperationInput.derived(MARK_FORM))
        .result(String.class)
        .command(OperationCommand.perSubject())
        .register();

    static {
        OperationHandlers.attach(SET_MARK)
            .applies(SiteOperationHandlers::live)
            .authorize(SitePartWrites::markRefusal)
            .handle(call -> marked(call.subject(), Objects.requireNonNull(call.input(), "a mark has its input")));
    }

    private SitePartWrites() {
    }

    /** @return a list of {@code {username, password}} objects, the password written and never shown again */
    private static @NonNull Map<String, Object> credentialShape() {
        return InstanceVariableOperations.textPairs(USERNAME, PASSWORD, true);
    }

    /** @return each person given as its username and its password's text, in the order given */
    static @NonNull List<Map<String, Object>> people(@Nullable Object given) {
        List<Map<String, Object>> people = new ArrayList<>();
        if (!(given instanceof List<?> list)) return people;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> person)) continue;
            Map<String, Object> said = new LinkedHashMap<>();
            said.put(USERNAME, plain(person.get(USERNAME)));
            said.put(PASSWORD, plain(person.get(PASSWORD)));
            people.add(said);
        }
        return people;
    }

    /** @return a value as its text, a sealed one unsealed; null stays null */
    static @Nullable String plain(@Nullable Object value) {
        if (value instanceof Secret sealed) return sealed.reveal();
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Points a new path at the list the people, networks or provider given make, written as the site's "Protect a
     * path" writes it; with an access list given instead, nothing is filled and the path names that one.
     *
     * @throws Violations for more than one of the four, or an empty one
     * @throws DomainRefusal not found for a site the caller does not manage
     */
    private static @NonNull Map<String, Object> protection(OperationInput.@NonNull Values input,
                                                           @NonNull Set<String> sent, @Nullable Object siteKey,
                                                           @NonNull AccessContext caller) {
        boolean listed = input.get(SiteKindWrites.ACCESS_LIST) != null;
        if (sent.size() + (listed ? 1 : 0) > 1) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("protected_path_one_guard"));
        }
        if (sent.isEmpty() || siteKey == null) return Map.of();
        Row site = Models.get(SiteModel.class).findById(Integer.valueOf(String.valueOf(siteKey)));
        if (site == null || !SiteOperationHandlers.live(site) || !HohenheimAccess.reachesRecord(caller,
                SiteModel.MODEL_ID, site.get(SiteModel.ID), HohenheimCapabilities.MANAGE)) {
            throw new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "site " + siteKey + " is not reachable");
        }
        if (!SiteParts.servesHttp(site)) {
            throw Violations.ofField(SITE, null, HohenheimMicrocopy.VIOLATIONS.of("protected_path_no_http"));
        }
        String method = sent.contains(CREDENTIALS) ? ProtectPath.METHOD_PASSWORD
            : sent.contains(ProtectPath.NETWORKS.getName()) ? ProtectPath.METHOD_NETWORK : ProtectPath.METHOD_SIGN_IN;
        Object provider = input.get(SIGN_IN_PROVIDER);
        String path = String.valueOf(input.get(ProtectedPathModel.PATH.getInputName()));
        ProtectPath.Input asked = new ProtectPath.Input(path, method, people(input.get(CREDENTIALS)),
            PrimitiveCoercion.toTrimmedTextList(input.get(ProtectPath.NETWORKS.getName())),
            provider == null ? null : Integer.valueOf(String.valueOf(provider)));
        // The panel's refusals name its own entries; the call's arguments are named by the spec's words.
        Integer list = KindSettingsWrites.speaking(() -> SiteOperationHandlers.dedicatedList(site, asked, caller),
            Map.of(ProtectPath.PEOPLE.name(), CREDENTIALS, ProtectPath.PROVIDER_ID.getName(), SIGN_IN_PROVIDER));
        return Map.of(ProtectedPathModel.ACCESS_LIST_ID.getName(), list);
    }

    /** @return each mark as an option under its word, the path of its id ({@code pinned}, {@code label}) */
    private static @NonNull List<FieldOption<String>> markOptions() {
        List<FieldOption<String>> options = new ArrayList<>();
        for (RevisionMark mark : RevisionMarks.registry()) {
            options.add(FieldOption.of(mark.id().getPath(), mark.label()));
        }
        return options;
    }

    /** @return the mark of this word, null for none */
    private static @Nullable RevisionMark markOf(@Nullable Object word) {
        for (RevisionMark mark : RevisionMarks.registry()) {
            if (mark.id().getPath().equals(String.valueOf(word))) return mark;
        }
        return null;
    }

    /**
     * @return null when the caller may set the mark: the right the mark names (a hold's), and the right to change the
     *     site, which a pin or a label needs as the history tab's own placement asks
     */
    private static @Nullable DomainRefusal markRefusal(@NonNull Row site, OperationInput.@Nullable Values input,
                                                       @NonNull AccessContext access) {
        if (!HohenheimAccess.reachesRecord(access, SiteModel.MODEL_ID, site.get(SiteModel.ID),
                HohenheimCapabilities.MANAGE)) {
            return new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "site " + site.get(SiteModel.ID)
                + " is not reachable");
        }
        RevisionMark mark = input == null ? null : markOf(input.get(MARK));
        if (mark != null && mark.permission() != null && !access.hasPermission(mark.permission())) {
            return new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "the caller may not set a " + mark.id().getPath());
        }
        return null;
    }

    /**
     * Places the mark on the revision, its words replacing those of the same mark already there, or lifts it.
     *
     * @return the marks in force on the revision (or the whole site), with their words
     * @throws Violations for words given to a mark without any, or a lift of a mark not in force
     */
    private static @NonNull String marked(@NonNull Row site, OperationInput.@NonNull Values input) {
        RevisionMark mark = Objects.requireNonNull(markOf(input.get(MARK)), "the form admits only marks");
        Integer revision = input.get(REVISION) instanceof Number number ? number.intValue() : null;
        String text = Texts.trimmedOrNull(input.get(TEXT));
        if (text != null && !mark.carriesText()) {
            throw Violations.ofField(TEXT, text, HohenheimMicrocopy.VIOLATIONS.of("revision_mark_no_text")
                .withArg("mark", mark.id().getPath()));
        }
        Model sites = Models.get(SiteModel.class);
        Object key = Objects.requireNonNull(site.get(SiteModel.ID));
        Row placed = placed(sites, key, mark, revision);
        if (Boolean.TRUE.equals(input.get(REMOVE))) {
            if (placed == null) {
                throw Violations.ofField(MARK, mark.id().getPath(),
                    HohenheimMicrocopy.VIOLATIONS.of("revision_mark_not_in_force"));
            }
            MarkedRevisions.remove(sites, key, ((Number) Objects.requireNonNull(placed.get(RevisionMarkModel.ID)))
                .longValue(), mark);
        } else {
            if (placed != null && mark.carriesText()) {
                MarkedRevisions.remove(sites, key, ((Number) Objects.requireNonNull(placed.get(RevisionMarkModel.ID)))
                    .longValue(), mark);
            }
            MarkedRevisions.place(sites, key, revision, mark, text);
        }
        return inForce(sites, key, revision);
    }

    /** @return the mark of this kind in force on the revision (null: the whole site), null for none */
    private static @Nullable Row placed(@NonNull Model sites, @NonNull Object key, @NonNull RevisionMark mark,
                                       @Nullable Integer revision) {
        for (Row row : at(sites, key, revision)) {
            if (mark.id().toString().equals(row.get(RevisionMarkModel.MARK))) return row;
        }
        return null;
    }

    /** @return the marks in force on the revision (null: the whole site), oldest first */
    private static @NonNull List<Row> at(@NonNull Model sites, @NonNull Object key, @Nullable Integer revision) {
        List<Row> at = new ArrayList<>();
        for (Row row : MarkedRevisions.inForce(sites.getResolvedDatasource(), SiteModel.MODEL_ID.toString(),
                String.valueOf(key))) {
            if (Objects.equals(revision, row.get(RevisionMarkModel.REVISION))) at.add(row);
        }
        return at;
    }

    /** @return the site's code and revision, and each mark in force there by its word and its words */
    private static @NonNull String inForce(@NonNull Model sites, @NonNull Object key, @Nullable Integer revision) {
        List<String> marks = new ArrayList<>();
        for (Row row : at(sites, key, revision)) {
            String word = PanelRecordWrites.wordOf(String.valueOf((Object) row.get(RevisionMarkModel.MARK)));
            String words = row.get(RevisionMarkModel.TEXT);
            marks.add(words == null ? word : word + " (" + words + ")");
        }
        String code = RecordCodes.codeUnjudged(SiteModel.MODEL_ID, String.valueOf(key));
        return code + (revision == null ? " as a whole" : " revision " + revision) + ": "
            + (marks.isEmpty() ? "no marks in force" : String.join(", ", marks));
    }
}
