package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ControllerIdentityModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.incus.ControllerPresence;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusEndpoint;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.incus.IncusReaper;
import be.elevenways.hohenheim.server.incus.IncusTrust;
import be.elevenways.hohenheim.server.task.ReapIncusControllers;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.protoblast.server.process.ProcessOutcome;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.protoblast.server.process.Termination;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The enrollment facts for a GENUINELY REMOTE Incus host, supplied out of band by an
 * operator in {@code ~/.config/hohenheim-livehost/incus.properties}; absent file =
 * every Incus live test skips, the {@link LiveRemoteHost} pattern exactly.
 *
 * Expected keys: {@code url} (https://host:8443), {@code fingerprint} (the sha256 hex
 * {@code incus info} prints ON THE HOST -- copied from a scan it would be decorative),
 * and {@code trust_target} ([user@]host for ssh) so a test can MINT a one-use trust
 * token the way an operator would ({@code incus config trust add}) and remove the
 * enrolled certificate afterwards. Unlike the ssh fixture no identity is supplied:
 * minting the client certificate is the PRODUCT's own job (IncusTrust), and the token
 * is how it lands on the daemon.
 */
public final class LiveIncusHost {

    /** Wall-clock budget for one remote command; a hang must become a named failure. */
    private static final long DEFAULT_TIMEOUT_SECONDS = 180;

    /** The ssh port a {@code trust_target} without one connects to. */
    private static final int SSH_PORT = 22;

    /** Where an operator records the enrollment facts. */
    public static final Path CONFIG = Path.of(System.getProperty("user.home"),
        ".config", "hohenheim-livehost", "incus.properties");

    private final String url;
    private final String fingerprint;
    private final String trustTarget;

    /**
     * Every authorized_keys line this fixture installed on the remote, so a tearDown can
     * hand back what it took without every test class tracking it by hand.
     *
     * AIDEV-NOTE: these are WORKING ROOT CREDENTIALS on a real machine. Enrollment
     * installs one for every Incus live test now that the kernel-truth lane is part of the
     * ceremony, so "the class that forgot its cleanup" would be root access accumulating,
     * silently. {@link #releaseAuthorizedKeys} is a no-op when nothing was installed, so
     * calling it unconditionally in a tearDown is always right.
     */
    private final List<String> installedKeys = new ArrayList<>();

    /**
     * Every client certificate this fixture enrolled on the daemon, for the same reason
     * {@link #installedKeys} exists.
     *
     * AIDEV-NOTE: an enrolled certificate is a WORKING ADMIN CREDENTIAL on a real daemon,
     * and the obvious shape leaks it. Callers used to hold the fingerprint in a static
     * field assigned by the RETURN of {@link #enrollThroughProduct} -- so an enrollment
     * that aborted anywhere after the trust step (a preflight assumption, a network blip)
     * never assigned it and the teardown removed nothing. Observed 2026-08-07: two stale
     * {@code hohenheim-live-liveness} certificates on daystrom from two aborted runs.
     * Tracking it HERE makes {@link #releaseTrustEntries} unconditionally correct.
     */
    private final List<String> enrolledCertificates = new ArrayList<>();

    /**
     * Every daemon this fixture enrolled through the FULL ceremony, with the shared
     * object names THIS controller mints there -- captured at enroll time (inside the
     * caller's Db scope, where the identity token resolves) so the teardown release
     * needs no scope of its own.
     */
    private final List<SharedObjects> mintedSharedObjects = new ArrayList<>();

    private LiveIncusHost(String url, String fingerprint, String trustTarget) {
        this.url = url;
        this.fingerprint = fingerprint;
        this.trustTarget = trustTarget;
    }

    /** The configured host, or null when no operator enrolled one on this machine. */
    public static LiveIncusHost configured() {
        return fromConfig("url", "fingerprint", "trust_target", true);
    }

    /**
     * The SECOND, deliberately twinned host for cross-host tests ({@code url_b} /
     * {@code fingerprint_b} / {@code trust_target_b} in the same file), or null when
     * the operator enrolled only one -- single-host live tests keep running unchanged
     * and cross-host ones skip.
     */
    public static LiveIncusHost configuredSecondary() {
        return fromConfig("url_b", "fingerprint_b", "trust_target_b", false);
    }

    /**
     * THE gate of every Incus live class: the configured primary host, with its daemon
     * endpoint and its ssh lane both answering, or a skip naming {@code incus-host}.
     *
     * AIDEV-NOTE: gating on the config file alone made a host that was merely DOWN fail
     * every class in setUp (host_key_scan_failed "No route to host") instead of reporting
     * one named skip each. A run declaring {@code hohenheim.live.require=incus-host} still
     * FAILS here, which is the point of declaring it.
     */
    public static @NonNull LiveIncusHost requirePrimary() {
        return requireReachable(configured(), "no live incus host enrolled at " + CONFIG,
            LiveLane.Need.INCUS_HOST, true);
    }

    /** {@link #requirePrimary} for the SECOND, twinned host ({@code url_b}). */
    public static @NonNull LiveIncusHost requireSecondary() {
        return requireReachable(configuredSecondary(),
            "no SECOND live incus host (url_b) enrolled at " + CONFIG,
            LiveLane.Need.INCUS_HOST, true);
    }

    /**
     * The primary host as a plain ssh host (the Docker-over-ssh twin): only its ssh lane
     * must answer, and a miss is a {@code remote-host} skip.
     */
    public static @NonNull LiveIncusHost requireSshLane() {
        return requireReachable(configured(), "no live host enrolled at " + CONFIG,
            LiveLane.Need.REMOTE_HOST, false);
    }

    private static @NonNull LiveIncusHost requireReachable(@Nullable LiveIncusHost host,
                                                           @NonNull String absent,
                                                           LiveLane.Need need,
                                                           boolean daemon) {
        LiveLane.require(need, host != null, absent);
        if (daemon) {
            IncusEndpoint endpoint = IncusEndpoint.parse(host.url);
            LiveLane.requireReachable(need, "incus daemon " + host.url,
                endpoint.host(), endpoint.port());
        }
        HostKeys.Target ssh = HostKeys.parseTarget(host.trustTarget);
        LiveLane.requireReachable(need, "ssh lane " + host.trustTarget,
            ssh.host(), ssh.port() > 0 ? ssh.port() : SSH_PORT);
        return host;
    }

    private static LiveIncusHost fromConfig(String urlKey, String fingerprintKey,
                                            String targetKey, boolean required) {
        if (!Files.isReadable(CONFIG)) {
            return null;
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(CONFIG)) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable " + CONFIG, e);
        }
        if (!required && (properties.getProperty(urlKey) == null
                || properties.getProperty(urlKey).isBlank())) {
            return null;
        }
        try {
            return new LiveIncusHost(require(properties, urlKey),
                require(properties, fingerprintKey), require(properties, targetKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Incomplete " + CONFIG, e);
        }
    }

    public String url() {
        return this.url;
    }

    /** The fingerprint an operator read ON THE HOST ITSELF, never from a scan. */
    public String fingerprint() {
        return this.fingerprint;
    }

    /**
     * Enrol the host record at the BOTTOM of the trust ceremony: no pin, unconfirmed,
     * unadmitted, no client identity yet (the product mints that itself).
     */
    public Row enrol(String name) {
        ServerModel model = Models.get(ServerModel.class);
        Row row = model.findByName(name);
        if (row == null) {
            row = model.createEmptyRow();
            row.set(ServerModel.NAME, name);
        }
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        row.set(ServerModel.INCUS_URL, this.url);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.SSH_TARGET, null);
        row.set(ServerModel.HOST_KEY, null);
        row.set(ServerModel.HOST_KEY_FINGERPRINT, null);
        row.set(ServerModel.HOST_KEY_OFFERED, null);
        row.set(ServerModel.HOST_KEY_VERIFIED, false);
        row.set(ServerModel.INCUS_SERVER_CERT, null);
        row.set(ServerModel.INCUS_SERVER_CERT_FINGERPRINT, null);
        row.set(ServerModel.INCUS_SERVER_CERT_OFFERED, null);
        row.set(ServerModel.INCUS_SERVER_CERT_VERIFIED, false);
        row.set(ServerModel.INCUS_CLIENT_CERT, null);
        row.set(ServerModel.INCUS_CLIENT_KEY, null);
        row.set(ServerModel.PREFLIGHT_OK, false);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        row.set(ServerModel.IDENTITY_PRIVATE_KEY, null);
        row.set(ServerModel.IDENTITY_PUBLIC_KEY, null);
        model.save(row);
        HostFixtures.acknowledgePosture(row);
        return model.findByName(name);
    }

    /**
     * Mint a ONE-USE trust token on the host, the operator act
     * ({@code incus config trust add <name>}); the ssh lane here is the test's stand-in
     * for the host's own console, the same trust root the fingerprint came from.
     */
    public String mintTrustToken(String clientName) throws IOException {
        String output = ssh(List.of("incus", "config", "trust", "add", clientName, "--quiet"));
        String token = output.trim();
        int lastLine = token.lastIndexOf('\n');
        if (lastLine >= 0) {
            token = token.substring(lastLine + 1).trim();
        }
        if (token.isEmpty()) {
            throw new IOException("incus config trust add printed no token: " + output);
        }
        return token;
    }

    /** Remove an enrolled client certificate by its sha256-hex fingerprint (cleanup). */
    public void removeTrustEntry(String certificateFingerprint) throws IOException {
        ssh(List.of("incus", "config", "trust", "remove", certificateFingerprint));
    }

    /**
     * Give back every client certificate this fixture enrolled, however the run ended.
     *
     * @return one printable outcome per certificate, or "nothing enrolled"
     */
    public String releaseTrustEntries() {
        if (this.enrolledCertificates.isEmpty()) {
            return "nothing enrolled";
        }
        List<String> outcomes = new ArrayList<>();
        for (String fingerprint : this.enrolledCertificates) {
            String shortForm = fingerprint.length() > 12
                ? fingerprint.substring(0, 12) : fingerprint;
            try {
                removeTrustEntry(fingerprint);
                outcomes.add(shortForm + " -> removed");
            } catch (IOException failed) {
                outcomes.add(shortForm + " -> NOT removed: " + failed.getMessage());
            }
        }
        this.enrolledCertificates.clear();
        return String.join(", ", outcomes);
    }

    /**
     * The FULL product enrollment ceremony for a host that will accept TENANT workloads:
     * daemon trust, the ssh admin kernel-truth lane, preflight, admission -- with no
     * fixture shortcut writing any verdict. Call inside a {@code Db.run} scope.
     *
     * AIDEV-NOTE: the ssh lane is part of this ceremony since kernel-truth verification
     * became an admission REQUIREMENT. It is not fixture convenience: an
     * https Incus host with a tenant-accepting posture and no lane now FAILS preflight by
     * design, so a fixture that skipped the lane would turn every Incus live test into a
     * silent skip -- the exact reports-success shape this ceremony exists to kill.
     *
     * @return the enrolled client certificate's fingerprint (for trust cleanup)
     */
    public String enrollThroughProduct(String hostName, String clientName) {
        String fingerprint = enrollDaemonTrustThroughProduct(hostName, clientName);
        enrollSshLaneThroughProduct(hostName);
        admitThroughProduct(hostName);
        sweepSharedObjects(hostName);
        return fingerprint;
    }

    /**
     * Keep a shared TEST daemon from accumulating departed controllers' ACLs and bridges:
     * run the PRODUCT's own reap sweep now (any stamped garbage older than the grace goes).
     * THIS controller's names are remembered at trust enrollment ({@link #registerSharedObjects}).
     *
     * AIDEV-NOTE: the sweep is the SANCTIONED host-wide path -- three independent facts
     * (foreign token, empty used_by re-read at delete time, stamped AND expired) must
     * all agree before it removes anything, so a concurrent fork's fresh stamp or a
     * referenced ACL refuses it. It is exactly what a production controller's 15-minute
     * schedule runs; test daemons have no long-lived controller, so this is the only
     * place that schedule's job ever happens for them. NEVER call
     * {@code reapIncludingUnstamped} here: it once emptied daystrom of 72 ACLs and 9
     * bridges, including objects other live forks were about to reference.
     */
    private void sweepSharedObjects(String hostName) {
        Row host = Models.get(ServerModel.class).findByName(hostName);
        ReapIncusControllers.HostOutcome outcome = ReapIncusControllers.sweepHost(host);
        System.out.println("=== enroll sweep on " + hostName + ": "
            + (outcome.reachable()
                ? IncusReaper.tally(outcome.plan()) + " removed=" + outcome.removed()
                    + " refused=" + outcome.refused()
                : "UNREACHABLE " + outcome.errors()));
    }

    /**
     * Remember the names THIS controller mints on the daemon, captured in Db scope at trust enrollment, so
     * {@link #releaseControllerSharedObjects} hands them back for a class that enrolled the daemon half only too.
     */
    private void registerSharedObjects(String hostName) {
        this.mintedSharedObjects.add(new SharedObjects(
            new ServerService().incusClientFor(hostName),
            ControllerScope.scoped(""),
            IncusNetworkPolicy.aclName(),
            IncusNetworkPolicy.extraNetwork(),
            ControllerPresence.aclName()));
    }

    /**
     * One enrolled daemon's per-controller names, captured in Db scope: the prefix every object this controller
     * mints there starts with, and its three shared objects.
     */
    private record SharedObjects(IncusClient incus, String prefix, String isolationAcl, String bridge,
                                 String presence) {}

    /**
     * Hand back EVERYTHING this controller left on every enrolled daemon: first any instance and custom volume a
     * failed step or a refused cleanup left behind (named by this controller's own prefix, so another fork's and an
     * operator's objects are never candidates), then the shared objects through the product's own
     * {@link IncusReaper#releaseOwn} (bridge and ACL when unreferenced, the presence marker last). Call it in
     * tearDown BEFORE the trust entry is removed; after that the client cannot authenticate.
     *
     * AIDEV-NOTE: the leftover half exists because a tearDown's product destroy can be refused: run 1862's
     * WorkspaceIncusLiveTest destroy met instance_operation_in_progress and the workspace, its ACL and its marker
     * stayed on nightstrom. Every Incus live class already calls this one method, so the guarantee lives here.
     *
     * @return one printable outcome per daemon, or "nothing enrolled"
     */
    public String releaseControllerSharedObjects() {
        if (this.mintedSharedObjects.isEmpty()) {
            return "nothing enrolled";
        }
        List<String> outcomes = new ArrayList<>();
        for (SharedObjects shared : this.mintedSharedObjects) {
            List<String> parts = new ArrayList<>(removeLeftovers(shared));
            IncusReaper.Reaped released = IncusReaper.releaseOwn(shared.incus(), shared.isolationAcl(),
                shared.bridge(), shared.presence());
            parts.add("released " + released.removed());
            if (!released.refused().isEmpty()) {
                parts.add("NOT released " + released.refused());
            }
            outcomes.add(String.join(", ", parts));
        }
        this.mintedSharedObjects.clear();
        return String.join("; ", outcomes);
    }

    /** Force-removes this controller's instances and custom volumes left on one daemon. */
    private static List<String> removeLeftovers(SharedObjects shared) {
        List<String> parts = new ArrayList<>();
        IncusClient incus = shared.incus();
        try {
            for (Map<String, Object> instance : incus.instances()) {
                String name = String.valueOf(instance.get("name"));
                if (!name.startsWith(shared.prefix())) {
                    continue;
                }
                try {
                    incus.changeState(name, "stop", 10, true);
                } catch (IOException alreadyStopped) {
                    // the delete below is the authority
                }
                incus.deleteInstance(name);
                parts.add("left-over instance " + name + " removed");
            }
            String pool = IncusInstanceRuntime.managedPoolNameOf(incus);
            for (Map<String, Object> volume : incus.customVolumes(pool)) {
                String name = String.valueOf(volume.get("name"));
                if (name.startsWith(shared.prefix())) {
                    incus.deleteCustomVolume(pool, name);
                    parts.add("left-over volume " + name + " removed");
                }
            }
        } catch (IOException failed) {
            parts.add("left-overs NOT removed: " + failed.getMessage());
        }
        return parts;
    }

    /** The live identities this JVM pinned, so a class's second enrollment keeps the identity of its first. */
    private static final Set<String> PINNED_IDENTITIES = ConcurrentHashMap.newKeySet();

    /**
     * Pin the current database's controller identity to a token derived from {@code seed} (the class's first trust
     * client name), so every run of one live class names its daemon objects with the SAME prefix and a later run can
     * find what a killed one left.
     *
     * AIDEV-NOTE: a deliberate test-fixture exception to ControllerIdentity's "no fixed token" rule, scoped to the
     * live classes' own fresh databases: one token per class, never shared by two classes (parallel forks would sweep
     * each other's live objects) and never an operator controller's (a random token's chance of matching one is
     * ~2^-41). A database whose identity this JVM already pinned keeps it, so a class enrolling two daemons names both
     * with one prefix.
     */
    private static void pinLiveIdentity(String seed) {
        if (PINNED_IDENTITIES.contains(ControllerIdentity.token())) {
            return;
        }
        String token = liveToken(seed);
        Models.get(ControllerIdentityModel.class).find().where(ControllerIdentityModel.SINGLETON.eq(1))
            .assign(ControllerIdentityModel.TOKEN, token)
            .bypassBehaviours()
            .updateAll();
        ControllerIdentity.forgetForTest();
        if (!token.equals(ControllerIdentity.token())) {
            throw new IllegalStateException("The live identity " + token + " did not take");
        }
        PINNED_IDENTITIES.add(token);
    }

    /** @return a controller token (lowercase alphanumeric, a letter first) derived from {@code seed} */
    static String liveToken(String seed) {
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(("hohenheim-live:" + seed)
                .getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        StringBuilder token = new StringBuilder(ControllerIdentity.TOKEN_LENGTH);
        token.append(alphabet.charAt(10 + Math.floorMod(digest[0], 26)));
        for (int i = 1; i < ControllerIdentity.TOKEN_LENGTH; i++) {
            token.append(alphabet.charAt(Math.floorMod(digest[i], alphabet.length())));
        }
        return token.toString();
    }

    /**
     * Remove, over the host's own CLI, what an earlier run of THIS live class left on this daemon: every instance
     * and custom volume named with this controller's prefix, its isolation and presence ACLs and its bridge, and a
     * trust entry or pending trust token named {@code clientName}. A run killed mid-test (the JVM a timeout ended)
     * never reaches its tearDown, so the next run cleans up before it starts.
     *
     * AIDEV-NOTE: names only ever match this class's pinned identity and its own client name, so an operator's
     * controller (u548fz9h, its alpvm instance) and another class's objects are never candidates. Run inside the
     * class's Db scope, after {@link #pinLiveIdentity}.
     *
     * @return what was removed, or "nothing left"
     */
    private String sweepOwnLeftovers(String clientName) {
        String prefix = ControllerScope.scoped("");
        List<String> removed = new ArrayList<>();
        try {
            for (String name : csvColumn(ssh(List.of("incus", "list", "--format", "csv", "-c", "n")), 0)) {
                if (name.startsWith(prefix)) {
                    ssh(List.of("incus", "delete", "--force", name));
                    removed.add("instance " + name);
                }
            }
            for (String pool : csvColumn(ssh(List.of("incus", "storage", "list", "--format", "csv", "-c", "n")),
                    0)) {
                for (String line : ssh(List.of("incus", "storage", "volume", "list", pool, "--format", "csv",
                        "-c", "tn")).split("\n")) {
                    String[] cells = line.trim().split(",", 2);
                    if (cells.length == 2 && "custom".equals(cells[0]) && cells[1].startsWith(prefix)) {
                        ssh(List.of("incus", "storage", "volume", "delete", pool, cells[1]));
                        removed.add("volume " + pool + "/" + cells[1]);
                    }
                }
            }
            List<String> acls = csvColumn(ssh(List.of("incus", "network", "acl", "list", "--format", "csv")), 0);
            for (String acl : List.of(IncusNetworkPolicy.aclName(), ControllerPresence.aclName())) {
                if (acls.contains(acl)) {
                    ssh(List.of("incus", "network", "acl", "delete", acl));
                    removed.add("acl " + acl);
                }
            }
            String bridge = IncusNetworkPolicy.extraNetwork();
            if (csvColumn(ssh(List.of("incus", "network", "list", "--format", "csv", "-c", "n")), 0)
                    .contains(bridge)) {
                ssh(List.of("incus", "network", "delete", bridge));
                removed.add("network " + bridge);
            }
            for (String line : ssh(List.of("incus", "config", "trust", "list", "--format", "csv", "-c", "nf"))
                    .split("\n")) {
                String[] cells = line.trim().split(",", 2);
                if (cells.length == 2 && clientName.equals(cells[0])) {
                    ssh(List.of("incus", "config", "trust", "remove", cells[1]));
                    removed.add("trust " + clientName);
                }
            }
            for (String token : csvColumn(ssh(List.of("incus", "config", "trust", "list-tokens", "--format", "csv",
                    "-c", "n")), 0)) {
                if (clientName.equals(token)) {
                    ssh(List.of("incus", "config", "trust", "revoke-token", clientName));
                    removed.add("trust token " + clientName);
                }
            }
        } catch (IOException failed) {
            throw new UncheckedIOException("The pre-run sweep of " + prefix + "* on " + this.trustTarget
                + " failed after removing " + removed, failed);
        }
        return removed.isEmpty() ? "nothing left" : String.join(", ", removed);
    }

    /** @return one column of csv output, one value per non-blank line */
    private static List<String> csvColumn(String csv, int column) {
        List<String> values = new ArrayList<>();
        for (String line : csv.split("\n")) {
            String[] cells = line.trim().split(",", -1);
            if (cells.length > column && !cells[column].isBlank()) {
                values.add(cells[column].trim());
            }
        }
        return values;
    }

    /**
     * The DAEMON half of the ceremony only -- identity, pin, confirm, token enrollment --
     * leaving the host unpreflighted, unadmitted and without a kernel-truth lane. First pins this database's
     * controller identity to the live class's own ({@link #pinLiveIdentity}) and removes what an earlier run of the
     * same class left on this daemon ({@link #sweepOwnLeftovers}).
     *
     * @return the enrolled client certificate's fingerprint (for trust cleanup)
     */
    public String enrollDaemonTrustThroughProduct(String hostName, String clientName) {
        pinLiveIdentity(clientName);
        System.out.println("=== pre-run sweep on " + hostName + ": " + sweepOwnLeftovers(clientName));
        Row host = enrol(hostName);
        IncusTrust.ensureIdentity(host);
        String fingerprint = IncusTrust.fingerprintOf(
            host.get(HostTrustSlot.INCUS_TLS.clientPublic()));
        IncusTrust.scanAndPin(host);
        Row pinned = Models.get(ServerModel.class).findByName(hostName);
        IncusTrust.confirm(pinned);
        try {
            IncusTrust.enrollWithToken(Models.get(ServerModel.class).findByName(hostName),
                mintTrustToken(clientName));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.enrolledCertificates.add(fingerprint);
        registerSharedObjects(hostName);
        return fingerprint;
    }

    /** Run the real preflight and take the admit transition through the real gate. */
    public void admitThroughProduct(String hostName) {
        HostPreflight.Report report = HostPreflight.runAndStore(hostName);
        // AIDEV-NOTE: PRINTED, not only carried in the assumption message. A failing
        // preflight aborts every test in the class, and JUnit's abort reason does not
        // reach the captured task output -- so the whole class reads as a silent skip
        // with no way to tell "no host enrolled" from "the host failed a required check".
        for (HostPreflight.Check check : report.checks()) {
            if (check.required() && check.failed()) {
                System.out.println("=== preflight REQUIRED check failed on " + hostName
                    + ": " + check.name() + " -- " + check.detail());
            }
        }
        LiveLane.require(LiveLane.Need.INCUS_HOST, report.passed(),
            "incus preflight did not pass: " + report.checks());
        Row ready = Models.get(ServerModel.class).findByName(hostName);
        HostAdmission.requireAdmittable(ready);
        ready.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        Models.get(ServerModel.class).save(ready);
    }

    /**
     * Walk the PRODUCT's ssh trust ceremony for this host's admin lane, doing only the
     * parts an operator does out of band: declare the target, install the product's
     * minted public key in the remote's authorized_keys, and compare the scanned
     * fingerprint against what the host itself reports before confirming it.
     *
     * AIDEV-NOTE: the comparison is genuinely out of band with respect to the code under
     * test. The product scans with {@code ssh-keyscan} FROM THE CONTROLLER; this reads
     * {@code ssh-keygen -lf} on the host's own filesystem over the fixture's already
     * authenticated operator session -- the same trust root {@code incus info}'s
     * certificate fingerprint and the trust token come from. Two different channels
     * agreeing is the property the confirm step is supposed to record.
     *
     * @return the ssh host-key fingerprint both channels agreed on
     */
    public String enrollSshLaneThroughProduct(String hostName) {
        ServerModel model = Models.get(ServerModel.class);
        Row host = model.findByName(hostName);
        host.set(ServerModel.SSH_TARGET, this.trustTarget);
        model.save(host);

        Row withTarget = model.findByName(hostName);
        HostKeys.ensureIdentity(withTarget);
        Row withIdentity = model.findByName(hostName);
        try {
            authorizeKey(withIdentity.get(HostTrustSlot.SSH.clientPublic()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        HostKeys.ScanResult scan = HostKeys.scanAndPin(withIdentity);
        String reported;
        try {
            reported = hostSshFingerprint();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        LiveLane.require(LiveLane.Need.INCUS_HOST, scan.fingerprint().equals(reported),
            "the scanned ssh host key (" + scan.fingerprint() + ") is not what "
            + this.trustTarget + " reports for itself (" + reported + ")");
        HostKeys.confirm(model.findByName(hostName));
        return reported;
    }

    /**
     * The operator act: put one public key in the remote account's authorized_keys.
     *
     * AIDEV-NOTE: every line carrying the same COMMENT is swept first. The product mints a
     * fresh key per enrollment, so a run that dies between installing one and removing it
     * leaves a WORKING root credential behind, and the next run adds another -- observed
     * 2026-08-05, two stale {@code hohenheim-live-incus-kernel} keys on daystrom from
     * runs killed mid-journey. Sweeping by comment makes the install self-healing instead
     * of accumulating root access on a real machine.
     *
     * AIDEV-NOTE: the append AND the sweep both run under {@code flock} on
     * {@code ~/.ssh/authorized_keys.lock}. Twelve Incus live classes each install and
     * sweep a key and browserTestIsolated runs up to 4 parallel forks against the SAME
     * host, so an unlocked sweep (grep to .tmp, mv back) that read the file before a
     * sibling's append silently discarded that append at the mv. The product lane runs
     * IdentitiesOnly with the per-host key, so a lost line surfaces as exactly
     * "Permission denied (publickey)". The same race could drop the OPERATOR key line
     * and lock the fixture out of the host entirely.
     *
     * AIDEV-NOTE: the flock is NECESSARY but not sufficient. The sweep is keyed on the
     * COMMENT, and the product mints it as {@code hohenheim-<host record name>}
     * (HostKeys.rotateIdentity) -- per-fork SQLite databases mean two forks enrolling
     * the SAME record name mint DIFFERENT keys carrying the SAME comment, so fork B's
     * enroll-time sweep here (or its teardown's releaseAuthorizedKeys) legitimately
     * deletes fork A's live key under the lock. That is a semantic collision, not a
     * race: the invariant is that every live class uses a UNIQUE host record name
     * (observed 2026-08-10: IncusVmLiveTest and IncusInstanceRuntimeLiveTest shared
     * "live-incus"; the runtime class's @BeforeAll enroll cut the VM journey off
     * mid-deploy with "Permission denied (publickey)" on the nft kernel-truth lane).
     */
    public void authorizeKey(String publicKey) throws IOException {
        String line = publicKey.trim().replace("'", "");
        this.installedKeys.add(line);
        deauthorizeComment(commentOf(line));
        ssh(List.of("sh", "-c", "'mkdir -p ~/.ssh && chmod 700 ~/.ssh &&"
            + " touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys &&"
            + " flock ~/.ssh/authorized_keys.lock sh -c"
            + " \"echo \\\"" + line + "\\\" >> ~/.ssh/authorized_keys\"'"));
    }

    /**
     * Give back every authorized_keys line this fixture installed, whoever installed it.
     *
     * @return one printable outcome per key, or "nothing installed"
     */
    public String releaseAuthorizedKeys() {
        if (this.installedKeys.isEmpty()) {
            return "nothing installed";
        }
        List<String> outcomes = new ArrayList<>();
        for (String key : this.installedKeys) {
            outcomes.add(commentOf(key) + " -> " + deauthorizeKey(key));
        }
        this.installedKeys.clear();
        return String.join(", ", outcomes);
    }

    /**
     * Remove every authorized_keys line this fixture installed under one comment.
     *
     * @return what the remote reports afterwards: "removed", "STILL PRESENT", or the failure
     */
    public String deauthorizeKey(String publicKey) {
        return deauthorizeComment(commentOf(publicKey.trim().replace("'", "")));
    }

    /** @return "removed", "STILL PRESENT (n copies)", or the failure text */
    public String deauthorizeComment(String comment) {
        if (comment.isBlank()) {
            return "no comment to sweep";
        }
        try {
            // No && between grep and mv: grep exits 1 when it filters everything out, and
            // a cleanup that silently skips its own mv is how the stale keys accumulated.
            // The whole read-filter-replace runs under the same flock authorizeKey's
            // append takes, so a concurrent fork's install can never be discarded here.
            ssh(List.of("sh", "-c", "'flock ~/.ssh/authorized_keys.lock sh -c"
                + " \"grep -v \\\" " + comment + "\\$\\\" ~/.ssh/authorized_keys"
                + " > ~/.ssh/authorized_keys.tmp; mv ~/.ssh/authorized_keys.tmp"
                + " ~/.ssh/authorized_keys\"'"));
            String remaining = ssh(List.of("sh", "-c", "'grep -c \" " + comment
                + "$\" ~/.ssh/authorized_keys || true'")).trim();
            return "0".equals(remaining) ? "removed"
                : "STILL PRESENT (" + remaining + " copies)";
        } catch (IOException failed) {
            return "NOT removed: " + failed.getMessage();
        }
    }

    /** The trailing comment of a public key line ({@code ssh-ed25519 AAAA... comment}). */
    private static String commentOf(String publicKeyLine) {
        String[] fields = publicKeyLine.trim().split("\\s+");
        return fields.length >= 3 ? fields[fields.length - 1] : "";
    }

    /** What the host reports as its OWN ssh host key digest, read on the host itself. */
    public String hostSshFingerprint() throws IOException {
        String output = ssh(List.of("sh", "-c",
            "'ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub'")).trim();
        for (String token : output.split("\\s+")) {
            if (token.startsWith("SHA256:")) {
                return token;
            }
        }
        throw new IOException("ssh-keygen -lf printed no SHA256 digest: " + output);
    }

    /** Run one shell command INSIDE the instance, over the host's own CLI. */
    public String exec(String handle, String command) throws IOException {
        return ssh(List.of("incus", "exec", handle, "--", "sh", "-c",
            HostShell.quote(command))).trim();
    }

    /** Force-remove one instance over the host's own CLI (cleanup; absent is fine). */
    public void forceDelete(String handle) {
        try {
            ssh(List.of("incus", "delete", "--force", handle));
        } catch (IOException ignored) {
            // already gone
        }
    }

    /** Run one command on the HOST itself (reachability probes from outside a container). */
    public String hostCommand(String... command) throws IOException {
        return ssh(List.of(command)).trim();
    }

    /**
     * The same, with an explicit budget. Exists so a test can PROVE the timeout without
     * waiting out the default one.
     */
    public String hostCommand(long timeoutSeconds, String... command) throws IOException {
        return ssh(List.of(command), timeoutSeconds).trim();
    }

    /** Raw REST truth over the host's own CLI ({@code incus query <path>}). */
    public String query(String path) throws IOException {
        return ssh(List.of("incus", "query", path)).trim();
    }

    /** The daemon-side truth of one instance, read over the host's own CLI. */
    public String instanceInfoOrError(String handle) throws IOException {
        try {
            return ssh(List.of("incus", "info", handle));
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String ssh(List<String> remoteCommand) throws IOException {
        return ssh(remoteCommand, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Run one command over the operator ssh lane, bounded by a real wall clock.
     *
     * AIDEV-NOTE: Subprocess drains both pipes on its own threads and enforces the budget on the whole process
     * tree, because the obvious shape is a trap this fixture already fell into: reading stdout on the calling
     * thread blocks until the remote closes it, so a command that keeps its channel open never reaches the
     * timeout and the "timeout" is decoration. Observed 2026-08-05: one such call sat until Gradle killed the
     * whole task at 30 minutes and reported the test SKIPPED -- a hang that reads as a green-ish run. Draining
     * sequentially is the second half of the same trap: a child that fills the stderr pipe while nobody reads
     * it deadlocks against a caller blocked on stdout.
     *
     * @throws IOException naming the command and the budget when the remote outlives it
     */
    private String ssh(List<String> remoteCommand, long timeoutSeconds) throws IOException {
        ArrayList<String> argv = new ArrayList<>(List.of("ssh",
            "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
            // -n: never let the remote read this JVM's stdin, which is another way for a
            // command to sit forever waiting on input nobody is going to type.
            "-n", "--", this.trustTarget));
        argv.addAll(remoteCommand);
        ProcessOutcome outcome = Subprocess.of(argv).timeout(Duration.ofSeconds(timeoutSeconds)).runChecked();
        if (outcome.termination() == Termination.TIMED_OUT) {
            throw new IOException("ssh " + remoteCommand + " to " + this.trustTarget
                + " outlived its " + timeoutSeconds + "s budget and was killed");
        }
        if (!outcome.succeeded()) {
            throw new IOException("ssh " + remoteCommand + " failed (exit "
                + outcome.exitCode() + "): " + outcome.failureText());
        }
        if (outcome.stdout().truncated()) {
            throw new IOException("ssh " + remoteCommand + " printed " + outcome.stdout().totalBytes()
                + " bytes, more than the output Subprocess keeps");
        }
        return outcome.stdout().text();
    }

    private static String require(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException(CONFIG + " has no '" + key + "'");
        }
        return value.trim();
    }
}
