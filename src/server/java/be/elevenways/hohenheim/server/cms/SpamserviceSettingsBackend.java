package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.spamservice.client.SettingEntry;
import be.elevenways.spamservice.client.SettingsApplyResult;
import be.elevenways.spamservice.client.SpamserviceApiException;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.server.page.SettingsBackend;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.common.setting.SettingGroup;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Adapts Spamservice's metadata-rich management settings API to a SettingsPage mount. */
public final class SpamserviceSettingsBackend implements SettingsBackend {

    private final Supplier<SpamserviceClient> clientSupplier;
    private volatile @Nullable BuiltSnapshot lastSnapshot;

    public SpamserviceSettingsBackend() {
        this(() -> SpamserviceManager.get().client());
    }

    SpamserviceSettingsBackend(@NonNull Supplier<SpamserviceClient> clientSupplier) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier cannot be null");
    }

    @Override
    public @NonNull Snapshot snapshot() {
        SpamserviceClient client = this.clientSupplier.get();
        if (client == null) {
            return unavailableSnapshot();
        }
        try {
            BuiltSnapshot built = buildData(client.settings());
            this.lastSnapshot = built;
            return built.snapshot();
        } catch (SpamserviceApiException unavailable) {
            return unavailableSnapshot();
        }
    }

    @Override
    public @NonNull List<Refusal> validate(@NonNull Patch patch) {
        SpamserviceClient client = this.clientSupplier.get();
        if (client == null) {
            return List.of(new Refusal("", RefusalKind.NOT_ALLOWED, null));
        }
        try {
            BuiltSnapshot built = buildData(client.settings());
            this.lastSnapshot = built;
            return validateAgainst(patch, built);
        } catch (SpamserviceApiException unavailable) {
            return List.of(new Refusal("", RefusalKind.NOT_ALLOWED, null));
        }
    }

    @Override
    public @NonNull ApplyResult apply(@NonNull Patch patch) {
        SpamserviceClient client = this.clientSupplier.get();
        if (client == null) {
            return new ApplyResult("", List.of(
                new Refusal("", RefusalKind.NOT_ALLOWED, null)), 0, false, Set.of());
        }

        BuiltSnapshot built;
        try {
            built = buildData(client.settings());
            this.lastSnapshot = built;
        } catch (SpamserviceApiException unavailable) {
            return new ApplyResult("", List.of(
                new Refusal("", RefusalKind.NOT_ALLOWED, null)), 0, false, Set.of());
        }
        List<Refusal> refusals = validateAgainst(patch, built);
        if (!refusals.isEmpty()) {
            return new ApplyResult(built.snapshot().revision(), refusals, 0, false, Set.of());
        }

        Map<String, be.elevenways.spamservice.client.SettingChange> changes = new LinkedHashMap<>();
        Set<String> elided = new LinkedHashSet<>();
        for (Change change : patch.changes()) {
            if (change.kind() == ChangeKind.CLEAR) {
                changes.put(change.path(), be.elevenways.spamservice.client.SettingChange.reset());
                elided.add(change.path());
            } else {
                changes.put(change.path(), be.elevenways.spamservice.client.SettingChange.value(change.value()));
                SettingEntry entry = built.entries().get(change.path());
                SettingDefinition<?> definition = built.definitions().get(change.path());
                if (entry != null && definition != null) {
                    SettingDefinition.CoercionResult<?> coerced = definition.coerce(change.value());
                    if (coerced.accepted() && Objects.equals(coerced.value(), entry.defaultValue())) {
                        elided.add(change.path());
                    }
                }
            }
        }

        try {
            SettingsApplyResult result = client.applySettings(patch.expectedRevision(), changes);
            return new ApplyResult(result.revision(), List.of(), result.changed(),
                result.restartRequired(), elided);
        } catch (SpamserviceApiException failure) {
            if (failure.status() == 409 || failure.code().toLowerCase(Locale.ROOT).contains("revision")) {
                return new ApplyResult(built.snapshot().revision(), List.of(
                    new Refusal("", RefusalKind.STALE_REVISION, failure.getMessage())), 0, false, Set.of());
            }
            return new ApplyResult(built.snapshot().revision(), List.of(
                new Refusal("", RefusalKind.NOT_ALLOWED, null)), 0, false, Set.of());
        }
    }

    private @NonNull Snapshot unavailableSnapshot() {
        BuiltSnapshot cached = this.lastSnapshot;
        if (cached == null) {
            return new Snapshot(new SettingGroup("spamservice").label("Spamservice"), Map.of(), "",
                false, null);
        }
        Snapshot snapshot = cached.snapshot();
        return new Snapshot(snapshot.rootGroup(), snapshot.settings(), snapshot.revision(), false, null);
    }

    private static @NonNull Snapshot build(be.elevenways.spamservice.client.SettingsSnapshot remote) {
        return buildData(remote).snapshot();
    }

    private static @NonNull BuiltSnapshot buildData(be.elevenways.spamservice.client.SettingsSnapshot remote) {
        SettingGroup root = new SettingGroup("spamservice").label("Spamservice");
        Map<String, SettingGroup> groups = new LinkedHashMap<>();
        groups.put("", root);
        Map<String, SettingState> states = new LinkedHashMap<>();
        Map<String, SettingDefinition<?>> definitions = new LinkedHashMap<>();
        Map<String, SettingEntry> entries = new LinkedHashMap<>();

        for (SettingEntry entry : remote.settings()) {
            String path = entry.path();
            int lastDot = path.lastIndexOf('.');
            String groupPath = lastDot >= 0 ? path.substring(0, lastDot) : "";
            String name = lastDot >= 0 ? path.substring(lastDot + 1) : path;
            SettingGroup group = group(root, groups, groupPath);
            SettingDefinition<?> definition = definition(group, name, entry);
            definitions.put(path, definition);
            entries.put(path, entry);
            states.put(path, new SettingState(entry.secret() ? null : entry.value(), entry.configured(),
                entry.readonly(), entry.source(), entry.hasSecret()));
        }
        return new BuiltSnapshot(new Snapshot(root, states, remote.revision()), definitions, entries);
    }

    private static SettingGroup group(SettingGroup root, Map<String, SettingGroup> groups, String path) {
        if (path.isEmpty()) return root;
        SettingGroup current = root;
        StringBuilder full = new StringBuilder();
        for (String part : path.split("\\.")) {
            if (!full.isEmpty()) full.append('.');
            full.append(part);
            String key = full.toString();
            SettingGroup known = groups.get(key);
            if (known == null) {
                known = current.createGroup(part).label(humanize(part));
                groups.put(key, known);
            }
            current = known;
        }
        return current;
    }

    private static SettingDefinition<?> definition(SettingGroup group, String name, SettingEntry entry) {
        return switch (entry.type().toLowerCase(Locale.ROOT)) {
            case "boolean", "bool", "java.lang.boolean" -> configure(
                group.buildSetting(name, Boolean.class), entry, Boolean.class).build();
            case "integer", "int", "java.lang.integer" -> configure(
                group.buildSetting(name, Integer.class), entry, Integer.class).build();
            case "long", "java.lang.long" -> configure(
                group.buildSetting(name, Long.class), entry, Long.class).build();
            case "double", "float", "java.lang.double" -> configure(
                group.buildSetting(name, Double.class), entry, Double.class).build();
            default -> configure(group.buildSetting(name, String.class), entry, String.class).build();
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> SettingDefinition.Builder<T> configure(SettingDefinition.Builder<T> builder,
                                                               SettingEntry entry, Class<T> type) {
        builder.label(entry.label());
        if (entry.description() != null) builder.description(entry.description());
        if (entry.secret()) builder.secret();
        if (entry.multiline()) builder.multiline();
        if (entry.suffix() != null) builder.suffix(entry.suffix());
        if (entry.filesystemPath()) builder.filesystemPath();
        if (entry.restartRequired()) builder.restartRequired();
        if (entry.defaultValue() != null) {
            SettingDefinition.CoercionResult<T> coerced = temporary(type, entry.defaultValue());
            if (coerced.accepted()) builder.defaultValue(coerced.value());
        }
        if (!entry.allowedValues().isEmpty()) {
            List<T> allowed = new ArrayList<>();
            for (Object raw : entry.allowedValues()) {
                SettingDefinition.CoercionResult<T> coerced = temporary(type, raw);
                if (coerced.accepted()) allowed.add(coerced.value());
            }
            T[] array = (T[]) java.lang.reflect.Array.newInstance(type, allowed.size());
            builder.allowedValues(allowed.toArray(array));
        }
        return builder;
    }

    private static <T> SettingDefinition.CoercionResult<T> temporary(Class<T> type, Object raw) {
        SettingGroup temporary = new SettingGroup("temporary");
        return temporary.buildSetting("value", type).build().coerce(raw);
    }

    private static List<Refusal> validateAgainst(Patch patch, BuiltSnapshot built) {
        if (!Objects.equals(patch.expectedRevision(), built.snapshot().revision())) {
            return List.of(new Refusal("", RefusalKind.STALE_REVISION, built.snapshot().revision()));
        }
        List<Refusal> refusals = new ArrayList<>();
        for (Change change : patch.changes()) {
            SettingDefinition<?> definition = built.definitions().get(change.path());
            SettingEntry entry = built.entries().get(change.path());
            if (definition == null || entry == null) {
                refusals.add(new Refusal(change.path(), RefusalKind.NOT_ALLOWED, "Unknown setting path"));
                continue;
            }
            if (entry.readonly()) {
                refusals.add(new Refusal(change.path(), RefusalKind.READ_ONLY, entry.source()));
                continue;
            }
            if (change.kind() == ChangeKind.CLEAR) continue;
            SettingDefinition.CoercionResult<?> coerced = definition.coerce(change.value());
            if (!coerced.accepted()) {
                refusals.add(new Refusal(change.path(), RefusalKind.NOT_COERCIBLE,
                    String.valueOf(change.value())));
            } else if (!isAllowed(definition, coerced.value())) {
                refusals.add(new Refusal(change.path(), RefusalKind.NOT_ALLOWED,
                    String.valueOf(change.value())));
            }
        }
        return List.copyOf(refusals);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean isAllowed(SettingDefinition definition, @Nullable Object value) {
        return definition.isValueAllowed(value);
    }

    private static String humanize(String value) {
        String text = value.replace('_', ' ');
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private record BuiltSnapshot(Snapshot snapshot, Map<String, SettingDefinition<?>> definitions,
                                 Map<String, SettingEntry> entries) {}
}
