package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.incus.ControllerPresence;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.incus.IncusReaper;
import be.elevenways.hohenheim.server.incus.IncusTrust;
import be.elevenways.hohenheim.server.task.ReapIncusControllers;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /**
     * Pipe drains. Daemon threads: a test JVM must never be held open by a drain still
     * waiting on a remote that outlived the test.
     */
    private static final ExecutorService DRAINS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "live-incus-ssh-drain");
        thread.setDaemon(true);
        return thread;
    });

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
     * The SECOND, deliberately twinned host of a cross-host wave ({@code url_b} /
     * {@code fingerprint_b} / {@code trust_target_b} in the same file), or null when
     * the operator enrolled only one -- single-host live tests keep running unchanged
     * and cross-host ones skip.
     */
    public static LiveIncusHost configuredSecondary() {
        return fromConfig("url_b", "fingerprint_b", "trust_target_b", false);
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
     * became an admission REQUIREMENT (2026-08-07). It is not fixture convenience: an
     * https Incus host with a tenant-accepting posture and no lane now FAILS preflight by
     * design, so a fixture that skipped the lane would turn every Incus live test into a
     * silent skip -- the exact reports-success shape this wave exists to kill.
     *
     * @return the enrolled client certificate's fingerprint (for trust cleanup)
     */
    public String enrollThroughProduct(String hostName, String clientName) {
        String fingerprint = enrollDaemonTrustThroughProduct(hostName, clientName);
        enrollSshLaneThroughProduct(hostName);
        admitThroughProduct(hostName);
        sweepAndRegisterSharedObjects(hostName);
        return fingerprint;
    }

    /**
     * The two halves of keeping a shared TEST daemon from accumulating one ACL and one
     * bridge per fork forever: run the PRODUCT's own presence-stamp-and-reap sweep now
     * (our token is attributable from this moment, and any stamped garbage older than
     * the grace goes), and remember THIS controller's shared-object names so
     * {@link #releaseControllerSharedObjects} can hand them back at teardown.
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
    private void sweepAndRegisterSharedObjects(String hostName) {
        Row host = Models.get(ServerModel.class).findByName(hostName);
        ReapIncusControllers.HostOutcome outcome = ReapIncusControllers.sweepHost(host);
        System.out.println("=== enroll sweep on " + hostName + ": "
            + (outcome.reachable()
                ? IncusReaper.tally(outcome.plan()) + " removed=" + outcome.removed()
                    + " refused=" + outcome.refused()
                : "UNREACHABLE " + outcome.errors()));
        this.mintedSharedObjects.add(new SharedObjects(
            new ServerService().incusClientFor(hostName),
            IncusNetworkPolicy.aclName(),
            IncusNetworkPolicy.extraNetwork(),
            ControllerPresence.aclName()));
    }

    /** One enrolled daemon's per-controller shared-object names, captured in Db scope. */
    private record SharedObjects(IncusClient incus, String isolationAcl, String bridge,
                                 String presence) {}

    /**
     * Hand back THIS controller's shared objects on every enrolled daemon: the hhx
     * bridge and isolation ACL when nothing references them (re-read live, the reaper's
     * own guard), and the presence marker LAST and only when both others went -- the
     * marker is the evidence any survivor would be judged by. Own token only, so it is
     * safe beside three other live forks by construction. Call it in tearDown BEFORE
     * the trust entry is removed; after that the client cannot authenticate.
     *
     * @return one printable outcome per daemon, or "nothing enrolled"
     */
    public String releaseControllerSharedObjects() {
        if (this.mintedSharedObjects.isEmpty()) {
            return "nothing enrolled";
        }
        List<String> outcomes = new ArrayList<>();
        for (SharedObjects shared : this.mintedSharedObjects) {
            List<String> parts = new ArrayList<>();
            boolean bridgeGone = releaseIfUnused(parts, shared.bridge(), () ->
                shared.incus().network(shared.bridge()),
                () -> shared.incus().deleteNetwork(shared.bridge()));
            boolean aclGone = releaseIfUnused(parts, shared.isolationAcl(), () ->
                shared.incus().networkAcl(shared.isolationAcl()),
                () -> shared.incus().deleteNetworkAcl(shared.isolationAcl()));
            if (bridgeGone && aclGone) {
                releaseIfUnused(parts, shared.presence(), () ->
                    shared.incus().networkAcl(shared.presence()),
                    () -> shared.incus().deleteNetworkAcl(shared.presence()));
            } else {
                parts.add(shared.presence() + " -> kept (it is the evidence the"
                    + " surviving objects are judged by)");
            }
            outcomes.add(String.join(", ", parts));
        }
        this.mintedSharedObjects.clear();
        return String.join("; ", outcomes);
    }

    private interface DaemonRead {
        Map<String, Object> read() throws IOException;
    }

    private interface DaemonDelete {
        void delete() throws IOException;
    }

    /** @return true when the object is gone afterwards (absent counts as gone) */
    private static boolean releaseIfUnused(List<String> parts, String name,
                                           DaemonRead read, DaemonDelete delete) {
        try {
            Map<String, Object> live = read.read();
            if (live == null) {
                parts.add(name + " -> already absent");
                return true;
            }
            List<?> usedBy = ControllerPresence.usedBy(live);
            if (!usedBy.isEmpty()) {
                parts.add(name + " -> kept (referenced by " + usedBy + ")");
                return false;
            }
            delete.delete();
            parts.add(name + " -> released");
            return true;
        } catch (IOException failed) {
            parts.add(name + " -> NOT released: " + failed.getMessage());
            return false;
        }
    }

    /**
     * The DAEMON half of the ceremony only -- identity, pin, confirm, token enrollment --
     * leaving the host unpreflighted, unadmitted and without a kernel-truth lane.
     *
     * @return the enrolled client certificate's fingerprint (for trust cleanup)
     */
    public String enrollDaemonTrustThroughProduct(String hostName, String clientName) {
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
            "'" + command.replace("'", "'\\''") + "'")).trim();
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
     * AIDEV-NOTE: both pipes are drained on their OWN threads and the budget is enforced
     * on the PROCESS, because the obvious shape is a trap this fixture already fell into:
     * reading {@code getInputStream().readAllBytes()} on the calling thread blocks until
     * the remote closes stdout, so a command that keeps its channel open never reaches the
     * {@code waitFor(timeout)} below it and the "timeout" is decoration. Observed
     * 2026-08-05: one such call sat until Gradle killed the whole task at 30 minutes and
     * reported the test SKIPPED -- a hang that reads as a green-ish run. Draining
     * sequentially is the second half of the same trap: a child that fills the stderr pipe
     * while nobody reads it deadlocks against a caller blocked on stdout.
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
        Process process = new ProcessBuilder(argv).start();
        Future<String> stdout = DRAINS.submit(() -> drain(process.getInputStream()));
        Future<String> stderr = DRAINS.submit(() -> drain(process.getErrorStream()));
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IOException("ssh " + remoteCommand + " to " + this.trustTarget
                    + " outlived its " + timeoutSeconds + "s budget and was killed");
            }
            String out = stdout.get(10, TimeUnit.SECONDS);
            String err = stderr.get(10, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new IOException("ssh " + remoteCommand + " failed (exit "
                    + process.exitValue() + "): " + err.trim());
            }
            return out;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted talking to " + this.trustTarget);
        } catch (ExecutionException | TimeoutException e) {
            process.destroyForcibly();
            throw new IOException("could not read the output of ssh " + remoteCommand
                + ": " + e.getMessage());
        }
    }

    private static String drain(InputStream stream) throws IOException {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String require(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException(CONFIG + " has no '" + key + "'");
        }
        return value.trim();
    }
}
