package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.ArtifactOperationModel;
import be.elevenways.hohenheim.model.ArtifactSourceModel;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.ControllerIdentityModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstallMediaFetchModel;
import be.elevenways.hohenheim.model.InstanceLogModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.model.LinkedServerCodeModel;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.ReconcileFindingModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteOutageModel;
import be.elevenways.hohenheim.model.SiteSessionModel;
import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.model.WebhookDeliveryModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.peer.SharedWithPeersBehaviour;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.server.orm.migration.MigrationChecksum;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which kinds a linked master keeps a record of: every model of {@code be.elevenways.hohenheim.model} is either
 * declared shared on its schema or named here as not shared, with its reason; a model in neither fails, so a new
 * model is decided on before it lands, and fails closed (unshared) until then.
 *
 * AIDEV-NOTE: the rule (docs/cluster.md): a kind is shared when a person or an assistant declares it, never when the
 * system produces it or when it belongs to the install itself. A shared model's title is kept in clear on the master,
 * so a shared model may override its title only when that title is reviewed here as reading no secret field.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class SharedKindsDriftTest {

    private static final Path MODELS = Path.of("src/common/java/be/elevenways/hohenheim/model");

    /** Every model a linked master keeps no record of, and why. */
    private static final Map<Class<? extends Model>, String> NOT_SHARED = Map.ofEntries(
        entry(ArtifactOperationModel.class, "produced: a build's artifact steps"),
        entry(ArtifactSourceModel.class, "produced: where a build's artifact came from"),
        entry(BanModel.class, "produced: the automatic blocking of addresses"),
        entry(BuildOperationModel.class, "produced: the build history"),
        entry(CertificateModel.class, "produced: issued by ACME for this server's own names"),
        entry(InstallMediaFetchModel.class, "produced: a download of install media"),
        entry(InstanceLogModel.class, "produced: an app's log lines"),
        entry(InstanceSnapshotModel.class, "produced: a machine's snapshots on this server's disks"),
        entry(PortAllocationModel.class, "produced: ports this server handed out"),
        entry(PreviewDeploymentModel.class, "produced: previews a push deploys"),
        entry(ReconcileFindingModel.class, "produced: what the reconciler found"),
        entry(ReleaseOperationModel.class, "produced: the release history"),
        entry(ReleasedRouteClaimModel.class, "produced: addresses released by this server's proxy"),
        entry(SiteOutageModel.class, "produced: the outage watch's own state"),
        entry(SiteSessionModel.class, "produced: visitors' sessions"),
        entry(StackDeploymentModel.class, "produced: a stack's deploy history"),
        entry(WebhookDeliveryModel.class, "produced: deliveries of notifications"),
        entry(ControllerIdentityModel.class, "the install itself: this database's own identity"),
        entry(SpamserviceInstallationModel.class, "the install itself: its own Spamservice installation"),
        entry(SystemUserModel.class, "the install itself: the users of its own hosts"),
        entry(LinkedServerModel.class, "the install itself: the servers it is linked to"),
        entry(LinkedServerCodeModel.class, "the install itself: the join codes it issued"));

    /** The shared models whose own title override was read and reads no secret field. */
    private static final Map<Class<? extends Model>, String> REVIEWED_TITLES = Map.of(
        EnvironmentModel.class, "its own name, and with a reader its project's title: neither is secret");

    @Test
    void everyModelIsSharedOrNamedAsNotSharedWithItsReason() throws Exception {
        List<String> shared = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        TreeSet<String> found = new TreeSet<>();
        StringBuilder births = new StringBuilder();
        for (Supplier<Migration> supplier : MigrationRunner.discoverMigrations("default")) {
            Migration migration = supplier.get();
            if (migration.getClass().getName().startsWith("be.elevenways.hohenheim.")) {
                births.append(MigrationChecksum.canonicalText(migration)).append('\n');
            }
        }
        for (Class<? extends Model> type : modelClasses()) {
            found.add(type.getSimpleName());
            Schema schema = (Schema) type.getField("SCHEMA").get(null);
            Identifier modelId = (Identifier) type.getField("MODEL_ID").get(null);
            SharedWithPeersBehaviour declared = schema.getBehaviour(SharedWithPeersBehaviour.class);
            boolean named = NOT_SHARED.containsKey(type);

            // 1. Each model is decided once: shared on its schema, or named as not shared.
            if (declared == null && !named) {
                problems.add(type.getSimpleName() + " is neither shared nor named as not shared");
            }
            if (declared != null && named) {
                problems.add(type.getSimpleName() + " is shared and named as not shared");
            }
            if (declared == null) {
                continue;
            }
            shared.add(type.getSimpleName());

            // 2. A declaration names its own model.
            if (!declared.modelId().equals(modelId)) {
                problems.add(type.getSimpleName() + " declares " + declared.modelId() + ", not its own id " + modelId);
            }

            // 3. Its title is kept in clear: no secret display field, and an override only when it was reviewed.
            for (Field<?, ?> field : schema.getDisplayFields()) {
                if (field.isSecret() || field.isEncrypted()) {
                    problems.add(type.getSimpleName() + " is titled by the secret field " + field.getName());
                }
            }
            if (overridesTitle(type) && !REVIEWED_TITLES.containsKey(type)) {
                problems.add(type.getSimpleName() + " overrides its title, which the master keeps in clear: read it"
                    + " and review it here");
            }

            // 4. The stored generation it carries is born by a migration of this module.
            if (births.indexOf("data:birth " + modelId + " generation@1") < 0) {
                problems.add(type.getSimpleName() + " is shared, but no migration births " + modelId
                    + "'s generation: add StoredGenerations.birthStep to the open migration");
            }
        }

        // 5. Nothing named here is gone or unknown.
        for (Class<? extends Model> type : NOT_SHARED.keySet()) {
            if (!found.contains(type.getSimpleName())) {
                problems.add(type.getSimpleName() + " is named as not shared but is no model of the package");
            }
        }
        assertThat(problems).as("the split of shared and not shared kinds").isEmpty();
        assertThat(shared).as("the shared kinds").hasSize(40);
    }

    /** @return every concrete model class of the model package, from its sources */
    @SuppressWarnings("unchecked")
    private static List<Class<? extends Model>> modelClasses() throws IOException, ClassNotFoundException {
        List<Class<? extends Model>> types = new ArrayList<>();
        try (Stream<Path> files = Files.list(MODELS)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java")) {
                    continue;
                }
                Class<?> type = Class.forName("be.elevenways.hohenheim.model." + name.substring(0, name.length() - 5));
                if (Model.class.isAssignableFrom(type) && !Modifier.isAbstract(type.getModifiers())) {
                    types.add((Class<? extends Model>) type);
                }
            }
        }
        return types;
    }

    /** @return whether the model declares its own title */
    private static boolean overridesTitle(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals("resolveDisplayTitle") || method.getName().equals("resolveDisplayTitles")) {
                return true;
            }
        }
        return false;
    }
}
