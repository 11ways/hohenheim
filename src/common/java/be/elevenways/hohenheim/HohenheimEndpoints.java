package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.data.DataPage;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.routing.RateLimitPolicy;
import be.elevenways.zenit.common.routing.WebSocketEndpoint;

import java.time.Duration;

/**
 * Host-declared endpoints that complement the zenit-cms panel routes: file
 * downloads/uploads, process control, the terminal WebSocket, the settings
 * write path, and the health check. Regular admin CRUD is owned by zenit-cms.
 */
public class HohenheimEndpoints {

    // --- Parameters ---
    public static final ParameterDefinition<Integer> SITE_ID = ParameterDefinition.builder(Integer.class)
        .name("siteId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<Long> PID = ParameterDefinition.builder(Long.class)
        .name("pid")
        .stringResolver(Long::parseLong)
        .build();

    public static final ParameterDefinition<Integer> CERT_ID = ParameterDefinition.builder(Integer.class)
        .name("certId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<String> DATABASE_NAME = ParameterDefinition.builder(String.class)
        .name("databaseName")
        .stringResolver(value -> value)
        .build();

    public static final ParameterDefinition<Integer> ZONE_ID = ParameterDefinition.builder(Integer.class)
        .name("zoneId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<String> DNS_ORIGIN = ParameterDefinition.builder(String.class)
        .name("dnsOrigin")
        .stringResolver(value -> value)
        .build();

    public static final ParameterDefinition<Integer> DNS_RECORD_ID = ParameterDefinition.builder(Integer.class)
        .name("dnsRecordId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<Integer> TEMPLATE_ID = ParameterDefinition.builder(Integer.class)
        .name("templateId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<Integer> INSTANCE_ID = ParameterDefinition.builder(Integer.class)
        .name("instanceId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<Integer> PROVIDER_ID = ParameterDefinition.builder(Integer.class)
        .name("providerId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<Integer> SERVER_ID = ParameterDefinition.builder(Integer.class)
        .name("serverId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<Integer> PROJECT_ID = ParameterDefinition.builder(Integer.class)
        .name("projectId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<Integer> ENVIRONMENT_ID = ParameterDefinition.builder(Integer.class)
        .name("environmentId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<Integer> DEPLOYMENT_ID = ParameterDefinition.builder(Integer.class)
        .name("deploymentId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<Integer> RELEASE_ID = ParameterDefinition.builder(Integer.class)
        .name("releaseId")
        .stringResolver(Integer::parseInt)
        .build();

    public static final ParameterDefinition<Integer> BUILD_ID = ParameterDefinition.builder(Integer.class)
        .name("buildId")
        .stringResolver(Integer::parseInt)
        .build();

    // --- Rate limits: expensive or upstream-quota-bound operations. ---
    // The LE request burns Let's Encrypt quota; db dump/restore stream whole
    // databases; deploys spawn builds. Keyed per principal (per IP for
    // anonymous rejects) so one runaway client cannot starve the rest.
    private static final RateLimitPolicy LE_REQUEST_LIMIT =
        RateLimitPolicy.of(5, Duration.ofHours(1))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_le_request");

    private static final RateLimitPolicy DOWNLOAD_LIMIT =
        RateLimitPolicy.of(30, Duration.ofMinutes(1))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_download");

    private static final RateLimitPolicy DATABASE_IO_LIMIT =
        RateLimitPolicy.of(5, Duration.ofMinutes(1))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_db_io");

    private static final RateLimitPolicy DEPLOY_LIMIT =
        RateLimitPolicy.of(10, Duration.ofMinutes(1))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_deploy");

    // The dyndns endpoint is public (token in HTTP Basic auth); keyed per IP so a
    // credential-guessing client cannot grind tokens. Routers poll every few
    // minutes, so a generous per-minute budget still leaves ample headroom.
    private static final RateLimitPolicy DYNDNS_LIMIT =
        RateLimitPolicy.of(30, Duration.ofMinutes(1))
            .keyBy(RateLimitPolicy.KeyBy.IP)
            .named("hh_dyndns");

    // --- Tenant instance surface: a container start pulls images and burns host CPU,
    //     a backup/snapshot moves gigabytes, and a create provisions a workload. Every
    //     one of them is a resource-amplification lever in tenant hands, so each rides
    //     its own budget keyed per principal (per IP for anonymous rejects). Reads are
    //     generous enough for a polling dashboard and still bounded.
    private static final RateLimitPolicy INSTANCE_READ_LIMIT =
        RateLimitPolicy.of(120, Duration.ofMinutes(1))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_instance_read");

    private static final RateLimitPolicy INSTANCE_POWER_LIMIT =
        RateLimitPolicy.of(20, Duration.ofMinutes(1))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_instance_power");

    private static final RateLimitPolicy INSTANCE_ARTIFACT_LIMIT =
        RateLimitPolicy.of(5, Duration.ofMinutes(10))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_instance_artifact");

    private static final RateLimitPolicy INSTANCE_CREATE_LIMIT =
        RateLimitPolicy.of(10, Duration.ofMinutes(10))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_instance_create");

    // File operations AMPLIFY: one listing is an exec in a container, one download moves a
    // capped-but-real payload off a volume, and both are cheap to issue in a loop. Browsing
    // is interactive so its budget is generous; writes move bytes INTO a workload and are
    // tighter. Keyed per principal (per IP for anonymous rejects), like every other tenant
    // budget here.
    private static final RateLimitPolicy INSTANCE_FILES_READ_LIMIT =
        RateLimitPolicy.of(120, Duration.ofMinutes(1))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_instance_files_read");

    private static final RateLimitPolicy INSTANCE_FILES_WRITE_LIMIT =
        RateLimitPolicy.of(30, Duration.ofMinutes(1))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_instance_files_write");

    // The PaaS surface: reads are generous enough for a polling CI job and still
    // bounded; variable writes mutate deploy inputs and are tighter. Deploy and
    // rollback ride the existing DEPLOY_LIMIT -- they spawn builds either way.
    private static final RateLimitPolicy PAAS_READ_LIMIT =
        RateLimitPolicy.of(120, Duration.ofMinutes(1))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_paas_read");

    private static final RateLimitPolicy PAAS_WRITE_LIMIT =
        RateLimitPolicy.of(30, Duration.ofMinutes(1))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_paas_write");

    // --- Let's Encrypt request (POST for the CMS certificate-request page) ---
    public static final Endpoint<Object> CERTIFICATES_REQUEST = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "certificates_request"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("admin").addDelimiter().addStatic("certificates-request").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .rateLimit(LE_REQUEST_LIMIT)
        .build();

    // --- Instance templates: export download, import paste, create-from-template ---

    /**
     * The template catalog export, deliberately NOT {@code requiresInteractiveLogin()}.
     *
     * AIDEV-NOTE: the line this endpoint sits on the safe side of, stated so the next
     * reader does not "harden" it by reflex or copy its stance onto the next download.
     * The DECIDING question is whether the response body is a CREDENTIAL -- something
     * that outlives the request and grants authority somewhere else. This document is
     * operator-authored catalog content (name, scripts, config files, variable
     * DECLARATIONS with their non-secret defaults, {@code TemplatePortability.export});
     * an admin-scoped key already reads every one of those fields off the template
     * resource, which is the accepted residual. {@code CERTIFICATES_DOWNLOAD} is the
     * opposite answer under the same question and carries the note that says so.
     */
    public static final Endpoint<Object> INSTANCE_TEMPLATES_EXPORT = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "instance_templates_export"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("admin").addDelimiter().addStatic("instance-templates").addDelimiter()
            .addParameter(TEMPLATE_ID).addDelimiter().addStatic("export").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .build();

    public static final Endpoint<Object> INSTANCE_TEMPLATES_IMPORT = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "instance_templates_import"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("admin").addDelimiter().addStatic("instance-templates-import").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .build();

    /**
     * The create-from-template submit, for BOTH panels. Deliberately NOT gated on the
     * admin permission and deliberately NOT under an {@code /admin} path: the authority
     * to create is {@code hohenheim.instances.create} (plus the template's approval
     * stamp, the quota and placement), all decided inside
     * {@code InstanceTemplates.createFromTemplate} so the HTML surfaces and the
     * automation API answer to ONE gate. requiresLogin keeps anonymous callers out
     * before any of that runs.
     */
    public static final Endpoint<Object> INSTANCES_FROM_TEMPLATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "instances_from_template"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("instances").addDelimiter().addStatic("from-template").build())
        .requiresLogin()
        .rateLimit(INSTANCE_CREATE_LIMIT)
        .build();

    // --- Git provider repository/branch selection (admin pickers + automation) ---
    private static final RateLimitPolicy PROVIDER_BROWSE_LIMIT =
        RateLimitPolicy.of(30, Duration.ofMinutes(1))
            .keyBy(RateLimitPolicy.KeyBy.PRINCIPAL_OR_IP)
            .named("hh_provider_browse");

    /** Optional search text for the provider browse endpoints (query param). */
    public static final ParameterDefinition<String> PROVIDER_TEXT = ParameterDefinition.builder(String.class)
        .name("text")
        .stringResolver(s -> s)
        .build();

    /** Repository the branch listing addresses (query param -- paths carry slashes). */
    public static final ParameterDefinition<String> PROVIDER_REPOSITORY = ParameterDefinition.builder(String.class)
        .name("repository")
        .stringResolver(s -> s)
        .build();

    // Typed DataPage answers, so the admin picker's DataProviders ride
    // Endpoint.call directly (the RecordSourceEndpoints shape).
    //
    // AIDEV-NOTE: deliberately NOT requiresInteractiveLogin(), unlike
    // CERTIFICATES_DOWNLOAD. Both answers are read-only ENUMERATIONS of the operator's
    // own provider (repository paths, branch names) -- no token, no clone URL with
    // credentials in it, nothing that grants authority elsewhere; the stored provider
    // credential never leaves the controller. Automation is a DECLARED consumer here
    // (the docblock on the handlers says so), and closing an enumeration to it would buy
    // nothing the admin permission does not already decide.
    public static final Endpoint<DataPage> GIT_PROVIDER_REPOSITORIES = Endpoint.<DataPage>builder()
        .identifier(Identifier.of("hohenheim", "git_provider_repositories"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("admin").addDelimiter().addStatic("git-providers").addDelimiter()
            .addParameter(PROVIDER_ID).addDelimiter().addStatic("repositories").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .rateLimit(PROVIDER_BROWSE_LIMIT)
        .build();

    public static final Endpoint<DataPage> GIT_PROVIDER_BRANCHES = Endpoint.<DataPage>builder()
        .identifier(Identifier.of("hohenheim", "git_provider_branches"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("admin").addDelimiter().addStatic("git-providers").addDelimiter()
            .addParameter(PROVIDER_ID).addDelimiter().addStatic("branches").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .rateLimit(PROVIDER_BROWSE_LIMIT)
        .build();

    /** The access list whose rule tree a request adds to. */
    public static final ParameterDefinition<Integer> ACCESS_LIST_ID = ParameterDefinition.builder(Integer.class)
        .name("accessListId")
        .stringResolver(Integer::parseInt)
        .build();

    // --- Access-rule creation (POST from the access list's Rules tab) ---

    /**
     * Adds ONE node to an access list's rule tree. Creation lives here rather than on the
     * generated create form because a node's PLACE (its list and its parent group) is a
     * property of the tree the tab is showing, not a field an operator should type.
     */
    public static final Endpoint<Object> ACCESS_RULES_ADD = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "access_rules_add"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("admin").addDelimiter().addStatic("access-lists").addDelimiter()
            .addParameter(ACCESS_LIST_ID).addDelimiter().addStatic("rules").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .build();

    // --- DNS zone-file import (POST for the CMS zone-file tab) ---
    public static final Endpoint<Object> DNS_ZONE_IMPORT = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "dns_zone_import"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("admin").addDelimiter().addStatic("dns-zones").addDelimiter()
            .addParameter(ZONE_ID).addDelimiter().addStatic("zonefile").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .build();

    // --- Certificate PEM bundle download ---

    /**
     * The certificate bundle download: certificate AND private key, so INTERACTIVE only.
     *
     * AIDEV-NOTE: the accepted residual of the admin permission is that a long-lived
     * non-interactive credential can READ admin surfaces. A response body that IS a
     * credential is past that line: this bundle carries {@code PRIVATE_KEY_PEM}, which
     * outlives the request and impersonates the TLS host anywhere. Being a GET, no CSRF
     * layer stands between an API key and it either, so the endpoint declaration is the
     * only gate there can be. Read this note before adding the next download: the
     * question is not "is it admin-gated", it is "is the body a credential"
     * ({@code INSTANCE_TEMPLATES_EXPORT} carries the same question's other answer).
     */
    public static final Endpoint<Object> CERTIFICATES_DOWNLOAD = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "certificates_download"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("certificates").addDelimiter().addParameter(CERT_ID)
            .addDelimiter().addStatic("download").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .requiresInteractiveLogin()
        .rateLimit(DOWNLOAD_LIMIT)
        .build();

    // --- Managed database dump download / restore upload ---
    public static final Endpoint<Object> DATABASES_BACKUP = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "databases_backup"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("databases").addDelimiter().addParameter(DATABASE_NAME)
            .addDelimiter().addStatic("backup").build())
        // requiresLogin, NOT the admin permission: a delegated tenant holding the
        // `backups` capability on their own database reaches this. The gate moved onto
        // the SERVICE (DatabaseService.backupDownload -> requireDatabaseCapability), so
        // the /manage row action, this URL and any later caller answer to one policy;
        // the handler renders both "no such database" and "not yours" as one 404.
        .requiresLogin()
        .rateLimit(DATABASE_IO_LIMIT)
        .build();

    public static final Endpoint<Object> DATABASES_RESTORE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "databases_restore"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("databases").addDelimiter().addParameter(DATABASE_NAME)
            .addDelimiter().addStatic("restore").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .rateLimit(DATABASE_IO_LIMIT)
        .build();

    // --- Install media on an Incus host (forms on the server Install media tab) ---

    /**
     * Fetch an ISO from an operator-supplied URL into the host's own image pool as an
     * ISO volume. MEDIA_MANAGE rather than the admin permission because media
     * provenance is arbitrary bootable code and the fetch makes the CONTROLLER an
     * outbound HTTP client of an arbitrary origin: who may do that is its own answer.
     */
    public static final Endpoint<Object> SERVERS_MEDIA_FETCH = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "servers_media_fetch"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("servers").addDelimiter().addParameter(SERVER_ID)
            .addDelimiter().addStatic("media").addDelimiter().addStatic("fetch").build())
        .requiresPermission(HohenheimSources.MEDIA_MANAGE)
        .rateLimit(DATABASE_IO_LIMIT)
        .build();

    /**
     * Upload an ISO from the operator's own machine: the body IS the image, streamed to
     * disk and never buffered, so this route carries no form at all (the name travels in
     * the query string). The counterpart to FETCH for media that has no public URL.
     */
    public static final Endpoint<Object> SERVERS_MEDIA_UPLOAD = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "servers_media_upload"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("servers").addDelimiter().addParameter(SERVER_ID)
            .addDelimiter().addStatic("media").addDelimiter().addStatic("upload").build())
        .requiresPermission(HohenheimSources.MEDIA_MANAGE)
        .rateLimit(DATABASE_IO_LIMIT)
        .build();

    public static final Endpoint<Object> SERVERS_MEDIA_DELETE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "servers_media_delete"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("servers").addDelimiter().addParameter(SERVER_ID)
            .addDelimiter().addStatic("media").addDelimiter().addStatic("delete").build())
        .requiresPermission(HohenheimSources.MEDIA_MANAGE)
        .rateLimit(DATABASE_IO_LIMIT)
        .build();

    // --- Deploy control (forms on the site Deployments tab) ---
    public static final Endpoint<Object> SITES_DEPLOY = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_deploy"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("deploy").build())
        .rateLimit(DEPLOY_LIMIT)
        .build();

    public static final Endpoint<Object> SITES_DEPLOY_CANCEL = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_deploy_cancel"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("deploy").addDelimiter().addStatic("cancel").build())
        .rateLimit(DEPLOY_LIMIT)
        .build();

    public static final Endpoint<Object> SITES_ROLLBACK = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_rollback"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("rollback").build())
        .rateLimit(DEPLOY_LIMIT)
        .build();

    // --- Process control (forms on the site Processes tab) ---
    public static final Endpoint<Object> SITES_PROCESS_START = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_process_start"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("processes").addDelimiter().addStatic("start").build())
        .build();

    public static final Endpoint<Object> SITES_PROCESS_KILL = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_process_kill"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("processes").addDelimiter().addParameter(PID)
            .addDelimiter().addStatic("kill").build())
        .build();

    public static final Endpoint<Object> SITES_PROCESS_ISOLATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "sites_process_isolate"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("processes").addDelimiter().addParameter(PID)
            .addDelimiter().addStatic("isolate").build())
        .build();

    // --- Automation API (znit_ bearer keys via zenit-auth) ---
    public static final Endpoint<Object> API_SITES = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_sites"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("sites").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals, so an ambient session cookie can never act here. */
    public static final Endpoint<Object> API_SITES_DEPLOY = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_sites_deploy"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("sites")
            .addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("deploy").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .csrfExempt()
        .rateLimit(DEPLOY_LIMIT)
        .build();

    // --- Tenant instance API v1 (znit_ bearer keys via zenit-auth) ---
    //
    // A SEPARATE, VERSIONED surface from the HTML routes, and NOT a wider door than
    // them: authorization is the same record-capability walk the /manage panel rides,
    // enforced inside InstanceService/InstanceSnapshots/InstanceBackups rather than in
    // these handlers, and creation goes through the same
    // InstanceTemplates.createFromTemplate funnel the create page posts to.
    //
    // No requiresPermission: a record capability is not a permission, and demanding a
    // type-level one here would either lock tenants out or hand them everything. The
    // handlers refuse any principal that is not an API key (a browser session belongs on
    // the HTML surface), which is what makes csrfExempt safe. requiresLogin still
    // rejects anonymous callers before a handler runs.
    //
    // JSON, not DRY: this is an external interchange edge for third-party automation
    // (the protoblast doctrine's stated exception), and it matches the existing
    // /api/sites and /api/dns surfaces. Request bodies are ordinary form encoding so the
    // create call feeds the SAME raw-values map the HTML form does -- one coercion and
    // validation pipeline, not a parallel one.

    public static final Endpoint<Object> API_INSTANCES = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instances"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").build())
        .requiresLogin()
        .rateLimit(INSTANCE_READ_LIMIT)
        .build();

    public static final Endpoint<Object> API_INSTANCE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID).build())
        .requiresLogin()
        .rateLimit(INSTANCE_READ_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_INSTANCE_POWER = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_power"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("power").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(INSTANCE_POWER_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_INSTANCE_COMMAND = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_command"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("command").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(INSTANCE_POWER_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_INSTANCE_BACKUP = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_backup"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("backup").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(INSTANCE_ARTIFACT_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_INSTANCE_SNAPSHOT = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_snapshot"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("snapshot").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(INSTANCE_ARTIFACT_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_INSTANCE_CREATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_create"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(INSTANCE_CREATE_LIMIT)
        .build();

    // --- Tenant instance FILE API v1 ---
    //
    // The same rule as every other endpoint here: no authorization decision lives in a
    // handler. The read lane asks InstanceFiles for files.read and the write lane for
    // files.write, both inside the service, so this surface and the Files tab can never
    // hold different policies. The path always travels as the `path` QUERY PARAMETER and
    // never as a route segment: a route segment would be split on '/' and reassembled,
    // which is a second decode, and a second decode is how a normalized traversal slips in.

    public static final Endpoint<Object> API_INSTANCE_FILES = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_files"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("files").build())
        .requiresLogin()
        .rateLimit(INSTANCE_FILES_READ_LIMIT)
        .build();

    public static final Endpoint<Object> API_INSTANCE_FILE_CONTENT = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_file_content"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("files").addDelimiter().addStatic("content").build())
        .requiresLogin()
        .rateLimit(INSTANCE_FILES_READ_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_INSTANCE_FILE_WRITE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_file_write"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("files").addDelimiter().addStatic("content").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(INSTANCE_FILES_WRITE_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_INSTANCE_FILE_ACTION = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_file_action"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("files").addDelimiter().addStatic("action").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(INSTANCE_FILES_WRITE_LIMIT)
        .build();

    // --- PaaS API v1 (znit_ bearer keys via zenit-auth) ---
    //
    // The operator-facing automation seam over the machinery that already exists:
    // projects/environments, git-sourced deployments, health-gated releases with
    // digest-pinned rollback, per-deployment logs and the table-backed variable
    // mechanism. Same three rules as the instance lane above: no authorization
    // decision in a handler beyond the shared visibility walk, no existence oracle
    // (one byte-identical 404), no field that was not enumerated. Handlers refuse
    // non-API-key principals, which is what makes every csrfExempt below safe.

    public static final Endpoint<Object> API_PROJECTS = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_projects"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("projects").build())
        .requiresLogin()
        .rateLimit(PAAS_READ_LIMIT)
        .build();

    public static final Endpoint<Object> API_PROJECT = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_project"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("projects").addDelimiter().addParameter(PROJECT_ID).build())
        .requiresLogin()
        .rateLimit(PAAS_READ_LIMIT)
        .build();

    public static final Endpoint<Object> API_V1_SITES = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_v1_sites"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("sites").build())
        .requiresLogin()
        .rateLimit(PAAS_READ_LIMIT)
        .build();

    public static final Endpoint<Object> API_V1_SITE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_v1_site"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("sites").addDelimiter().addParameter(SITE_ID).build())
        .requiresLogin()
        .rateLimit(PAAS_READ_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_V1_SITE_DEPLOY = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_v1_site_deploy"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("deploy").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(DEPLOY_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_V1_SITE_ROLLBACK = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_v1_site_rollback"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("rollback").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(DEPLOY_LIMIT)
        .build();

    public static final Endpoint<Object> API_V1_SITE_DEPLOYMENTS = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_v1_site_deployments"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("deployments").build())
        .requiresLogin()
        .rateLimit(PAAS_READ_LIMIT)
        .build();

    public static final Endpoint<Object> API_V1_SITE_DEPLOYMENT_LOG = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_v1_site_deployment_log"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("deployments").addDelimiter().addParameter(DEPLOYMENT_ID)
            .addDelimiter().addStatic("log").build())
        .requiresLogin()
        .rateLimit(PAAS_READ_LIMIT)
        .build();

    public static final Endpoint<Object> API_V1_SITE_RELEASES = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_v1_site_releases"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("releases").build())
        .requiresLogin()
        .rateLimit(PAAS_READ_LIMIT)
        .build();

    public static final Endpoint<Object> API_V1_SITE_RELEASE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_v1_site_release"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("releases").addDelimiter().addParameter(RELEASE_ID).build())
        .requiresLogin()
        .rateLimit(PAAS_READ_LIMIT)
        .build();

    public static final Endpoint<Object> API_V1_SITE_BUILDS = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_v1_site_builds"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("builds").build())
        .requiresLogin()
        .rateLimit(PAAS_READ_LIMIT)
        .build();

    public static final Endpoint<Object> API_V1_SITE_BUILD_LOG = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_v1_site_build_log"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("sites").addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addStatic("builds").addDelimiter().addParameter(BUILD_ID)
            .addDelimiter().addStatic("log").build())
        .requiresLogin()
        .rateLimit(PAAS_READ_LIMIT)
        .build();

    public static final Endpoint<Object> API_INSTANCE_LOGS = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_logs"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("logs").build())
        .requiresLogin()
        .rateLimit(PAAS_READ_LIMIT)
        .build();

    public static final Endpoint<Object> API_INSTANCE_VARIABLES = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_variables"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("variables").build())
        .requiresLogin()
        .rateLimit(PAAS_READ_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_INSTANCE_VARIABLE_SET = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_variable_set"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("variables").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(PAAS_WRITE_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_INSTANCE_VARIABLE_DELETE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_variable_delete"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("variables").addDelimiter().addStatic("delete").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(PAAS_WRITE_LIMIT)
        .build();

    public static final Endpoint<Object> API_INSTANCE_DEVICES = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_devices"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("devices").build())
        .requiresLogin()
        .rateLimit(PAAS_READ_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_INSTANCE_DEVICE_ATTACH = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_device_attach"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("devices").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(PAAS_WRITE_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_INSTANCE_DEVICE_RESIZE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_device_resize"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("devices").addDelimiter().addStatic("resize").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(PAAS_WRITE_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_INSTANCE_DEVICE_DETACH = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_instance_device_detach"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("devices").addDelimiter().addStatic("detach").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(PAAS_WRITE_LIMIT)
        .build();

    public static final Endpoint<Object> API_ENVIRONMENT_VARIABLES = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_environment_variables"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("environments").addDelimiter().addParameter(ENVIRONMENT_ID)
            .addDelimiter().addStatic("variables").build())
        .requiresLogin()
        .rateLimit(PAAS_READ_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_ENVIRONMENT_VARIABLE_SET = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_environment_variable_set"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("environments").addDelimiter().addParameter(ENVIRONMENT_ID)
            .addDelimiter().addStatic("variables").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(PAAS_WRITE_LIMIT)
        .build();

    /** csrfExempt is safe: the handler refuses non-API-key principals. */
    public static final Endpoint<Object> API_ENVIRONMENT_VARIABLE_DELETE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_environment_variable_delete"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("v1").addDelimiter()
            .addStatic("environments").addDelimiter().addParameter(ENVIRONMENT_ID)
            .addDelimiter().addStatic("variables").addDelimiter().addStatic("delete").build())
        .requiresLogin()
        .csrfExempt()
        .rateLimit(PAAS_WRITE_LIMIT)
        .build();

    // --- Instance file manager, HTML lane (the Files tab posts here) ---

    /** Download one file from an instance volume; bounded by hohenheim.files.max_file_kb. */
    public static final Endpoint<Object> INSTANCE_FILE_DOWNLOAD = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "instance_file_download"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("files").addDelimiter().addStatic("download").build())
        .requiresLogin()
        .rateLimit(INSTANCE_FILES_READ_LIMIT)
        .build();

    /** Every mutating file action of the Files tab (save, upload, mkdir, rename, delete). */
    public static final Endpoint<Object> INSTANCE_FILE_ACTION = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "instance_file_action"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("files").addDelimiter().addStatic("action").build())
        .requiresLogin()
        .rateLimit(INSTANCE_FILES_WRITE_LIMIT)
        .build();

    // --- DNS records peer/automation API (znit_ bearer keys; the edit-forwarding
    //     channel other Hohenheim instances use to edit zones this instance owns) ---
    public static final Endpoint<Object> API_DNS_RECORDS = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_dns_records"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("dns").addDelimiter().addStatic("zones")
            .addDelimiter().addParameter(DNS_ORIGIN)
            .addDelimiter().addStatic("records").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .build();

    /** csrfExempt is safe: the handlers refuse non-API-key principals, so an ambient session cookie can never act here. */
    public static final Endpoint<Object> API_DNS_RECORD_CREATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_dns_record_create"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("dns").addDelimiter().addStatic("zones")
            .addDelimiter().addParameter(DNS_ORIGIN)
            .addDelimiter().addStatic("records").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .csrfExempt()
        .build();

    public static final Endpoint<Object> API_DNS_RECORD_UPDATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_dns_record_update"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("dns").addDelimiter().addStatic("zones")
            .addDelimiter().addParameter(DNS_ORIGIN)
            .addDelimiter().addStatic("records").addDelimiter().addParameter(DNS_RECORD_ID).build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .csrfExempt()
        .build();

    public static final Endpoint<Object> API_DNS_RECORD_DELETE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_dns_record_delete"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("dns").addDelimiter().addStatic("zones")
            .addDelimiter().addParameter(DNS_ORIGIN)
            .addDelimiter().addStatic("records").addDelimiter().addParameter(DNS_RECORD_ID)
            .addDelimiter().addStatic("delete").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .csrfExempt()
        .build();

    /**
     * Transfer-key negotiation: the caller minted a TSIG key for the two of us and
     * installs its half here. SYMMETRIC -- every instance both calls and exposes it.
     *
     * csrfExempt is safe for the same reason as the record routes: the handler refuses
     * every principal that is not an API key, so an ambient session cookie can never
     * plant a transfer key.
     */
    public static final Endpoint<Object> API_DNS_PEER_KEY = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "api_dns_peer_key"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("api").addDelimiter().addStatic("dns").addDelimiter()
            .addStatic("peer-key").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .csrfExempt()
        .build();

    // --- Remote-record edit forwarding (admin form POST on a SECONDARY zone's
    //     Records tab; forwards to the owning peer's API above) ---
    public static final Endpoint<Object> DNS_REMOTE_RECORD = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "dns_remote_record"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("admin").addDelimiter().addStatic("dns-zones").addDelimiter()
            .addParameter(ZONE_ID).addDelimiter().addStatic("remote-records").build())
        .requiresPermission(HohenheimSources.ADMIN_ACCESS)
        .build();

    // --- Dynamic DNS (dyndns2 update protocol; public, token in HTTP Basic auth) ---
    // No requiresPermission: the token IS the credential, verified by the handler.
    // csrfExempt because ddclient/routers cannot carry a CSRF token (GET, no cookie).
    public static final Endpoint<Object> DYNDNS_UPDATE = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "dyndns_update"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("nic").addDelimiter().addStatic("update").build())
        .csrfExempt()
        .rateLimit(DYNDNS_LIMIT)
        .build();

    // --- Health check ---
    public static final Endpoint<Object> HEALTH = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "health"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("api").addDelimiter().addStatic("health").build())
        .build();

    // --- Interactive process terminal ---

    /** How often a live terminal session's per-site manage grant is re-checked (revoked = 1008). */
    public static final long TERMINAL_REVALIDATION_INTERVAL_MS = 15_000;

    public static final WebSocketEndpoint PROCESS_TERMINAL = WebSocketEndpoint.builder()
        .identifier(Identifier.of("hohenheim", "process_terminal"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("ws").addDelimiter().addStatic("terminal")
            .addDelimiter().addParameter(SITE_ID)
            .addDelimiter().addParameter(PID).build())
        .requiresLogin()
        .revalidateEvery(TERMINAL_REVALIDATION_INTERVAL_MS)
        .handler(session -> null) // Placeholder: set in HohenheimHandlers.init(), at the MODULES stage
        .build();

    // --- Instance console: live output over a WebSocket, commands over a POST form ---

    /**
     * Live console output of one instance (pl-terminal reads it; read-only -- commands
     * go through {@link #INSTANCE_CONSOLE_COMMAND}, never raw keystrokes: a non-TTY
     * container echoes nothing, so keystroke input would be invisible typing).
     * Authorization is the handshake's requiresLogin plus the handler's per-record
     * CONSOLE check ({@code InstanceConsoles}), revalidated mid-session. Not manage:
     * console is its own enforced verb since the capability split, and manage merely
     * implies it.
     */
    public static final WebSocketEndpoint INSTANCE_CONSOLE = WebSocketEndpoint.builder()
        .identifier(Identifier.of("hohenheim", "instance_console"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("ws").addDelimiter().addStatic("instance-console")
            .addDelimiter().addParameter(INSTANCE_ID).build())
        .requiresLogin()
        .revalidateEvery(TERMINAL_REVALIDATION_INTERVAL_MS)
        .handler(session -> null) // Placeholder: set in HohenheimHandlers.init(), at the MODULES stage
        .build();

    /**
     * VM framebuffer rescue console: server-captured VGA snapshots down (binary frames),
     * keyboard/mouse input up (DRY frames). requiresLogin at the handshake plus the
     * handler's per-record CONSOLE check (see VmFramebufferHandler's note on the verb),
     * revalidated mid-session (revoked = 1008).
     */
    public static final WebSocketEndpoint VM_FRAMEBUFFER = WebSocketEndpoint.builder()
        .identifier(Identifier.of("hohenheim", "vm_framebuffer"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("ws").addDelimiter().addStatic("instance-framebuffer")
            .addDelimiter().addParameter(INSTANCE_ID).build())
        .requiresLogin()
        .revalidateEvery(TERMINAL_REVALIDATION_INTERVAL_MS)
        .handler(session -> null) // Placeholder: set in HohenheimHandlers.init(), at the MODULES stage
        .build();

    /**
     * One console command line to a running instance (the console tab's form). The
     * handler demands per-record CONSOLE; requiresLogin is declared EXPLICITLY even
     * though the baseline("/") catch-all already implies it -- every other endpoint in
     * this file states its handshake requirement, and relying on the catch-all made
     * this the one declaration a reader could not audit in place.
     */
    public static final Endpoint<Object> INSTANCE_CONSOLE_COMMAND = Endpoint.<Object>builder()
        .identifier(Identifier.of("hohenheim", "instance_console_command"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.POST)
            .addStatic("instances").addDelimiter().addParameter(INSTANCE_ID)
            .addDelimiter().addStatic("console").addDelimiter().addStatic("command").build())
        .requiresLogin()
        .build();

    // --- Dev-tunnel registration (remote dev servers; token-authenticated in-band) ---

    /**
     * How often a registered tunnel's site/token/namespace authorization is
     * re-resolved (revoked = 1008).
     *
     * AIDEV-NOTE: the endpoint is deliberately DECLARED even though the handshake
     * is anonymous. This connection authenticates IN BAND (the register frame's
     * token, DevTunnelServerHandler.handleRegister), so
     * WebSocketRevalidator.intervalFor's identity test -- requiresAuthorization or a
     * non-anonymous handshake principal -- is false here and would start NO
     * revalidator at all, leaving a rotated token, a disabled site or a deleted site
     * with a live tunnel forever. Declaring an interval is what reaches the handler's
     * revalidate() hook, which is where the in-band credential is re-checked.
     */
    public static final long DEV_TUNNEL_REVALIDATION_INTERVAL_MS = TERMINAL_REVALIDATION_INTERVAL_MS;

    public static final WebSocketEndpoint DEV_TUNNEL = WebSocketEndpoint.builder()
        .identifier(Identifier.of("hohenheim", "dev_tunnel"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("ws").addDelimiter().addStatic("dev-tunnel").build())
        .revalidateEvery(DEV_TUNNEL_REVALIDATION_INTERVAL_MS)
        // The handshake is anonymous BY DECISION: this connection authenticates IN BAND
        // (the register frame's token, DevTunnelServerHandler.handleRegister), so the open
        // handshake is declared deliberately rather than left as an omission. The framework
        // now REFUSES a WebSocket endpoint that declares no auth stance at all; the in-band
        // credential is re-checked on the revalidate() hook the interval above reaches.
        .publiclyAccessible()
        .handler(session -> null) // Placeholder: set in HohenheimHandlers.init(), at the MODULES stage
        .build();

    public static void init() {
        // Static fields are initialized when this method is called
    }
}
