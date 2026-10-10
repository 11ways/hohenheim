package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.zenit.common.orm.field.EnumField;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The {@link OperationStatus} members one operation record stores, and how it spells each in its status column.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class OperationLifecycle {

    private final Map<OperationStatus, String> stored;

    private OperationLifecycle(@NonNull Map<OperationStatus, String> stored) {
        this.stored = stored;
    }

    /** @return a lifecycle storing each member under its own token */
    public static @NonNull OperationLifecycle of(@NonNull OperationStatus... members) {
        Map<OperationStatus, String> stored = new EnumMap<>(OperationStatus.class);
        for (OperationStatus member : members) {
            stored.put(member, member.token());
        }
        return new OperationLifecycle(stored);
    }

    /**
     * @return a copy storing {@code member} under a spelling rows already carry
     * @throws IllegalArgumentException for a member this lifecycle does not store
     */
    public @NonNull OperationLifecycle storing(@NonNull OperationStatus member, @NonNull String token) {
        this.stored(member);
        Map<OperationStatus, String> copy = new EnumMap<>(this.stored);
        copy.put(member, token);
        return new OperationLifecycle(copy);
    }

    /**
     * @return the column value of {@code member}
     * @throws IllegalArgumentException for a member this lifecycle does not store
     */
    public @NonNull String stored(@NonNull OperationStatus member) {
        String token = this.stored.get(member);
        if (token == null) {
            throw new IllegalArgumentException(member + " is not a status of this lifecycle");
        }
        return token;
    }

    /** @return the column values of every stored member {@code which} admits, in declaration order */
    public @NonNull List<String> stored(@NonNull Predicate<OperationStatus> which) {
        List<String> tokens = new ArrayList<>();
        for (Map.Entry<OperationStatus, String> entry : this.stored.entrySet()) {
            if (which.test(entry.getKey())) {
                tokens.add(entry.getValue());
            }
        }
        return List.copyOf(tokens);
    }

    /** @return the member a column value stands for, null for one no member stores */
    public @Nullable OperationStatus read(@Nullable Object value) {
        for (Map.Entry<OperationStatus, String> entry : this.stored.entrySet()) {
            if (entry.getValue().equals(value)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** @return whether a column value is {@code member}'s */
    public boolean is(@Nullable Object value, @NonNull OperationStatus member) {
        return this.read(value) == member;
    }

    /** @return the status column, each stored value wearing its member's words, icon and colour */
    public @NonNull EnumField field(@NonNull String name) {
        EnumField.Builder builder = EnumField.builder(name);
        for (Map.Entry<OperationStatus, String> entry : this.stored.entrySet()) {
            OperationStatus member = entry.getKey();
            builder.value(entry.getValue(), v -> v.label(member.label()).icon(member.icon()).color(member.variant()));
        }
        return builder.label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("status")).build();
    }
}
