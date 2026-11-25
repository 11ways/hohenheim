package be.elevenways.hohenheim;

import be.elevenways.zenit.common.operation.OperationVerb;
import be.elevenways.zenit.common.operation.OperationVerb.Effect;

/**
 * Hohenheim's own operation verbs, the second word of an assistant tool's name ({@code app_start}), where core's
 * vocabulary ({@link OperationVerb}) has no word for the act.
 *
 * AIDEV-NOTE: a key is a stored spelling once a tool carries it, and the registry is process-wide: an operation
 * whose act core or a module on Hohenheim's classpath already names uses that constant ({@link OperationVerb#PAUSE},
 * zenit-auth's toggle_enabled), and no word is declared here a second time; a clash refuses at class load.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class HohenheimVerbs {

    /** Starts the subject running. */
    public static final OperationVerb START = OperationVerb.declare(HohenheimVerbs.class, "start", Effect.WRITES);

    /** Stops the subject running; its data stays. */
    public static final OperationVerb STOP = OperationVerb.declare(HohenheimVerbs.class, "stop", Effect.WRITES);

    /** Stops and starts the subject again. */
    public static final OperationVerb RESTART = OperationVerb.declare(HohenheimVerbs.class, "restart", Effect.WRITES);

    /** Builds and rolls out the subject's current state. */
    public static final OperationVerb DEPLOY = OperationVerb.declare(HohenheimVerbs.class, "deploy", Effect.WRITES);

    /** Returns the subject to the release it ran before. */
    public static final OperationVerb ROLL_BACK = OperationVerb.declare(HohenheimVerbs.class, "roll_back",
        Effect.WRITES);

    /** Stores a backup of the subject's data now. */
    public static final OperationVerb BACK_UP = OperationVerb.declare(HohenheimVerbs.class, "back_up", Effect.WRITES);

    /** Takes a snapshot of the subject's disks now. */
    public static final OperationVerb SNAPSHOT = OperationVerb.declare(HohenheimVerbs.class, "snapshot",
        Effect.WRITES);

    /** Runs the subject's install step. */
    public static final OperationVerb INSTALL = OperationVerb.declare(HohenheimVerbs.class, "install", Effect.WRITES);

    /** Runs the subject's install step again, by its template's data policy. */
    public static final OperationVerb REINSTALL = OperationVerb.declare(HohenheimVerbs.class, "reinstall",
        Effect.WRITES);

    /** Makes a template of the subject's state. */
    public static final OperationVerb CAPTURE_TEMPLATE = OperationVerb.declare(HohenheimVerbs.class,
        "capture_template", Effect.WRITES);

    /** Removes the subject together with the data it owns, which {@link OperationVerb#DELETE} keeps. */
    public static final OperationVerb DELETE_WITH_DATA = OperationVerb.declare(HohenheimVerbs.class,
        "delete_with_data", Effect.DESTROYS);

    /** Puts install media in the subject's drive. */
    public static final OperationVerb ATTACH_MEDIA = OperationVerb.declare(HohenheimVerbs.class, "attach_media",
        Effect.WRITES);

    /** Takes the install media out of the subject's drives. */
    public static final OperationVerb DETACH_MEDIA = OperationVerb.declare(HohenheimVerbs.class, "detach_media",
        Effect.WRITES);

    /** Takes the subject (a disk, a device) off the machine it is attached to. */
    public static final OperationVerb DETACH = OperationVerb.declare(HohenheimVerbs.class, "detach", Effect.WRITES);

    /** Registers the subject's push webhook at its git provider. */
    public static final OperationVerb CONNECT_WEBHOOK = OperationVerb.declare(HohenheimVerbs.class,
        "connect_webhook", Effect.WRITES);

    /** Sends one line to the subject's primary process. */
    public static final OperationVerb CONSOLE_COMMAND = OperationVerb.declare(HohenheimVerbs.class,
        "console_command", Effect.WRITES);

    /** Runs one program inside the subject and answers its exit code and output. */
    public static final OperationVerb EXEC = OperationVerb.declare(HohenheimVerbs.class, "exec", Effect.WRITES);

    /** Runs the update script of the subject's template inside the running subject. */
    public static final OperationVerb RUN_UPDATE_SCRIPT = OperationVerb.declare(HohenheimVerbs.class,
        "run_update_script", Effect.WRITES);

    /** Restores the subject (a backup, a dump) into a new record beside its source. */
    public static final OperationVerb RESTORE_TO_NEW = OperationVerb.declare(HohenheimVerbs.class, "restore_to_new",
        Effect.WRITES);

    /** Replaces the data of the subject's source with the subject's (a backup, a snapshot, a dump). */
    public static final OperationVerb RESTORE_IN_PLACE = OperationVerb.declare(HohenheimVerbs.class,
        "restore_in_place", Effect.DESTROYS);

    /** Copies the subject under a new name. */
    public static final OperationVerb CLONE = OperationVerb.declare(HohenheimVerbs.class, "clone", Effect.WRITES);

    /** Stops forcing HTTPS on the subject's names no working certificate covers. */
    public static final OperationVerb STOP_FORCING_HTTPS = OperationVerb.declare(HohenheimVerbs.class,
        "stop_forcing_https", Effect.WRITES);

    /** Tries the subject against what it reaches outside and records the outcome. */
    public static final OperationVerb TEST = OperationVerb.declare(HohenheimVerbs.class, "test", Effect.WRITES);

    /** Tries the subject against what it reaches outside and changes nothing. */
    public static final OperationVerb CHECK = OperationVerb.declare(HohenheimVerbs.class, "check", Effect.READS);

    /** Orders a certificate for the subject's name. */
    public static final OperationVerb ORDER_CERTIFICATE = OperationVerb.declare(HohenheimVerbs.class,
        "order_certificate", Effect.WRITES);

    /** Orders the subject (a certificate) again, with names or a challenge changed. */
    public static final OperationVerb REISSUE = OperationVerb.declare(HohenheimVerbs.class, "reissue", Effect.WRITES);

    /** Renews the subject (a certificate) now. */
    public static final OperationVerb RENEW = OperationVerb.declare(HohenheimVerbs.class, "renew", Effect.WRITES);

    /** Finishes the subject's waiting order once what it waits for is published. */
    public static final OperationVerb CONTINUE_ORDER = OperationVerb.declare(HohenheimVerbs.class, "continue_order",
        Effect.WRITES);

    /** Moves the subject (a database) onto its host's shared engine. */
    public static final OperationVerb MOVE_TO_SHARED = OperationVerb.declare(HohenheimVerbs.class, "move_to_shared",
        Effect.WRITES);

    /** Finishes removing the engine a move onto a shared one left held. */
    public static final OperationVerb FINISH_OLD_ENGINE = OperationVerb.declare(HohenheimVerbs.class,
        "finish_old_engine", Effect.WRITES);

    /** Removes the subject's record after a normal delete failed; the host may keep leftovers. */
    public static final OperationVerb FORCE_DELETE = OperationVerb.declare(HohenheimVerbs.class, "force_delete",
        Effect.DESTROYS);

    /** Reads the subject's live state back into its status. */
    public static final OperationVerb REFRESH = OperationVerb.declare(HohenheimVerbs.class, "refresh", Effect.WRITES);

    /** Removes the volumes the subject owns. */
    public static final OperationVerb PURGE_VOLUMES = OperationVerb.declare(HohenheimVerbs.class, "purge_volumes",
        Effect.DESTROYS);

    /** Removes the images no workload uses from every host's daemon. */
    public static final OperationVerb RECLAIM_IMAGES = OperationVerb.declare(HohenheimVerbs.class, "reclaim_images",
        Effect.DESTROYS);

    /** Creates a new app from the subject (a template): with the noun "app", the tool app_create_from_template. */
    public static final OperationVerb CREATE_FROM_TEMPLATE = OperationVerb.declare(HohenheimVerbs.class,
        "create_from_template", Effect.WRITES);

    /** Puts a new service online from the subject (a template), reached on its ports. */
    public static final OperationVerb PUT_SERVICE_ONLINE = OperationVerb.declare(HohenheimVerbs.class,
        "put_service_online", Effect.WRITES);

    /** Tears the subject (a preview) down at its deadline. */
    public static final OperationVerb EXPIRE = OperationVerb.declare(HohenheimVerbs.class, "expire", Effect.DESTROYS);

    /** Lifts the subject (a quarantine) so its name may be claimed again. */
    public static final OperationVerb LIFT = OperationVerb.declare(HohenheimVerbs.class, "lift", Effect.WRITES);

    /** Agrees a new transfer key with the subject (a DNS peer). */
    public static final OperationVerb NEGOTIATE_KEY = OperationVerb.declare(HohenheimVerbs.class, "negotiate_key",
        Effect.WRITES);

    /** Checks the subject (a host) and admits it to the fleet once every required check passes. */
    public static final OperationVerb ADMIT = OperationVerb.declare(HohenheimVerbs.class, "admit", Effect.WRITES);

    /** Moves every workload off the subject (a cordoned host). */
    public static final OperationVerb DRAIN = OperationVerb.declare(HohenheimVerbs.class, "drain", Effect.WRITES);

    /** Revokes the subject's credential, so it is refused from then on. */
    public static final OperationVerb REVOKE = OperationVerb.declare(HohenheimVerbs.class, "revoke", Effect.WRITES);

    /** Removes the objects this controller left on the subject (a host) that no record names. */
    public static final OperationVerb REAP = OperationVerb.declare(HohenheimVerbs.class, "reap", Effect.DESTROYS);

    /** Reads the subject's log: what a workload printed, or what a build or a deploy wrote. */
    public static final OperationVerb READ_LOG = OperationVerb.declare(HohenheimVerbs.class, "read_log",
        Effect.READS);

    /** Lists the subject's deploys and the builds that never became one, newest first. */
    public static final OperationVerb LIST_DEPLOYS = OperationVerb.declare(HohenheimVerbs.class, "list_deploys",
        Effect.READS);

    /** Lists the subject's own variables. */
    public static final OperationVerb LIST_VARIABLES = OperationVerb.declare(HohenheimVerbs.class,
        "list_variables", Effect.READS);

    /** Sets one plain variable on the subject. */
    public static final OperationVerb SET_VARIABLE = OperationVerb.declare(HohenheimVerbs.class, "set_variable",
        Effect.WRITES);

    /** Removes one variable from the subject. */
    public static final OperationVerb UNSET_VARIABLE = OperationVerb.declare(HohenheimVerbs.class,
        "unset_variable", Effect.DESTROYS);

    /** Has the server make a secret value for a key of the subject that holds none; nobody is ever sent the value. */
    public static final OperationVerb GENERATE_SECRET = OperationVerb.declare(HohenheimVerbs.class,
        "generate_secret", Effect.WRITES);

    /** Moves the subject's public ports (a stream site's) into another record and sends the emptied subject away. */
    public static final OperationVerb MOVE_PORTS = OperationVerb.declare(HohenheimVerbs.class, "move_ports",
        Effect.DESTROYS);

    private HohenheimVerbs() {
    }
}
