package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.AuditLogModel;
import be.elevenways.hohenheim.server.Secrets;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.result.RenderTemplateResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Database request handlers.
 */
public final class DatabaseHandlers {

    private DatabaseHandlers() {
    }

    public static void init() {
        DatabaseService databaseService = new DatabaseService();
        ServerService serverService = new ServerService();

        // List
        HohenheimEndpoints.DATABASES.setHandler(conduit -> {
            List<Map<String, Object>> items = new ArrayList<>();
            for (DatabaseService.Summary summary : databaseService.summaries()) {
                Map<String, Object> item = new HashMap<>();
                item.put("name", summary.name());
                item.put("engine", summary.engine());
                item.put("image", summary.image());
                item.put("database", summary.database());
                item.put("user", summary.user());
                item.put("ephemeral", summary.ephemeral());
                item.put("server", summary.server());
                item.put("status", summary.status());
                item.put("running", summary.running());
                item.put("port", summary.port() != null ? summary.port() : 0);
                item.put("canBackup", ManagedDatabase.Engine.supportsBackup(summary.engine()));
                items.add(item);
            }
            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/databases/list"),
                Map.of("databases", items)
            );
        });

        // Create form
        HohenheimEndpoints.DATABASES_CREATE_FORM.setHandler(conduit ->
            new RenderTemplateResult(Identifier.of("hohenheim", "hohenheim/databases/create"),
                databaseCreateVars(serverService, "")));

        // Detail (connection info)
        HohenheimEndpoints.DATABASES_DETAIL.setHandler(conduit -> {
            String name = conduit.getParameter(HohenheimEndpoints.DATABASE_NAME);
            DatabaseService.Detail detail = databaseService.detail(name);
            if (detail == null) {
                return HandlerSupport.redirectUntyped("/databases");
            }
            Map<String, Object> vars = new HashMap<>();
            vars.put("name", detail.name());
            vars.put("engine", detail.engine());
            vars.put("image", detail.image());
            vars.put("database", detail.database());
            vars.put("user", detail.user());
            vars.put("password", detail.password());
            vars.put("ephemeral", detail.ephemeral());
            vars.put("server", detail.server());
            vars.put("status", detail.status());
            vars.put("running", detail.running());
            vars.put("port", detail.port() != null ? detail.port() : 0);
            vars.put("canBackup", ManagedDatabase.Engine.supportsBackup(detail.engine()));
            return HandlerSupport.renderUntyped(Identifier.of("hohenheim", "hohenheim/databases/detail"), vars);
        });

        // Create (POST) -- provisions the container synchronously, then persists the record.
        HohenheimEndpoints.DATABASES_CREATE.setHandler(conduit -> {
            Map<String, String> form = HandlerSupport.formMap(conduit);

            String name = form.getOrDefault("name", "").trim();
            String engineToken = form.getOrDefault("engine", "").trim().toLowerCase();
            String database = form.getOrDefault("database", "").trim();
            String image = form.getOrDefault("image", "").trim();
            boolean ephemeral = HandlerSupport.checkbox(form, "ephemeral");
            String server = form.getOrDefault("server", ServerService.LOCAL).trim();
            if (server.isEmpty()) {
                server = ServerService.LOCAL;
            }

            // User defaults to "appuser"; a blank password is auto-generated (shown on the detail page).
            String user = form.getOrDefault("db_user", "").trim();
            if (user.isEmpty()) {
                user = "appuser";
            }
            String password = form.getOrDefault("db_password", "").trim();
            if (password.isEmpty()) {
                password = Secrets.generatePassword();
            }

            String error = validateDatabaseForm(name, engineToken, database);
            if (error != null) {
                return HandlerSupport.renderUntyped(Identifier.of("hohenheim", "hohenheim/databases/create"),
                    databaseCreateVars(serverService, error));
            }

            // Provision in the background on the chosen host; the detail page shows the status.
            databaseService.createAsync(name, ManagedDatabase.Engine.valueOf(engineToken.toUpperCase()),
                image.isEmpty() ? null : image, user, password, database, ephemeral, server);

            HandlerSupport.audit(conduit, AuditLogModel.ACTION_CREATED, AuditLogModel.RESOURCE_DATABASE, name, name);
            return HandlerSupport.redirectUntyped("/databases/" + name);   // detail page shows status + connection info
        });

        // Backup (POST) -- download a SQL dump
        HohenheimEndpoints.DATABASES_BACKUP.setHandler(conduit -> {
            String name = conduit.getParameter(HohenheimEndpoints.DATABASE_NAME);
            String dump;
            try {
                dump = databaseService.backup(name);
            } catch (IOException e) {
                Blast.log("DB: backup of", name, "failed -", e.getMessage());
                return HandlerSupport.redirectUntyped("/databases?error=backup_failed");
            }
            HandlerSupport.download(conduit, "application/sql", name + ".sql", dump);
            return null;
        });

        // Delete (POST)
        HohenheimEndpoints.DATABASES_DELETE.setHandler(conduit -> {
            String name = conduit.getParameter(HohenheimEndpoints.DATABASE_NAME);
            try {
                databaseService.destroy(name, true);
                HandlerSupport.audit(conduit, AuditLogModel.ACTION_DELETED, AuditLogModel.RESOURCE_DATABASE,
                    name, name);
            } catch (IOException e) {
                // The container/volume may already be gone; record only succeeds via destroy() above.
                Blast.log("DB: delete of", name, "failed -", e.getMessage());
                return HandlerSupport.redirectUntyped("/databases?error=delete_failed");
            }
            return HandlerSupport.redirectUntyped("/databases");
        });
    }

    private static Map<String, Object> databaseCreateVars(ServerService serverService, String error) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("error", error);
        vars.put("servers", serverService.names());   // names only -- no reachability probe on the form
        return vars;
    }

    private static String validateDatabaseForm(String name, String engine, String database) {
        String nameError = HandlerSupport.validateName(name);
        if (nameError != null) return nameError;
        if (engine.isEmpty()) return "Engine is required";
        try {
            ManagedDatabase.Engine.valueOf(engine.toUpperCase());
        } catch (IllegalArgumentException e) {
            return "Unknown engine: " + engine;
        }
        if (database.isEmpty()) return "Database name is required";
        return null;   // user defaults / password auto-generates in the handler
    }
}
