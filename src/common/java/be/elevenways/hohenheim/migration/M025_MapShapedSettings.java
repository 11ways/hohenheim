package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Converts the JSON list-of-{name,value} shapes to plain maps and the numeric
 * user/node references to registry keys (SQLite JSON1 transforms; Hohenheim's
 * only backend is SQLite).
 *
 * @throws UnsupportedOperationException from {@link #down} — the transform is
 *         not reversed; all readers also accept the legacy shapes.
 */
public class M025_MapShapedSettings extends Migration {

    public M025_MapShapedSettings() {
        super("2026_07_07_000025", "Map-shaped env vars, headers, credentials + registry-keyed user/node");
    }

    @Override
    public void up(MigrationBuilder schema) {
        // Site settings: environment_variables list -> map.
        schema.execute("""
            UPDATE sites SET settings = json_set(settings, '$.environment_variables',
              coalesce((SELECT json_group_object(json_extract(j.value,'$.name'),
                                                 coalesce(json_extract(j.value,'$.value'),''))
                        FROM json_each(sites.settings, '$.environment_variables') j
                        WHERE json_extract(j.value,'$.name') IS NOT NULL), json('{}')))
            WHERE json_type(settings, '$.environment_variables') = 'array'""");

        // Site settings: system_user_id -> user (registry key), resolvable ids first.
        schema.execute("""
            UPDATE sites SET settings = json_set(settings, '$.user',
              'hohenheim:' || (SELECT name FROM system_users
                               WHERE id = json_extract(sites.settings,'$.system_user_id')))
            WHERE json_extract(settings,'$.system_user_id') IS NOT NULL
              AND EXISTS (SELECT 1 FROM system_users
                          WHERE id = json_extract(sites.settings,'$.system_user_id'))""");
        schema.execute("""
            UPDATE sites SET settings = json_remove(settings, '$.system_user_id')
            WHERE json_extract(settings,'$.system_user_id') IS NOT NULL""");

        // Site settings: node_version_id -> node (registry key), resolvable ids first.
        schema.execute("""
            UPDATE sites SET settings = json_set(settings, '$.node',
              'hohenheim:' || (SELECT version FROM node_versions
                               WHERE id = json_extract(sites.settings,'$.node_version_id')))
            WHERE json_extract(settings,'$.node_version_id') IS NOT NULL
              AND EXISTS (SELECT 1 FROM node_versions
                          WHERE id = json_extract(sites.settings,'$.node_version_id'))""");
        schema.execute("""
            UPDATE sites SET settings = json_remove(settings, '$.node_version_id')
            WHERE json_extract(settings,'$.node_version_id') IS NOT NULL""");

        // Git source settings: build_environment_variables list -> map.
        schema.execute("""
            UPDATE sites SET source_settings = json_set(source_settings, '$.build_environment_variables',
              coalesce((SELECT json_group_object(json_extract(j.value,'$.name'),
                                                 coalesce(json_extract(j.value,'$.value'),''))
                        FROM json_each(sites.source_settings, '$.build_environment_variables') j
                        WHERE json_extract(j.value,'$.name') IS NOT NULL), json('{}')))
            WHERE json_type(source_settings, '$.build_environment_variables') = 'array'""");

        // Domain header lists -> maps (an emptied list becomes NULL = no headers).
        schema.execute("""
            UPDATE site_domains SET custom_headers =
              (SELECT json_group_object(json_extract(j.value,'$.name'),
                                        coalesce(json_extract(j.value,'$.value'),''))
               FROM json_each(site_domains.custom_headers) j
               WHERE json_extract(j.value,'$.name') IS NOT NULL)
            WHERE json_type(custom_headers) = 'array'""");
        schema.execute("""
            UPDATE site_domains SET response_headers =
              (SELECT json_group_object(json_extract(j.value,'$.name'),
                                        coalesce(json_extract(j.value,'$.value'),''))
               FROM json_each(site_domains.response_headers) j
               WHERE json_extract(j.value,'$.name') IS NOT NULL)
            WHERE json_type(response_headers) = 'array'""");

        // Basic-auth credentials list -> username -> hash map.
        schema.execute("""
            UPDATE site_auth_providers SET config = json_set(config, '$.credentials',
              coalesce((SELECT json_group_object(json_extract(j.value,'$.username'),
                                                 json_extract(j.value,'$.password_hash'))
                        FROM json_each(site_auth_providers.config, '$.credentials') j
                        WHERE json_extract(j.value,'$.username') IS NOT NULL
                          AND json_extract(j.value,'$.password_hash') IS NOT NULL), json('{}')))
            WHERE json_type(config, '$.credentials') = 'array'""");
    }

    @Override
    public void down(MigrationBuilder schema) {
        throw new UnsupportedOperationException(
            "M025 is a one-way data-shape migration; readers accept both shapes");
    }
}
