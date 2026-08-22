package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.seed.SeedContext;
import be.elevenways.zenit.server.orm.seed.Seeder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The built-in runtime images ("yolks"), re-asserted on every boot.
 *
 * AIDEV-NOTE: {@code sync}, not {@code ensure} or {@code once}: these rows are CODE-OWNED,
 * so an operator edit to a built-in reverts at the next boot and a deleted one comes back.
 * That is deliberate -- the image's build context lives in this repository under
 * {@code images/}, so a row that disagreed with the Dockerfile beside it would describe an
 * image nobody can build. An operator who wants a variant copies the row.
 *
 * AIDEV-NOTE: the image references are LOCAL tags, never registry paths (Jelle's decision
 * on phase-0 open question 5): each host builds them from {@code build_context} at first
 * use. {@code incus_image} stays null until the Incus variants are published in phase 1;
 * the runtime-image picker reads that null as "cannot run on an Incus host".
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class RuntimeImageSeeder implements Seeder {

    /** Stable seed ids: the primary keys these code-owned rows are re-asserted under. */
    private static final int ID_NODE_22 = 1;
    private static final int ID_JAVA_21 = 2;
    private static final int ID_DEBIAN_13 = 3;
    private static final int ID_STATIC = 4;

    public RuntimeImageSeeder() {
    }

    @Override
    public void seed(@NonNull SeedContext ctx) {

        RuntimeImageModel images = Models.get(RuntimeImageModel.class);

        ctx.sync(images, ID_NODE_22, row -> image(row, "node-22",
            "Node.js 22 on Debian, with npm, git and a login shell.",
            "code", "hohenheim/node-22:1", "images/node-22",
            "npm start", 3000, "npm ci && npm run build --if-present"));

        ctx.sync(images, ID_JAVA_21, row -> image(row, "java-21",
            "Temurin JDK 21 with Gradle and Maven homes inside the data directory.",
            "flask", "hohenheim/java-21:1", "images/java-21",
            "java -jar app.jar", 8080, "./gradlew --no-daemon build"));

        ctx.sync(images, ID_DEBIAN_13, row -> image(row, "debian-13",
            "A plain Debian userland for a workspace that installs its own tools.",
            "terminal", "hohenheim/debian-13:1", "images/debian-13",
            null, null, null));

        ctx.sync(images, ID_STATIC, row -> image(row, "static",
            "Serves the files in the data directory over HTTP; nothing to install.",
            "file-lines", "hohenheim/static:1", "images/static",
            null, 8080, null));
    }

    private static void image(Row row, String name, String description, String icon,
                              String dockerImage, String buildContext,
                              String defaultCommand, Integer defaultPort,
                              String defaultBuildCommand) {
        row.set(RuntimeImageModel.NAME, name);
        row.set(RuntimeImageModel.DESCRIPTION, description);
        row.set(RuntimeImageModel.ICON, icon);
        row.set(RuntimeImageModel.DOCKER_IMAGE, dockerImage);
        row.set(RuntimeImageModel.INCUS_IMAGE, null);
        row.set(RuntimeImageModel.BUILD_CONTEXT, buildContext);
        row.set(RuntimeImageModel.DEFAULT_COMMAND, defaultCommand);
        row.set(RuntimeImageModel.DEFAULT_PORT, defaultPort);
        row.set(RuntimeImageModel.DEFAULT_BUILD_COMMAND, defaultBuildCommand);
        row.set(RuntimeImageModel.WORKDIR, "/home/site");
        row.set(RuntimeImageModel.SHELL, "/bin/bash");
        row.set(RuntimeImageModel.UID_MODE, RuntimeImageModel.UID_MAPPED);
        row.set(RuntimeImageModel.BUILTIN, true);
        row.set(RuntimeImageModel.ENABLED, true);
    }
}
