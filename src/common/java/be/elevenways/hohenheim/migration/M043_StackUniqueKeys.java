package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Backs the stack editor's uniqueness checks with real constraints: the app-level
 * read-then-write checks lose a concurrent-submit race, and a duplicate service name
 * means two containers claiming one name and DNS alias.
 */
public class M043_StackUniqueKeys extends Migration {

    public M043_StackUniqueKeys() {
        super("2026_07_27_000043", "Unique keys for stack services and files");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.assertUnique("stack_services", "stack_id", "name");
        schema.alterTable("stack_services", table -> table.unique("stack_id", "name"));

        schema.assertUnique("stack_files", "stack_service_id", "container_path");
        schema.alterTable("stack_files", table -> table.unique("stack_service_id", "container_path"));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("stack_files", table ->
            table.dropIndex("stack_files_stack_service_id_container_path_unique"));
        schema.alterTable("stack_services", table ->
            table.dropIndex("stack_services_stack_id_name_unique"));
    }
}
