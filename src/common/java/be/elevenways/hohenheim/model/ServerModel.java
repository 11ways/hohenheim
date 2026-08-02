package be.elevenways.hohenheim.model;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A Docker host the platform can manage: the implicit {@code local} daemon, or a remote one
 * reached over SSH ({@code mode = "ssh"}, {@code ssh_target} like {@code user@host}). The basis of
 * the multi-server inventory; {@code DockerClient}s are built per server from these records.
 */
public class ServerModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "server");
    public static final Schema SCHEMA = new Schema();

    /** {@link #MODE} value for the implicit local Docker daemon. */
    public static final String MODE_LOCAL = "local";

    /** {@link #MODE} value for a remote daemon reached over SSH. */
    public static final String MODE_SSH = "ssh";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name").build());
    public static final EnumField MODE = SCHEMA.addField(EnumField.builder("mode")
        .value(MODE_LOCAL, v -> v.displayName("Local").icon("house").color("teal"))
        .value(MODE_SSH, v -> v.displayName("SSH").icon("terminal").color("indigo"))
        .build());
    public static final StringField SSH_TARGET = SCHEMA.addField(StringField.builder().name("ssh_target").build());
    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        // The name is the human title (relation pickers, refusal messages), not "Server #id".
        SCHEMA.setDisplayFields(NAME);
    }

    /** The server with this unique name, or null if none. */
    public Row findByName(String name) {
        return find().where(NAME.eq(name)).first();
    }

    // -- THE canonical host-key derivation -----------------------------------

    /**
     * Fold any operator-facing host spelling to the canonical server NAME: null, blank
     * and {@code 0.0.0.0}-era empties become {@code local}, registry keys
     * ({@code hohenheim:<x>}) are unwrapped to their path, everything is trimmed.
     *
     * AIDEV-NOTE: THE one spelling normalisation. The local daemon used to be spelled
     * both {@code ""} and {@code "local"} across three separate normalisations
     * (DockerSiteRequestHandler, DatabaseService, StackServiceResource), which would
     * have split one machine's port claims into two disjoint sets while every unique
     * constraint held. Every consumer -- runtime resolution ({@link #canonicalServerId})
     * AND the M051 legacy heal -- must route through this method; never re-derive.
     */
    public static @NonNull String canonicalSpelling(@Nullable Object raw) {
        if (raw == null) {
            return MODE_LOCAL;
        }
        String spelling = String.valueOf(raw).trim();
        if (spelling.indexOf(':') >= 0) {
            Identifier key = Identifier.tryParse(spelling);
            if (key != null) {
                spelling = key.getPath().trim();
            }
        }
        return spelling.isEmpty() ? MODE_LOCAL : spelling;
    }

    /**
     * THE canonical host key: resolve any spelling (row id, {@code hohenheim:<id>}
     * registry key, server name, blank/"local") to the {@code servers.id} every
     * FK and port claim references.
     *
     * @throws IllegalArgumentException when the spelling names no known server
     */
    public static int canonicalServerId(@Nullable Object raw) {
        if (raw instanceof Number number) {
            return requireExisting(number.intValue());
        }
        String spelling = canonicalSpelling(raw);
        if (MODE_LOCAL.equals(spelling)) {
            return localServerId();
        }
        Row byName = Models.get(ServerModel.class).findByName(spelling);
        if (byName != null) {
            return byName.get(ID);
        }
        if (spelling.chars().allMatch(Character::isDigit)) {
            return requireExisting(Integer.parseInt(spelling));
        }
        throw new IllegalArgumentException("No server named '" + spelling + "'");
    }

    /** The implicit local daemon's row id, creating its row when absent (idempotent). */
    public static int localServerId() {
        ServerModel model = Models.get(ServerModel.class);
        Row row = model.findByName(MODE_LOCAL);
        if (row == null) {
            row = model.createEmptyRow();
            row.set(NAME, MODE_LOCAL);
            row.set(MODE, MODE_LOCAL);
            model.save(row);
        }
        return row.get(ID);
    }

    /** The display/transport name of a server id; a null id means the local daemon. */
    public static @NonNull String nameOf(@Nullable Integer serverId) {
        if (serverId == null) {
            Models.get(ServerModel.class);   // fail fast on an unbooted model registry
            return MODE_LOCAL;
        }
        Row row = Models.get(ServerModel.class).findById(serverId);
        if (row == null) {
            throw new IllegalArgumentException("No server with id " + serverId);
        }
        return String.valueOf(row.get(NAME));
    }

    /** The registry key a type-settings map stores for a server ({@code hohenheim:<id>}). */
    public static @NonNull String registryKeyOf(int serverId) {
        return Identifier.of("hohenheim", String.valueOf(serverId)).toString();
    }

    private static int requireExisting(int serverId) {
        if (Models.get(ServerModel.class).findById(serverId) == null) {
            throw new IllegalArgumentException("No server with id " + serverId);
        }
        return serverId;
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "Server"; }

    @Override
    public String getTableName() { return "servers"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
