# Shared database engines

One engine process per host serving many managed databases, instead of one engine
container per managed database.

## Why

Before 2026-09-02 every managed database record owned exactly one
`hohenheim:database_container` instance. On robbedoes that meant six Mongo containers
booking 512 to 1280 MB each (3,840 MB of the host budget) for roughly 650 MB of real
use: a Mongo process idles at ~120 MB and WiredTiger floors its cache at 256 MiB below
a 1.5 GiB cap whatever the cap says, so five small engines cost five floors. One engine
holding six logical databases costs one.

## Vocabulary

- **Engine kind** (`DatabaseModel.ENGINE`, tokens `postgres`/`mysql`/`redis`/`mongo`):
  the software. Unchanged, one home, drift-tested against `ManagedDatabase.Engine`.
- **Database engine** (`DatabaseEngineModel`, table `database_engines`): a RUNNING
  engine process on one host, operator-owned, that hosts logical databases. It owns the
  `database_container` instance the way a dedicated database record used to. There is
  at most one per (host, engine kind) that the allocation funnel creates on demand; an
  operator may create more by hand (a second Mongo on a host for a different major
  version, say) and pick one explicitly.
- **Placement** (`DatabaseModel.PLACEMENT`, tokens `dedicated`/`shared`): whether a
  managed database is its own engine container (the pre-2026-09-02 shape, still fully
  supported) or a LOGICAL database on a shared engine. `DatabaseModel.ENGINE_ID` is the
  resolved binding of a shared record; the two are one fact
  (`DatabaseModel.isShared(row)` reads placement, and a before-validate hook refuses a
  shared row without an engine or a dedicated row with one). Rows that predate the
  column carry a null placement and read as dedicated, which is exactly what they are.
- **Engine host** (`EngineHost`, server-side): the one resolution every operation goes
  through. For a dedicated record it is the record itself (its own container, its own
  root credentials); for a shared record it is the engine row. Injection, backup,
  restore, readiness, networks and destroy all ask `EngineHost.serving(databaseRow)`
  and never look at the placement themselves.

## Which engines share

`ManagedDatabase.Engine.supportsLogicalDatabases()`: Mongo, MySQL and PostgreSQL do
(each has a real per-database namespace with per-database credentials); Redis does not
(its "databases" are numbered slots under one password with one keyspace ACL model),
so a Redis record is always dedicated and a shared placement is refused by name.

The default placement of a new record is `shared` for an engine that supports it and
`dedicated` otherwise. An ephemeral (tmpfs) database is always dedicated: the tmpfs is
a property of its own container.

MySQL and PostgreSQL ride the same mechanism because it is generic (a create-database
plus create-user script per engine, an exhaustive switch). The production migration of
2026-09-02 moved only the Mongo records; the two WordPress MySQL records stay dedicated
until an operator moves them (one row action each, see below).

## Credentials

A shared database gets its OWN user, created on its own logical database, with rights
on that database only:

| Engine   | Created as                                                      | Connection URL     |
| -------- | --------------------------------------------------------------- | ------------------ |
| Mongo    | `db.getSiblingDB(name).createUser({roles: [dbOwner on name]})`  | `?authSource=name` |
| MySQL    | `CREATE USER 'u'@'%'` + `GRANT ALL ON name.*`                   | `mysql://.../name` |
| Postgres | `CREATE ROLE u LOGIN` + `CREATE DATABASE name OWNER u`          | `postgres://.../name` |

The engine's root credentials live on the engine row (`root_user`, encrypted
`root_password`) and are used only by the controller: engine readiness, creating and
dropping logical databases, dumps and restores. A workload never sees them. A dedicated
record's `db_user` IS the engine root (unchanged); its Mongo URL keeps `authSource=admin`.

## Every seam, and what changed

| Seam | Dedicated (unchanged) | Shared |
| --- | --- | --- |
| Provision | own instance deployed, readiness probed | engine ensured running (created on demand, serialized per engine), then the logical database + user created idempotently |
| Env injection | host = own container handle | host = the ENGINE's container handle, port = engine port, `authSource` = the database |
| Link networks | (instance, database) network joins the workload and the database's container | same network shape, the ENGINE container is the member; two workloads sharing one engine still cannot reach each other |
| Capacity booking | own instance at its cap | nothing: the engine instance is booked once, at its own cap (`Engine.sharedFootprintMb`, 1024 MB default, operator-resizable on the engine) |
| Backup (nightly + download) | dump inside own container with own root creds | dump of ONE logical database inside the engine container with the engine's root creds (`mongodump --db`, `mysqldump db`, `pg_dump -d db`) |
| Restore | client inside own container | same, scoped to the database (`mongorestore --nsInclude db.*`) |
| Readiness probe | own container | engine container, root creds |
| Resize | record's ceilings recreate its container | refused on the record (fields hidden, notice names the engine); the engine row is resized instead |
| Destroy | verified container removal, data volume optional | logical database and user DROPPED on the engine (refused when the engine is unreachable, record kept as `destroy_failed`); the engine stays |
| Engine end of life | n/a | an engine is deleted explicitly by an operator, refused while any record still points at it. It does NOT die with its last database: recreating an engine costs a minute and a port, and a booking that flaps with the last delete is worse than an idle 120 MB process |

## Moving a dedicated database onto a shared engine

`DatabaseService.moveToSharedEngine(name)`, offered on the Databases list as the
"Move to shared engine" row action (dedicated, active, engine supports it). It runs
in the background with the record `provisioning` while it works:

1. Stop every live workload attached to the database (their injected address is about
   to change; a write between the dump and the switch would be lost otherwise).
2. Dump the dedicated database, streamed, to `<backup_path>/moves/<name>/<stamp>.<ext>`.
   This file is the rollback and is never pruned by the nightly task.
3. Resolve or create the shared engine on the same host (the record's image, if any,
   must match the engine's) and ensure it runs.
4. Create the logical database and user with the record's OWN credentials, restore the
   dump into it, and compare the engine's content fingerprint on both sides
   (`dbHash` for Mongo, `CHECKSUM TABLE` per table for MySQL, per-table row md5 for
   Postgres). A mismatch aborts before anything is switched.
5. Flip the record to `shared` + the engine id, destroy the old engine instance (the
   named data volume is KEPT as a second rollback; remove it by hand once satisfied),
   rejoin the link networks with the engine container, redeploy every workload stopped
   in step 1 so it picks up the new address.
6. Any failure before step 5 restarts the stopped workloads against the untouched
   dedicated engine and leaves the record `active` with the failure text in
   `failure_reason` (an attention item names it).

## Tenants and quotas

A tenant allocation (`TenantDatabases.allocate`, the `/manage` create form, a
template-declared database) takes the default placement like every other create, so a
tenant's Mongo/MySQL/Postgres database is a logical database on the OPERATOR's shared
engine. Consequences, all deliberate: the tenant's record is charged to the tenant's
DATABASE quota (`MAX_DATABASES`, `DatabaseQuota`) exactly as before; the tenant's
INSTANCE quota is NOT spent (a shared database is not an instance) and the engine's
memory is booked once against the operator's bucket, because the operator chose to
share one process; the engine row and its credentials are never visible to a tenant
(the tenant panel shows the placement token only); and a tenant still cannot write any
column of its record outside the funnel (`tenant_field_frozen` wins over the placement
invariant by hook order). A Redis allocation stays dedicated and keeps spending an
instance slot.

## Panel and API

- Databases list: a Placement column and an Engine column (empty = dedicated); the
  create form's Placement select (default shared) and an optional explicit engine pick;
  memory/CPU ceilings are hidden on a shared record.
- Database engines list (Deploy group, beside Databases): name, engine, host, status,
  the databases it hosts, memory; create (engine, host, image, ceilings), resize (same
  recreate lane as a dedicated database), delete (refused while used).
- No REST verbs exist for databases (there were none before either).

## Decisions taken without Jelle (2026-09-02)

- Engine survives its last database (see table above).
- Shared engine default footprint 1024 MB for every sharing engine: UNMEASURED for a
  multi-database load, chosen because WiredTiger only starts using more than its
  256 MiB floor above a 1.5 GiB cap and MySQL's own default is already 1024; an
  operator resizes the engine row when a measurement says otherwise.
- Generic mechanism, Mongo-only production migration.
- A shared record may not declare an image that differs from its engine's
  (`database_image_engine_mismatch`); blank means the engine's.
