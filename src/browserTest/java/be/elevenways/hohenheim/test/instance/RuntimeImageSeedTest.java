package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.server.instance.RuntimeImageSeeder;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.seed.Seeds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The built-in runtime images exist, are code-owned, and every one of them names a build
 * context that is actually in this repository.
 *
 * AIDEV-NOTE: the Dockerfile check is the point. Jelle's decision on phase-0 open question 5
 * is that there is NO registry -- each host builds these locally from {@code build_context}
 * at first use -- so a row naming a directory nobody committed is an image that cannot be
 * built anywhere, and nothing else in the system would notice until a create failed on a
 * fresh host.
 */
class RuntimeImageSeedTest {

    @BeforeAll
    static void bootDatasource() {
        HohenheimTestRuntime.ensureDatasource();
    }

    @Test
    @DisplayName("every built-in runtime image is seeded, complete and buildable from this repo")
    void theBuiltInsAreSeededAndBuildable() {

        Seeds.run(Datasources.getDefault(), new RuntimeImageSeeder());

        RuntimeImageModel images = Models.get(RuntimeImageModel.class);
        List<Row> builtins = images.find().where(RuntimeImageModel.BUILTIN.eq(true)).all();

        // 1. Every one the design names is there, by name, so a renamed row is a failure
        //    rather than a silent second image. node-16 and node-12 are the Phoenix
        //    migration's legacy Alchemy runtimes.
        assertThat(builtins.stream().map(row -> row.get(RuntimeImageModel.NAME)).toList())
            .as("step 1: the shipped built-ins")
            .containsExactlyInAnyOrder("node-22", "node-16", "node-12", "java-21",
                "debian-13", "static");

        for (Row image : builtins) {
            String name = image.get(RuntimeImageModel.NAME);

            // 2. Every one carries what a create needs to run it at all.
            assertThat(image.get(RuntimeImageModel.DOCKER_IMAGE))
                .as("step 2: '%s' names a docker image", name).isNotBlank();
            assertThat(image.get(RuntimeImageModel.WORKDIR))
                .as("step 2: '%s' works in the data directory", name).isEqualTo("/home/site");
            assertThat(image.get(RuntimeImageModel.UID_MODE))
                .as("step 2: '%s' runs as a mapped uid", name)
                .isEqualTo(RuntimeImageModel.UID_MAPPED);
            assertThat(image.get(RuntimeImageModel.ENABLED))
                .as("step 2: '%s' is offered", name).isTrue();

            // 3. And the build context it names is a real directory holding a Dockerfile.
            String context = image.get(RuntimeImageModel.BUILD_CONTEXT);
            assertThat(context).as("step 3: '%s' names a build context", name).isNotBlank();
            assertThat(Files.isRegularFile(Path.of(context, "Dockerfile")))
                .as("step 3: '%s' has a committed Dockerfile at %s", name, context)
                .isTrue();

            // 4. The Incus variant is the LOCAL alias the conversion imports under
            //    (RuntimeImages), never a public-catalog name -- the same "no registry"
            //    decision the docker reference follows.
            assertThat((String) image.get(RuntimeImageModel.INCUS_IMAGE))
                .as("step 4: '%s' names its local Incus alias", name)
                .isEqualTo("hohenheim/" + name);
        }
    }

    @Test
    @DisplayName("a built-in is code-owned: an operator edit reverts on the next seed")
    void builtInsAreSynced() {

        Seeds.run(Datasources.getDefault(), new RuntimeImageSeeder());

        RuntimeImageModel images = Models.get(RuntimeImageModel.class);
        Row node = images.findByName("node-22");
        assertThat(node).as("the fixture image exists").isNotNull();
        String original = node.get(RuntimeImageModel.DOCKER_IMAGE);

        // 1. An operator edits a built-in.
        node.set(RuntimeImageModel.DOCKER_IMAGE, "someone-elses/node:latest");
        images.save(node);
        assertThat(images.findByName("node-22").get(RuntimeImageModel.DOCKER_IMAGE))
            .as("step 1: the edit lands").isEqualTo("someone-elses/node:latest");

        // 2. The next boot re-asserts it, because ctx.sync means the CODE owns this row --
        //    a row that disagreed with the Dockerfile beside it describes an image nobody
        //    can build.
        Seeds.run(Datasources.getDefault(), new RuntimeImageSeeder());
        assertThat(images.findByName("node-22").get(RuntimeImageModel.DOCKER_IMAGE))
            .as("step 2: the built-in reverts").isEqualTo(original);
    }
}
