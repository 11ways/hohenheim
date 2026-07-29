package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.process.PortAllocator;
import be.elevenways.hohenheim.server.process.ProcessMonitor;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Reserved control variables: whatever an operator configures, the child reaches the
 * IPC channel with the GENERATED values (the runtime stamp), and persisting a reserved
 * name is refused at save time on the model funnel (covers API-key write-back, clone,
 * seeds and imports, not just the admin form).
 */
class ReservedEnvJourneyTest {

    private static ProcessMonitor monitor;
    private static PortAllocator portAllocator;
    private final List<ManagedProcessSiteHandler> handlers = new java.util.ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        SiteTypes.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();

        monitor = new ProcessMonitor();
        portAllocator = new PortAllocator();
    }

    @AfterEach
    void reapHandlers() {
        for (ManagedProcessSiteHandler handler : handlers) {
            handler.destroy();
        }
        handlers.clear();
    }

    /**
     * Spawns a node child that PROVES which values it received: it authenticates on
     * the IPC channel using its OWN environment's token/port (so readiness itself is
     * the proof the generated pair won) and prints the probe variables to stdout.
     */
    private final class ProbeHandler extends ManagedProcessSiteHandler {

        ProbeHandler(int siteId, Map<String, Object> settings) {
            super(siteId, "reserved-env-" + siteId, settings, portAllocator, monitor);
            handlers.add(this);
        }

        @Override
        protected List<String> buildCommand(String listenTarget) {
            return List.of("node", "-e",
                "console.log('REPORT_TOKEN=' + (process.env.ZENIT_SECURITY_REPORT_TOKEN || 'absent'));"
                    + "console.log('REPORT_URL=' + (process.env.ZENIT_SECURITY_REPORT_URL || 'absent'));"
                    + "console.log('EXTRA=' + (process.env.HOHENHEIM_EXTRA || 'absent'));"
                    + "console.log('DB=' + (process.env.DATABASE_URL || 'absent'));"
                    + "console.log('PORT_SEEN=' + (process.env.PORT || 'absent'));"
                    + "const net=require('net');"
                    + "const s=net.connect(parseInt(process.env.HOHENHEIM_IPC_PORT),'127.0.0.1',()=>{"
                    + "s.write(JSON.stringify({type:'auth',token:process.env.HOHENHEIM_IPC_TOKEN})+'\\n');"
                    + "s.write(JSON.stringify({type:'ready'})+'\\n');"
                    + "});"
                    + "setInterval(()=>{},10000);");
        }

        @Override
        protected Map<String, String> resolveInjectedEnvironment() {
            Map<String, String> injected = new LinkedHashMap<>();
            injected.put("ZENIT_SECURITY_REPORT_URL", "https://collector.test/v1/events");
            injected.put("ZENIT_SECURITY_REPORT_TOKEN", "generated-report-token");
            injected.put("DATABASE_URL", "injected-db");
            return injected;
        }

        @Override
        protected Map<String, String> buildRuntimeEnvironment(int port) {
            return Map.of();
        }

        @Override
        protected File getWorkingDirectory() {
            return new File(System.getProperty("java.io.tmpdir"));
        }
    }

    private static void awaitCondition(String what, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    @Test
    void operatorValuesNeverReplaceGeneratedControlVariables() throws Exception {
        // 1. A hostile operator configuration: forged IPC pair, redirected security
        //    reporting, a squatting HOHENHEIM_ name, a squatting PORT, plus a
        //    LEGITIMATE override of an injected non-reserved DATABASE_* value.
        Map<String, Object> settings = new HashMap<>();
        settings.put("wait_for_ready", true);
        settings.put("minimum_processes", 1);
        Map<String, String> operator = new LinkedHashMap<>();
        operator.put("HOHENHEIM_IPC_TOKEN", "forged-token");
        operator.put("HOHENHEIM_IPC_PORT", "1");
        operator.put("ZENIT_SECURITY_REPORT_TOKEN", "attacker-token");
        operator.put("HOHENHEIM_EXTRA", "squatted");
        operator.put("PORT", "9999");
        operator.put("DATABASE_URL", "operator-db");
        settings.put("environment_variables", operator);

        ProbeHandler handler = new ProbeHandler(9301, settings);
        handler.startMinimumServers();

        // 2. The child becomes READY: it authenticated using the token and port from
        //    its OWN environment, so the GENERATED pair reached it -- had the forged
        //    operator pair won, auth would fail and readiness never arrive.
        awaitCondition("2. the child authenticated with the generated IPC pair",
            () -> handler.getHealth() == SiteHealth.UP);

        // 3. The probe output pins each variable: reserved ones carry the generated
        //    values, the stray HOHENHEIM_ name is dropped, and the non-reserved
        //    injected DATABASE_URL kept the operator's override (warn, not refuse).
        String log = handler.getProcesses().get(0).getLogText();
        assertThat(log)
            .as("3. the security-report token is the generated one, not the attacker's")
            .contains("REPORT_TOKEN=generated-report-token")
            .doesNotContain("attacker-token");
        assertThat(log)
            .as("3. the security-report URL is the generated collector")
            .contains("REPORT_URL=https://collector.test/v1/events");
        assertThat(log)
            .as("3. a stray HOHENHEIM_ variable is dropped entirely")
            .contains("EXTRA=absent");
        assertThat(log)
            .as("3. a non-reserved injected variable may be overridden by the operator")
            .contains("DB=operator-db");
        int allocatedPort = handler.getProcesses().get(0).port();
        assertThat(log)
            .as("3. PORT is the allocator's port, not the operator's 9999")
            .contains("PORT_SEEN=" + allocatedPort)
            .doesNotContain("PORT_SEEN=9999");
    }

    @Test
    void persistingAReservedNameIsRefusedOnTheModelFunnel() {
        var model = Models.get(SiteModel.class);

        // 1. A node site whose settings smuggle a reserved name is refused with the
        //    specific violation, whatever path performed the save (this is the MODEL
        //    hook, not a resource-layer check).
        Row site = model.createEmptyRow();
        site.set(SiteModel.NAME, "reserved-env-site");
        site.set(SiteModel.SITE_TYPE, "hohenheim:node");
        site.set(SiteModel.SETTINGS, Map.of(
            "script", "/srv/app/server.js",
            "environment_variables", Map.of("HOHENHEIM_IPC_TOKEN", "forged")));
        Violations violations = catchThrowableOfType(() -> model.save(site), Violations.class);
        assertThat((Object) violations)
            .as("1. saving a reserved environment variable name throws Violations")
            .isNotNull();
        Violation violation = violations.all().get(0);
        assertThat(violation.fieldName())
            .as("1. the violation targets the environment variables field")
            .isEqualTo("settings.environment_variables");
        assertThat(violation.value())
            .as("1. the violation names the offending variable")
            .isEqualTo("HOHENHEIM_IPC_TOKEN");
        assertThat(violation.message().key())
            .as("1. the violation carries the reserved_env_var message")
            .isEqualTo("reserved_env_var");

        // 2. The exact-name set is refused too, not only the prefix.
        site.set(SiteModel.SETTINGS, Map.of(
            "script", "/srv/app/server.js",
            "environment_variables", Map.of("ZENIT_SECURITY_REPORT_TOKEN", "redirect")));
        assertThatThrownBy(() -> model.save(site))
            .as("2. the security-report token cannot be configured either")
            .isInstanceOf(Violations.class)
            .hasMessageContaining("environment_variables");

        // 3. Positive control: non-reserved names (including a DATABASE_* override
        //    and the child's own PATH) persist without a violation.
        Row fine = model.createEmptyRow();
        fine.set(SiteModel.NAME, "reserved-env-fine");
        fine.set(SiteModel.SITE_TYPE, "hohenheim:node");
        fine.set(SiteModel.SETTINGS, Map.of(
            "script", "/srv/app/server.js",
            "environment_variables", Map.of(
                "DATABASE_URL", "postgres://external/db",
                "PATH", "/opt/custom/bin",
                "NODE_OPTIONS", "--max-old-space-size=256")));
        model.save(fine);
        assertThat((Object) fine.get(SiteModel.ID))
            .as("3. a site with non-reserved variables saves normally")
            .isNotNull();
    }
}
