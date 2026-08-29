package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.host.HostState;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.instance.ConsoleKind;
import be.elevenways.hohenheim.instance.ReadinessKind;
import be.elevenways.hohenheim.instance.StopKind;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.schedule.ScheduleRunStatuses;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.field.RegistryEnumField;
import be.elevenways.zenit.microcopy.server.DefaultCatalogLoader;
import be.elevenways.zenit.common.task.record.RecordScheduleRunModel;
import be.elevenways.zenit.common.task.record.RunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every status an admin surface RENDERS is covered by the vocabulary that declares it, and
 * no surface classifies a status it does not know.
 *
 * AIDEV-NOTE: this exists because three surfaces each grew their own partial copy of a
 * status vocabulary and then quietly disagreed with the producer. The schedule-run cell
 * called everything that was not "completed" destructive (so a RUNNING chain was red), the
 * site-databases tab knew three of DatabaseModel's four statuses (so destroy_failed -- the
 * one carrying an operator obligation -- rendered as a neutral grey pill), and the host
 * cell's dot switch had a {@code default -> "online"} arm (so an unknown state looked
 * healthy). None of the three could fail a test, because a fallback branch always
 * rendered SOMETHING. These assertions read the producers' own declarations.
 */
@DisplayName("Status presentation drift")
class StatusPresentationDriftTest {

    @Test
    @DisplayName("the schedule-run badge covers every status the framework can store")
    void scheduleRunStatusesAreExhaustive() {
        // 1. The framework declares its run statuses as the RunStatus enum, and the
        //    persisted column offers exactly those keys.
        List<String> declared = RunStatus.storageKeys();
        assertThat(RecordScheduleRunModel.STATUS.getValues().keySet())
            .as("step 1: the stored column is derived from the enum")
            .containsExactlyElementsOf(declared);

        // 2. Every one of them has a declared label/icon/colour here. A status added
        //    upstream breaks the exhaustive switches in ScheduleRunStatuses at COMPILE
        //    time; this assertion is the belt to that braces.
        assertThat(ScheduleRunStatuses.FIELD.getValues().keySet())
            .as("step 2: the presentation table covers exactly the framework's statuses")
            .containsExactlyInAnyOrderElementsOf(declared);

        // 3. Each carries the three facts the badge needs, and only the genuinely good
        //    outcome is green -- "completed with failures" must not read as success.
        for (EnumField.EnumValue value : ScheduleRunStatuses.FIELD.getValues().values()) {
            assertThat(value.getIcon()).as("step 3: %s declares an icon", value.getKey()).isNotNull();
            assertThat(value.getColor()).as("step 3: %s declares a colour", value.getKey()).isNotNull();
            assertThat(value.getMicrocopy()).as("step 3: %s declares a label", value.getKey()).isNotNull();
        }
        assertThat(ScheduleRunStatuses.FIELD.getValues()
                .get(RunStatus.COMPLETED.storageKey()).getColor())
            .as("step 3: a clean completion is the success colour").isEqualTo("success");
        assertThat(ScheduleRunStatuses.FIELD.getValues()
                .get(RunStatus.COMPLETED_WITH_FAILURES.storageKey()).getColor())
            .as("step 3: a partial completion is neither success nor outright failure")
            .isEqualTo("warning");
        assertThat(ScheduleRunStatuses.FIELD.getValues()
                .get(RunStatus.RUNNING.storageKey()).getColor())
            .as("step 3: a chain still running is not painted as a failure")
            .isNotEqualTo("destructive");

        // 4. An unknown status gets no colour at all rather than a wrong one.
        assertThat(ScheduleRunStatuses.badgeFor("teleported").known())
            .as("step 4: an unknown status renders as plain text, never a styled verdict")
            .isFalse();
        assertThat(ScheduleRunStatuses.badgeFor(null))
            .as("step 4: a missing status is no badge").isNull();
    }

    @Test
    @DisplayName("the database status badge covers every status the model declares")
    void databaseStatusesAreExhaustive() {
        // 1. The model's STATUS_* constants ARE the vocabulary the site-databases tab renders.
        List<String> declared = constantsWithPrefix(DatabaseModel.class, "STATUS_");
        assertThat(DatabaseModel.STATUS.getValues().keySet())
            .as("step 1: the status field offers exactly the declared statuses")
            .containsExactlyInAnyOrderElementsOf(declared);

        // 2. Each carries the badge facets the tab and the list both read off the field.
        for (EnumField.EnumValue value : DatabaseModel.STATUS.getValues().values()) {
            assertThat(value.getIcon()).as("step 2: %s declares an icon", value.getKey()).isNotNull();
            assertThat(value.getColor()).as("step 2: %s declares a colour", value.getKey()).isNotNull();
            assertThat(value.getMicrocopy()).as("step 2: %s is localizable", value.getKey()).isNotNull();
        }

        // 3. The state an operator must act on is destructive, not neutral.
        assertThat(DatabaseModel.STATUS.getValues().get(DatabaseModel.STATUS_DESTROY_FAILED).getColor())
            .as("step 3: a failed destroy shouts").isEqualTo("destructive");
    }

    @Test
    @DisplayName("only a healthy host reads as online")
    void hostStateNeverDegradesToOnline() {
        // 1. Every state carries a dot, so none can fall through to a default.
        for (HostState state : HostState.values()) {
            assertThat(state.dot()).as("step 1: %s declares a dot", state).isNotBlank();
            assertThat(state.token()).as("step 1: %s declares a token", state).isNotBlank();
        }

        // 2. Green belongs to OK alone -- the whole point of the type.
        List<HostState> online = Arrays.stream(HostState.values())
            .filter(state -> "online".equals(state.dot())).toList();
        assertThat(online).as("step 2: only OK looks healthy").containsExactly(HostState.OK);

        // 3. Every state that is not OK says something; OK shows the daemon label instead.
        for (HostState state : HostState.values()) {
            if (state == HostState.OK) {
                assertThat(state.wording()).as("step 3: OK has no state word").isNull();
            } else {
                assertThat(state.wording()).as("step 3: %s carries wording", state).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("every notification event is offered as a subscription")
    void notificationEventsAreDerived() {
        // 1. ALL is derived, so it can never lag a declared event.
        assertThat(NotificationEvents.ALL)
            .as("step 1: one entry per declared event")
            .hasSize(NotificationEvents.values().length);

        // 2. Every declared event is a known subscription token -- the validator that
        //    refuses unknown events and the picker that offers them read the same list.
        for (NotificationEvents event : NotificationEvents.values()) {
            assertThat(NotificationEvents.isKnown(event.token()))
                .as("step 2: %s is selectable", event).isTrue();
        }

        // 3. Tokens are distinct: two events sharing one token would silently merge
        //    subscriptions.
        assertThat(NotificationEvents.ALL).as("step 3: tokens are unique").doesNotHaveDuplicates();
        assertThat(NotificationEvents.isKnown("not_an_event"))
            .as("step 3: an undeclared token is refused").isFalse();
    }

    @Test
    @DisplayName("the enum-backed columns are derived from their enum, and fail closed")
    void enumBackedColumnsCannotDriftFromTheirDeclaringEnum() {

        // 1. Each of these columns is BUILT from its enum, so the stored option set cannot
        //    drift from the members -- a new member is one edit. The assertion binds the
        //    two ACROSS sites (the column's value map vs the enum's own values), never an
        //    enum against constants beside it.
        record Bound(String name, EnumField field, List<String> tokens) {}
        List<Bound> bound = List.of(
            new Bound("volume backend", ServerModel.VOLUME_BACKEND,
                Arrays.stream(VolumeBackend.values()).map(VolumeBackend::token).toList()),
            new Bound("readiness kind", InstanceTemplateModel.READINESS_KIND,
                Arrays.stream(ReadinessKind.values()).map(ReadinessKind::token).toList()),
            new Bound("stop kind", InstanceTemplateModel.STOP_KIND,
                Arrays.stream(StopKind.values()).map(StopKind::token).toList()),
            // The console vocabulary moved into the KIND SETTINGS (every Docker kind
            // declares the same field off the same builder); bind one of them.
            new Bound("console kind", DockerContainerKind.CONSOLE_KIND,
                Arrays.stream(ConsoleKind.values()).map(ConsoleKind::token).toList()));

        for (Bound entry : bound) {
            assertThat(entry.field().getValues().keySet())
                .as("step 1: %s offers exactly its enum's tokens", entry.name())
                .containsExactlyInAnyOrderElementsOf(entry.tokens());

            // 2. Every member is localizable: a column an admin surface renders with an
            //    English literal is the drift this whole class exists to catch.
            for (EnumField.EnumValue value : entry.field().getValues().values()) {
                assertThat(value.getMicrocopy())
                    .as("step 2: %s / %s is localizable", entry.name(), value.getKey())
                    .isNotNull();
            }
        }

        // 3. The volume backend also carries the pill facets the host page renders, and
        //    the refusing member is the one that shouts.
        for (VolumeBackend backend : VolumeBackend.values()) {
            assertThat(backend.icon()).as("step 3: %s declares an icon", backend).isNotBlank();
            assertThat(backend.color()).as("step 3: %s declares a colour", backend).isNotBlank();
        }
        assertThat(VolumeBackend.NONE.color())
            .as("step 3: a volume root that can enforce nothing shouts")
            .isEqualTo("destructive");

        // 4. Every lookup fails CLOSED: an unknown token is null, never a default member
        //    that would promise a capability the filesystem does not have.
        assertThat(VolumeBackend.forToken("ext4")).as("step 4: unknown backend").isNull();
        assertThat(ReadinessKind.forToken("vibes")).as("step 4: unknown readiness").isNull();
        assertThat(StopKind.forToken("plead")).as("step 4: unknown stop").isNull();
        assertThat(ConsoleKind.forToken("janeway")).as("step 4: unknown console").isNull();
        assertThat(ConsoleKind.forToken("tty"))
            .as("step 4: the interactive console is the tty member, and it is the only"
                + " member that is interactive")
            .isSameAs(ConsoleKind.TTY);
        assertThat(ConsoleKind.TTY.interactive()).isTrue();
        assertThat(ConsoleKind.PLAIN.interactive()).isFalse();
        assertThat(ConsoleKind.declaredIn(java.util.Map.of()))
            .as("step 4: an undeclared console is the plain default")
            .isSameAs(ConsoleKind.PLAIN);
        assertThat(ConsoleKind.declaredIn(java.util.Map.of(ConsoleKind.SETTING, "janeway")))
            .as("step 4: an unknown declared token is null, never a default")
            .isNull();
    }

    @Test
    @DisplayName("the converted string vocabularies are faceted enums with one declaring home")
    void convertedVocabulariesAreFacetedAndSingleHomed() throws Exception {
        // 1. Each column's value set IS its model's declared constant set.
        record Vocabulary(String name, EnumField field, Class<?> home, String prefix) {}
        List<Vocabulary> vocabularies = List.of(
            new Vocabulary("domain match type", SiteDomainModel.MATCH_TYPE,
                SiteDomainModel.class, "MATCH_"),
            new Vocabulary("released-claim match type", ReleasedRouteClaimModel.MATCH_TYPE,
                SiteDomainModel.class, "MATCH_"),
            new Vocabulary("access-list satisfy", AccessListModel.SATISFY,
                AccessListModel.class, "SATISFY_"),
            new Vocabulary("dns zone role", DnsZoneModel.ROLE,
                DnsZoneModel.class, "ROLE_"),
            new Vocabulary("ban source", BanModel.SOURCE,
                BanModel.class, "SOURCE_"),
            new Vocabulary("certificate dns publisher", CertificateModel.DNS_PUBLISHER,
                CertificateModel.class, "DNS_PUBLISHER_"));
        for (Vocabulary vocabulary : vocabularies) {
            assertThat(vocabulary.field().getValues().keySet())
                .as("step 1: %s offers exactly its declared constants", vocabulary.name())
                .containsExactlyInAnyOrderElementsOf(
                    constantsWithPrefix(vocabulary.home(), vocabulary.prefix()));

            // 2. Every member carries the pill facets plus a localizable label.
            for (EnumField.EnumValue value : vocabulary.field().getValues().values()) {
                assertThat(value.getIcon())
                    .as("step 2: %s / %s declares an icon", vocabulary.name(), value.getKey())
                    .isNotNull();
                assertThat(value.getColor())
                    .as("step 2: %s / %s declares a colour", vocabulary.name(), value.getKey())
                    .isNotNull();
                assertThat(value.getMicrocopy())
                    .as("step 2: %s / %s is localizable", vocabulary.name(), value.getKey())
                    .isNotNull();
            }
        }

        // 3. The two match-type columns share ONE vocabulary (SiteDomainModel.matchTypeField):
        //    a member added for domains reaches released claims in the same edit.
        assertThat(ReleasedRouteClaimModel.MATCH_TYPE.getValues().keySet())
            .as("step 3: released claims store the SAME match-type member set as domains")
            .containsExactlyElementsOf(SiteDomainModel.MATCH_TYPE.getValues().keySet());

        // 4. Step 1 is CIRCULAR -- it compares an enum to constants declared beside it, so it
        //    cannot see a value some OTHER file writes. This step asks the writers instead:
        //    dns_publisher is chosen by a hand-built select in the request form, and that
        //    select offered "command" (CommandDnsTxtPublisher.ID) while the enum did not,
        //    which is the narrowing this whole method exists to catch.
        assertThat(dnsModeOptionsOffered())
            .as("step 4: every dns_publisher the request form offers is a declared member")
            .isNotEmpty()
            .allMatch(CertificateModel.DNS_PUBLISHER::isValidValue,
                "declared in CertificateModel.DNS_PUBLISHER");
    }

    @Test
    @DisplayName("the dns zone role badge shows the role word and explains itself elsewhere")
    void dnsZoneRoleLabelsAreShortAndDescribed() {
        DefaultCatalogLoader catalogs = new DefaultCatalogLoader();

        for (EnumField.EnumValue value : DnsZoneModel.ROLE.getValues().values()) {
            for (String tag : List.of("en", "nl")) {
                LocaleChain chain = LocaleChain.ofTags(tag);
                String label = value.getLabel().resolve(chain, catalogs);

                // 1. The badge text is the role WORD: one word, no parenthetical. The list
                //    column used to carry 180px of nowrap text because the explanation was
                //    smuggled into the label.
                assertThat(label)
                    .as("step 1: %s / %s resolves to real copy", tag, value.getKey())
                    .isNotEqualTo(value.getLabel().key());
                assertThat(label)
                    .as("step 1: %s / %s is the bare role word", tag, value.getKey())
                    .doesNotContain("(").doesNotContain(")").doesNotContain(" ");

                // 2. The explanation is not lost: it is DECLARED as the value's description
                //    facet, in the same locales, and says something the label does not.
                assertThat(value.getDescription())
                    .as("step 2: %s declares a description", value.getKey())
                    .isNotNull();
                String description = value.getDescription().resolve(chain, catalogs);
                assertThat(description)
                    .as("step 2: %s / %s description resolves to real copy", tag, value.getKey())
                    .isNotEqualTo(value.getDescription().key())
                    .isNotBlank()
                    .isNotEqualTo(label);
            }
        }
    }

    @Test
    @DisplayName("every enum value an admin surface renders carries localizable copy")
    void everyEnumValueIsLocalizable() throws Exception {
        DefaultCatalogLoader catalogs = new DefaultCatalogLoader();
        List<String> unlocalizable = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();

        // 1. The classes to inspect are DISCOVERED, never listed: every source file that
        //    builds an EnumField is one, so a new model joins this test by existing.
        List<Class<?>> declaring = classesDeclaringEnumFields();
        assertThat(declaring)
            .as("step 1: the source scan found the enum-declaring classes")
            .hasSizeGreaterThan(30);

        // 2. Every value of every static EnumField declares its own translation token.
        //    Without one, EnumValue.getLabel() falls back to Microcopy.of(displayName) and
        //    the English display name becomes a catalog key that no locale can ever answer.
        for (Class<?> type : declaring) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || field.getType() != EnumField.class) {
                    continue;
                }
                field.setAccessible(true);
                EnumField enumField = (EnumField) field.get(null);
                // A registry enum has no declared values at all: it enumerates the
                // registered TypeDefinitions at call time (which needs a datasource) and
                // takes its labels from them, so there is no displayName here to audit.
                if (enumField == null || enumField instanceof RegistryEnumField) {
                    continue;
                }
                for (EnumField.EnumValue value : enumField.getValues().values()) {
                    String where = type.getSimpleName() + "." + field.getName()
                        + " / " + value.getKey();
                    if (value.getMicrocopy() == null) {
                        unlocalizable.add(where + " (\"" + value.getDisplayName() + "\")");
                        continue;
                    }
                    // 3. A token nothing translates is the same English-only outcome with
                    //    an extra step, so both shipped catalogs must answer it.
                    for (String tag : List.of("en", "nl")) {
                        String resolved = value.getLabel()
                            .resolve(LocaleChain.ofTags(tag), catalogs);
                        if (resolved == null || resolved.isBlank()
                                || resolved.equals(value.getLabel().key())) {
                            unresolved.add(tag + " " + where);
                        }
                    }
                }
            }
        }
        assertThat(unlocalizable)
            .as("step 2: add .label(Microcopy.of(<short key>).withFilter(\"scope\", ...))")
            .isEmpty();
        assertThat(unresolved)
            .as("step 3: add the entry to BOTH en.json and nl.json")
            .isEmpty();

        // 4. Reflection only sees STATIC fields, so an EnumField built inside a method
        //    (a row action's option list) escapes step 2 entirely. The source lane closes
        //    that hole: a literal displayName in a .value(...) chain needs a .label(...)
        //    beside it, wherever the field is built.
        assertThat(valueChainsWithoutALabel())
            .as("step 4: a literal displayName without a label is an English-only value")
            .isEmpty();
    }

    /** Every source file under src/ that builds an {@link EnumField}, loaded. */
    private static List<Class<?>> classesDeclaringEnumFields() throws Exception {
        List<Class<?>> types = new ArrayList<>();
        for (Path file : javaSourcesContaining("EnumField.builder(")) {
            String name = binaryNameOf(file);
            try {
                types.add(Class.forName(name));
            } catch (Throwable unloadable) {
                throw new IllegalStateException("cannot load " + name, unloadable);
            }
        }
        return types;
    }

    /**
     * {@code Class.getName()} for a source file, from its {@code package} declaration --
     * the directory layout is not trusted because the source sets nest differently.
     */
    private static String binaryNameOf(Path file) throws Exception {
        String source = Files.readString(file);
        Matcher matcher = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE)
            .matcher(source);
        assertThat(matcher.find()).as("%s declares a package", file).isTrue();
        String simple = file.getFileName().toString().replace(".java", "");
        return matcher.group(1) + "." + simple;
    }

    /** Java sources under {@code src/} whose text contains the needle. */
    private static List<Path> javaSourcesContaining(String needle) throws Exception {
        List<Path> hits = new ArrayList<>();
        try (var walk = Files.walk(Path.of("src"))) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                if (!file.getFileName().toString().endsWith(".java")) {
                    continue;
                }
                if (Files.readString(file).contains(needle)) {
                    hits.add(file);
                }
            }
        }
        return hits;
    }

    /**
     * Every {@code .value(...)} chain carrying a LITERAL display name but no label, as
     * {@code file:line} strings; a computed display name (an operator-defined option) has
     * no catalog key by construction and is not matched.
     */
    private static List<String> valueChainsWithoutALabel() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaSourcesContaining(".displayName(\"")) {
            String source = Files.readString(file);
            Matcher matcher = Pattern.compile("\\.value\\(").matcher(source);
            while (matcher.find()) {
                int open = matcher.end() - 1;
                int close = matchingParen(source, open);
                if (close < 0) {
                    continue;
                }
                String chain = source.substring(open, close + 1);
                if (chain.contains(".displayName(\"") && !chain.contains(".label(")) {
                    long line = source.substring(0, open).chars().filter(c -> c == '\n').count() + 1;
                    offenders.add(file + ":" + line);
                }
            }
        }
        return offenders;
    }

    /**
     * Index of the {@code )} closing the {@code (} at {@code open}, or -1; parentheses
     * inside a string literal do not count ({@code "Foreign (known)"} is one display name).
     */
    private static int matchingParen(String source, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** The {@code dns_mode} select's literal option values, read out of the request template. */
    private static List<String> dnsModeOptionsOffered() throws Exception {
        String template = Files.readString(
            Path.of("src/common/templates/cms/certificate-request.hwk"));
        int select = template.indexOf("name=\"dns_mode\"");
        assertThat(select).as("the request form still carries a dns_mode select")
            .isGreaterThan(-1);
        String body = template.substring(select, template.indexOf("</pl-select>", select));

        List<String> offered = new ArrayList<>();
        Matcher matcher = Pattern.compile("<pl-select-item value=\"([^\"]+)\"").matcher(body);
        while (matcher.find()) {
            offered.add(matcher.group(1));
        }
        return offered;
    }

    /** Public static String constants of a class whose NAME starts with the prefix. */
    private static List<String> constantsWithPrefix(Class<?> type, String prefix) {
        List<String> values = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!field.getName().startsWith(prefix)
                    || !Modifier.isStatic(field.getModifiers())
                    || !Modifier.isPublic(field.getModifiers())
                    || field.getType() != String.class) {
                continue;
            }
            try {
                values.add((String) field.get(null));
            } catch (IllegalAccessException unreadable) {
                throw new IllegalStateException("cannot read " + field.getName(), unreadable);
            }
        }
        return values;
    }
}
