package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.instance.InstanceAttachmentOperations;
import be.elevenways.hohenheim.server.database.DatabaseWrites;
import be.elevenways.zenit.common.edit.Plain;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Secret;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationPipeline;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Set;

/**
 * The managed-database tier's creates and changes as operations: a database, a shared engine and an app's attachment
 * of a database, each through its panel resource ({@link PanelRecordWrites}).
 *
 * AIDEV-NOTE: a database's password goes in and never comes out. A change carrying {@code password} or
 * {@code generate_password} is the password change's own: its gate and availability decide (the Change password
 * button's offer), then the same writer stores it. The fields describing a provisioned container are frozen after
 * create in the panel, so here they are create-only: a change naming one is refused by name.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class DatabaseRecordWrites {

    /** The argument setting a new generated password on a database. */
    public static final String GENERATE_PASSWORD = "generate_password";

    /** The argument naming the shared engine a database goes on. */
    public static final String ENGINE = "database_engine";

    /** The argument naming the host a database or an engine runs on. */
    public static final String HOST = "host";

    /** The argument naming the app a database is attached to. */
    public static final String APP = "app";

    /** The argument naming the database an app is attached to. */
    public static final String DATABASE = "database";

    private static final BooleanField GENERATE = BooleanField.builder(GENERATE_PASSWORD).defaultValue(false)
        .help(HohenheimMicrocopy.HELP.of("database_generate_password")).build();

    /** A managed database's create and change. */
    public static final PanelRecordWrites DATABASES = PanelRecordWrites.of("database", DatabaseModel.MODEL_ID,
            SubjectType.record(DatabaseModel.REF), DatabaseModel.ID, HohenheimSlugs.DATABASES)
        .createOnly(List.of(DatabaseModel.NAME, DatabaseModel.ENGINE, DatabaseModel.PLACEMENT))
        .link(ENGINE, HohenheimMicrocopy.HELP.of("database_engine_code"), DatabaseModel.ENGINE_ID,
            DatabaseEngineModel.MODEL_ID, true)
        .createOnly(List.of(DatabaseModel.DB_NAME, DatabaseModel.DB_USER, DatabaseModel.DB_PASSWORD,
            DatabaseModel.IMAGE, DatabaseModel.EPHEMERAL))
        .settings(List.of(DatabaseModel.MEMORY_LIMIT_MB, DatabaseModel.CPU_LIMIT, DatabaseModel.DUMPS_KEPT))
        // Where its dumps go: the storage named by code, the app's own argument name for its backups' storage.
        .link(AppKindWrites.BACKUP_TARGET, HohenheimMicrocopy.HELP.of("database_backup_target_code"),
            DatabaseModel.BACKUP_TARGET_ID, BackupTargetModel.MODEL_ID, false)
        .link(HOST, HohenheimMicrocopy.HELP.of("database_host_code"), DatabaseModel.HOST_ID, HostModel.MODEL_ID, true)
        .also(List.of(KindSettingsWrites.entryOf(DatabaseModel.DB_PASSWORD), Plain.of(GENERATE)),
            DatabaseRecordWrites::passwordChange)
        // A new password is set inside the engine: a change runs outside the receipt's transaction, as that does.
        .changesAs(CmsCommands.EXTERNAL)
        .build();

    /** A shared engine's create and change. */
    public static final PanelRecordWrites ENGINES = PanelRecordWrites.of("database_engine",
            DatabaseEngineModel.MODEL_ID, SubjectType.record(DatabaseEngineModel.REF), DatabaseEngineModel.ID,
            HohenheimSlugs.DATABASE_ENGINES)
        .createOnly(List.of(DatabaseEngineModel.NAME, DatabaseEngineModel.ENGINE, DatabaseEngineModel.ROOT_USER,
            DatabaseEngineModel.ROOT_PASSWORD, DatabaseEngineModel.IMAGE))
        .settings(List.of(DatabaseEngineModel.MEMORY_LIMIT_MB, DatabaseEngineModel.CPU_LIMIT))
        .link(HOST, HohenheimMicrocopy.HELP.of("database_engine_host_code"), DatabaseEngineModel.HOST_ID,
            HostModel.MODEL_ID, true)
        .build();

    /** An app's attachment of a database: made once, only its variable prefix changing after. */
    public static final PanelRecordWrites ATTACHMENTS = PanelRecordWrites.of("database_link",
            InstanceDatabaseModel.MODEL_ID, InstanceAttachmentOperations.DATABASE_LINK, InstanceDatabaseModel.ID,
            HohenheimSlugs.INSTANCE_DATABASES)
        // The app is the attachment's fixed parent: the attachment is made under it, as from that app's tab.
        .parent(APP, HohenheimMicrocopy.HELP.of("database_link_app"), InstanceDatabaseModel.INSTANCE_ID,
            InstanceModel.MODEL_ID)
        .link(DATABASE, HohenheimMicrocopy.HELP.of("database_link_database"), InstanceDatabaseModel.DATABASE_ID,
            DatabaseModel.MODEL_ID, true)
        .settings(List.of(InstanceDatabaseModel.ENV_PREFIX))
        .build();

    private DatabaseRecordWrites() {
    }

    /**
     * Judges the password the call gave, or a generated one, as the Change password button is judged (refused where
     * that button is not offered or is dead, in its words) before anything is written.
     *
     * @return the write setting it, as that button's own writer does
     */
    private static @NonNull Runnable passwordChange(@NonNull Row database, OperationInput.@NonNull Values input,
                                                    @NonNull Set<String> sent, @NonNull AccessContext caller) {
        String passwordName = DatabaseModel.DB_PASSWORD.getInputName();
        Object typed = input.get(passwordName);
        boolean generate = Boolean.TRUE.equals(input.get(GENERATE_PASSWORD));
        if (generate && typed != null) {
            throw Violations.ofField(GENERATE_PASSWORD, true,
                HohenheimMicrocopy.VIOLATIONS.of("database_password_twice"));
        }
        if (!generate && typed == null) return () -> { };
        switch (OperationPipeline.offer(DatabaseParts.CHANGE_PASSWORD, caller, database)) {
            case OperationPipeline.Offer.Hidden hidden -> throw new DomainRefusal(ZenitRefusalReason.FORBIDDEN,
                "the caller may not change this database's password");
            case OperationPipeline.Offer.Unavailable dead -> throw Violations.ofField(passwordName, null,
                dead.reason());
            case OperationPipeline.Offer.Available available -> { }
        }
        Secret password = typed == null ? null
            : typed instanceof Secret given ? given : Secret.of(String.valueOf(typed));
        return () -> DatabaseWrites.changePassword(database, password);
    }
}
