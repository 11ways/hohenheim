package be.elevenways.hohenheim.server.build;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.common.dry.Dry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The buildpack builder kind: a sandboxed nixpacks DETECTION phase emits a Dockerfile
 * into the build context, after which the Dockerfile builder runs unchanged -- exactly
 * the path the old refusal named. One operation record, one sandbox, one log, one
 * digest pin; the only addition is the quota'd detection phase.
 *
 * <p>Why nixpacks and not Cloud Native Buildpacks: the CNB lifecycle wants to EXPORT the
 * image itself (to a daemon or a registry; OCI-layout export is experimental), which
 * would mean a second artifact path beside the kaniko tar -- its own digest pinning, its
 * own reproducibility argument, its own read-back cap. Nixpacks' detector is one STATIC
 * binary whose output is a Dockerfile, so everything downstream of detection is the
 * already-proven lane and the only thing this class owns is the detection itself.</p>
 *
 * AIDEV-NOTE: nixpacks' exit codes are NOT a detection signal (measured on 1.41.0:
 * {@code plan} exits 0 printing an empty-phases plan for an undetectable repo, and
 * {@code build --out} exits 1 in a container but has been observed exiting 0 elsewhere)
 * -- the signature silent-success shape, plus environment-dependent variance. The
 * refusal below is therefore OURS and judged on the detector's OBSERVATIONS: no provider
 * AND no configured phases is a named refusal BEFORE any image build starts, never a
 * fall-through to some guessed default image.
 */
public final class NixpacksBuilder implements Builders {

    /** Where the emitted Dockerfile lands, relative to the build context. */
    static final String EMIT_DIR = ".nixpacks";
    static final String EMITTED_DOCKERFILE = EMIT_DIR + "/Dockerfile";

    /** Phase-internal paths: the staged binary and its inspection outputs. */
    private static final String TOOL_DIR = "/hohenheim-nixpacks";
    private static final String TOOL = TOOL_DIR + "/" + NixpacksDistribution.BINARY_NAME;
    private static final String PROVIDERS_OUT = TOOL_DIR + "/providers.txt";
    private static final String PLAN_OUT = TOOL_DIR + "/plan.json";

    private static final Pattern VALID_ARG_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Snapshot paths EXCLUDED from the emitted image, because they are wall-clock junk
     * that breaks digest stability -- each one MEASURED, not guessed, by diffing the
     * layers of two builds of an identical context. {@code npm i} writes a
     * timestamp-named debug log under /root/.npm/_logs; {@code nix-env} leaves a git
     * tarball-cache under /root/.cache whose pack files are named after a fetch-dependent
     * hash, stamps registration TIMES into /nix/var/nix/db/db.sqlite, and bzips
     * timestamped build logs under /nix/var/log/nix. Dropping the nix db means `nix`
     * itself stops working INSIDE the built image, which no nixpacks-launched workload
     * does at runtime; the /nix/store payload and the profile symlinks all remain.
     */
    private static final List<String> UNSTABLE_PATHS = List.of(
        "/root/.cache", "/root/.npm/_logs", "/nix/var/nix/db", "/nix/var/log/nix");

    private @Nullable String detection;

    @Override
    public @NonNull String kind() {
        return BuildOperationModel.KIND_NIXPACKS;
    }

    @Override
    public @Nullable String detection() {
        return this.detection;
    }

    @Override
    public @NonNull BuildPlan plan(@NonNull BuildRequest request, int buildId,
                                   @NonNull BuildLog log, @NonNull BuildSandbox sandbox)
            throws IOException {
        Path toolDir = NixpacksDistribution.ensureBinary();
        String detectorImage = detectorImage();
        Map<String, String> buildArgs = validatedArgs(request.buildArgs());

        // Stale output of a previous build in a reused checkout slot never leaks into
        // this detection; the phase re-emits from scratch.
        deleteRecursively(request.contextDir().resolve(EMIT_DIR));

        log.line("[hohenheim] nixpacks detection phase starting (nixpacks "
            + HohenheimSettings.VALUES.getValue(HohenheimSettings.Builds.NIXPACKS_VERSION)
            + " in " + detectorImage + ", no network)");
        BuildSandbox.PhaseOutcome outcome = sandbox.runPhase(buildId, "detect",
            new BuildSandbox.Phase(detectorImage,
                List.of("/bin/sh", "-c", detectScript(buildArgs)),
                buildArgs,
                DockerfileBuilder.CONTEXT_PATH,
                List.of(new BuildSandbox.Phase.StagedBinary(TOOL_DIR, toolDir,
                    List.of(NixpacksDistribution.BINARY_NAME))),
                List.of(PROVIDERS_OUT, PLAN_OUT),
                DockerfileBuilder.CONTEXT_PATH + "/" + EMIT_DIR),
            request.quota(), request.contextDir(), log);

        try {
            String providers = text(outcome.files().get(PROVIDERS_OUT));
            String planJson = text(outcome.files().get(PLAN_OUT));
            Map<String, Object> plan = planJson.isBlank() ? null : parsePlan(planJson);
            boolean hasPhases = plan != null
                && plan.get("phases") instanceof Map<?, ?> phases && !phases.isEmpty();

            // Undetectability is judged on what the detector SAW, never on the tool's
            // exit code: measured on 1.41.0, `plan` exits 0 on an undetectable repo while
            // `build` exits 1 in a container (and 0 elsewhere) -- neither is a signal.
            if (plan != null && providers.isBlank() && !hasPhases) {
                this.detection = detectionJson(providers, plan, null);
                throw new IOException("REFUSED to build: nixpacks detected no provider for"
                    + " this repository and no nixpacks.toml declares build phases. Nothing"
                    + " here is buildable without a Dockerfile -- add one and use the"
                    + " dockerfile builder, or make the repository detectable (see the"
                    + " recorded detection for what the detector saw).");
            }
            if (!outcome.succeeded()) {
                if (plan != null) {
                    this.detection = detectionJson(providers, plan, null);
                }
                throw new IOException("The nixpacks detection phase ended "
                    + outcome.ending() + " (exit " + outcome.exitCode()
                    + "); see the build log");
            }
            if (plan == null) {
                throw new IOException("The nixpacks detection phase produced no plan output");
            }
            Map<String, String> variables = planVariables(plan);

            unpackEmitted(outcome.collectedDirTar(), request.contextDir());
            Path dockerfile = request.contextDir().resolve(EMITTED_DOCKERFILE);
            if (!Files.isRegularFile(dockerfile)) {
                this.detection = detectionJson(providers, plan, null);
                throw new IOException("REFUSED to build: nixpacks reported provider(s) '"
                    + providers.strip() + "' but emitted no " + EMITTED_DOCKERFILE
                    + " -- an emit that silently produced nothing is a failure, not a"
                    + " build");
            }
            // The emit's helper script embeds a random UUID and would enter the image
            // through COPY, breaking digest stability; it is docker-cli-only anyway.
            Files.deleteIfExists(request.contextDir().resolve(EMIT_DIR + "/build.sh"));
            // AIDEV-NOTE: kaniko --reproducible does NOT normalize mtimes in COPY layers
            // (measured: touching one context file changes the config digest). Tenant
            // files keep stable mtimes because checkout slots are reused, but .nixpacks
            // is re-emitted with fresh timestamps on EVERY build -- so the entries THIS
            // builder injects are pinned to the epoch, or an unchanged repository would
            // build to a new digest every time and every reload would roll the site.
            normalizeTimestamps(request.contextDir().resolve(EMIT_DIR));

            String dockerfileText = Files.readString(dockerfile, StandardCharsets.UTF_8);
            this.detection = detectionJson(providers, plan, dockerfileText);
            log.line("[hohenheim] nixpacks detected '" + providers.strip()
                + "', emitted " + EMITTED_DOCKERFILE);

            BuildRequest derived = new BuildRequest(request.forModel(), request.forId(),
                request.builderKind(), request.contextDir(), EMITTED_DOCKERFILE,
                request.tag(), variables, request.sourceRef(), request.registry(),
                request.quota());
            BuildPlan imagePlan = new DockerfileBuilder().plan(derived, buildId, log, sandbox);
            return withStableSnapshots(imagePlan);
        } finally {
            BuildSandbox.deleteQuietly(outcome.collectedDirTar());
        }
    }

    /** The kaniko command with the measured digest-unstable paths excluded. */
    private static @NonNull BuildPlan withStableSnapshots(@NonNull BuildPlan plan) {
        List<String> command = new ArrayList<>(plan.command());
        for (String path : UNSTABLE_PATHS) {
            command.add("--ignore-path=" + path);
        }
        return new BuildPlan(plan.builderImage(), List.copyOf(command), plan.env(),
            plan.contextPath(), plan.artifactPath(), plan.stagedFiles());
    }

    /**
     * The detection phase's script. Build-arg VALUES never touch the command line: each
     * validated NAME is passed as a bare {@code --env NAME}, which nixpacks resolves from
     * the container environment the sandbox set.
     */
    private static @NonNull String detectScript(@NonNull Map<String, String> buildArgs) {
        StringBuilder envFlags = new StringBuilder();
        for (String name : buildArgs.keySet()) {
            envFlags.append(" --env ").append(name);
        }
        String context = DockerfileBuilder.CONTEXT_PATH;
        return "set -e\n"
            + TOOL + " detect " + context + " > " + PROVIDERS_OUT + "\n"
            + "echo \"[nixpacks] providers: $(cat " + PROVIDERS_OUT + ")\"\n"
            + TOOL + " plan " + context + envFlags + " > " + PLAN_OUT + "\n"
            + TOOL + " build " + context + " --no-cache --out " + context + envFlags + "\n";
    }

    private static @NonNull String detectorImage() {
        String configured = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Builds.DETECTOR_IMAGE);
        return configured == null || configured.isBlank() ? "alpine:3.21" : configured.trim();
    }

    /**
     * @throws IOException for a build-arg name that is not a valid environment name --
     *         the one thing that could smuggle shell into the phase script
     */
    private static @NonNull Map<String, String> validatedArgs(@NonNull Map<String, String> args)
            throws IOException {
        for (String name : args.keySet()) {
            if (!VALID_ARG_NAME.matcher(name).matches()) {
                throw new IOException("REFUSED to build: build argument name '" + name
                    + "' is not a valid environment variable name");
            }
        }
        return args;
    }

    private static @NonNull Map<String, Object> parsePlan(@NonNull String planJson)
            throws IOException {
        Object parsed;
        try {
            parsed = new Dry().parse(planJson);
        } catch (RuntimeException notJson) {
            throw new IOException("The nixpacks plan output is not parseable JSON: "
                + notJson.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IOException("The nixpacks plan output is not a JSON object");
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        map.forEach((key, value) -> plan.put(String.valueOf(key), value));
        return plan;
    }

    /** The plan's variables: the ARG values the emitted Dockerfile expects at build time. */
    private static @NonNull Map<String, String> planVariables(@NonNull Map<String, Object> plan) {
        Map<String, String> variables = new LinkedHashMap<>();
        if (plan.get("variables") instanceof Map<?, ?> map) {
            map.forEach((key, value) ->
                variables.put(String.valueOf(key), String.valueOf(value)));
        }
        return variables;
    }

    /** The first-class detection record: what was seen, what will be built. */
    private static @NonNull String detectionJson(@NonNull String providers,
                                                 @NonNull Map<String, Object> plan,
                                                 @Nullable String dockerfile) {
        Map<String, Object> detection = new LinkedHashMap<>();
        detection.put("tool", "nixpacks");
        detection.put("version", HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Builds.NIXPACKS_VERSION));
        detection.put("providers", providers.lines().map(String::strip)
            .filter(line -> !line.isEmpty()).toList());
        detection.put("plan", plan);
        detection.put("dockerfile", dockerfile);
        return Json.stringify(detection);
    }

    /**
     * Unpack the phase's emitted {@code .nixpacks} directory into the control-plane
     * context (docker's archive envelope roots entries at the directory basename, so the
     * tar extracts as {@code .nixpacks/...} directly).
     */
    private static void unpackEmitted(@Nullable Path dirTar, @NonNull Path contextDir)
            throws IOException {
        if (dirTar == null) {
            return;
        }
        try {
            Process process = new ProcessBuilder("tar", "-xf", dirTar.toString(),
                "-C", contextDir.toString()).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Unpacking the emitted nixpacks output timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Unpacking the emitted nixpacks output failed (tar exit "
                    + process.exitValue() + ")");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Unpacking the emitted nixpacks output was interrupted");
        }
    }

    /** Pin every entry under {@code root} (the dir included) to the epoch. */
    private static void normalizeTimestamps(@NonNull Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.toList()) {
                Files.setLastModifiedTime(path, FileTime.fromMillis(0));
            }
        }
    }

    private static @NonNull String text(byte @Nullable [] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(@NonNull Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort; the phase re-emits over leftovers anyway
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
