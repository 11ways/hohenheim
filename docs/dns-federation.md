# DNS federation: hidden primary, secondaries, and zone transfer

Status: SHIPPED (2026-07-17) for the standards-based replication core.

## What it enables

Each DNS zone has one owning instance (its PRIMARY); other instances can hold
read replicas (SECONDARIES) kept in sync over authenticated zone transfer.
This gives two things at once:

- A HIDDEN PRIMARY: Hohenheim at the office owns and edits its zones but never
  exposes port 53 to the internet. A public secondary (a VPS) answers the
  world. The office firewall only needs an inbound rule for the transfer port
  from the secondary's IP (TSIG-authenticated), plus outbound NOTIFY.
- REDUNDANCY: two authoritative nameservers on different networks, the
  standard production posture. The secondary can be another Hohenheim or an
  off-the-shelf NSD/Knot (it speaks standard AXFR/TSIG/NOTIFY).

## Model

- `dns_zones.role` = `primary` | `secondary`. A secondary zone carries
  `primary_peer_id` and transfer bookkeeping (`transfer_status`,
  `transfer_message`, `last_checked_at`, `last_transfer_at`).
- `dns_peers`: a federation peer -- a DNS transfer channel (`transfer_host`,
  `transfer_port`, `tsig_key_name`, `tsig_algorithm`, `tsig_secret`) and,
  reserved for the future edit-forwarding layer, an HTTPS admin `base_url` +
  `api_key`.
- `dns_zone_peers`: which peers a PRIMARY zone is replicated to -- both its
  NOTIFY targets and the set of TSIG keys authorized to AXFR it.

## Wire behavior

Primary side (`AxfrResponder`, wired into the TCP listener): answers AXFR only
when the request is TSIG-signed by a key belonging to a peer linked to the
zone; the response stream is TSIG-signed (dnsjava `StreamGenerator`).
Everything else is REFUSED. `DnsNotifier` sends a NOTIFY (best-effort UDP,
TSIG-signed) to each linked secondary whenever the zone's serial bumps -- and
every primary edit and every ACME TXT publish funnels through
`DnsZoneStore.bumpSerialAndReload`, so both trigger it.

Secondary side (`SecondaryZoneService`): the persisted replica is restored and
served at boot (see below), then a refresh-timer poll (SOA-serial check, then
AXFR only if the primary advanced) and a serial-checked pull when a NOTIFY
arrives. Inbound NOTIFY is authenticated: when the zone's primary peer has a
TSIG key, an unsigned or wrongly-signed NOTIFY is ignored (RFC 1996 posture),
and concurrent notifies for the same zone coalesce into one queued pull, so
spoofed NOTIFY floods cannot make the secondary hammer its primary. RFC 1982
serial arithmetic; refresh/retry from the SOA; past the SOA expire window
(measured from the last SUCCESSFUL transfer, surviving restarts via the
persisted timestamp) the replica stops being served but keeps retrying.
Transferred zones are compiled straight into serving snapshots
(`DnsZoneStore.snapshotFromTransfer` + `putSecondarySnapshot`) -- they never
touch the local record table, so a secondary is inherently read-only here.

Replica persistence: every successful transfer also stores the zone as
master-file text on the zone row (`replica_records` + `last_transfer_at`).
After a restart the secondary serves that replica immediately -- even when the
hidden primary is unreachable -- until the SOA expire window closes. This is
the same posture as an NSD/Knot secondary's zone file on disk.

The serving view is the merge of DB-built primary zones and transferred
secondary zones, so a primary edit or an incoming transfer each rebuild only
their half. Deleting, disabling or role-flipping a secondary zone prunes its
snapshot on the next reload, so it stops being answered immediately.

## ACME across secondaries

Let's Encrypt validates DNS-01 against the delegated (public) nameservers --
the secondaries, not the hidden primary. So after the internal publisher
writes a challenge TXT and bumps the serial (which NOTIFYs the secondaries),
`InternalDnsTxtPublisher` blocks until every linked secondary serves the new
serial (bounded poll) before returning, so validation never races propagation.
A zone with no secondaries keeps the old serves-immediately behavior.

## Operator setup (hidden-primary + VPS-secondary)

1. On BOTH boxes create a `dns_peer` for the other, sharing one TSIG key
   (name + algorithm + base64 secret) and the other's transfer host/port.
2. Office (primary): own the zone (`role=primary`), and on the zone's
   Secondaries tab attach the VPS peer -- this authorizes its AXFR and makes
   it a NOTIFY target.
3. VPS (secondary): create the same-origin zone with `role=secondary` and
   `primary_peer` = the office peer. It pulls immediately and on every change.
4. Registrar: delegate the domain to the VPS's public nameserver(s); glue
   record for an in-bailiwick NS. Open UDP+TCP 53 on the VPS only.
5. Office firewall: allow the transfer port inbound from the VPS IP only;
   port 53 stays fully closed to the world.

## Verification

`DnsFederationTest` (real sockets): authorized AXFR transfers the zone,
a wrong TSIG key is refused, NOTIFY reaches a linked secondary, a full
secondary replication is pulled and then answered from this instance's own
listener with the zone row marked transferred, and an unreachable primary
marks the secondary errored.

## Not yet built (next slice): central editing

A secondary zone's records are read-only on the replica; edit them on the
owning primary. The reserved `dns_peers.base_url` + `api_key` are for the
planned edit-forwarding layer: a single instance would edit a peer-owned
zone's records by forwarding the change to the owner over an authenticated
HTTPS API, which applies it, bumps the serial, and NOTIFYs -- so the replica
reflects it within seconds. Single-owner-per-zone stays the invariant; no
multi-primary editing.

Also still open: DNSSEC (a separate security project) and response-rate-
limiting on the public listeners.
