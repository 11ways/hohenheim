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

## Central editing (edit forwarding)

One instance can be the single pane for every federated zone.
Single-owner-per-zone stays the invariant; no multi-primary editing.

Owner side: `/api/dns/zones/{origin}/records` (list; POST create; POST
`/{id}` update; POST `/{id}/delete`) on the admin server, gated to znit_
API-key principals (csrfExempt is safe for exactly that reason), primary
zones only (a replica answers 409 `not_primary`, so a fork is impossible).
Every mutation runs the same `DnsRecordEdits.validate` pipeline as the CMS
resource, is activity-logged with origin `api`, and bumps the serial --
which NOTIFYs the secondaries, so the editing instance's own replica
catches up within seconds. Validation refusals answer 422 with the
violation's microcopy key.

Viewing side: a SECONDARY zone's Records tab reads the owner's records LIVE
through `DnsPeerApi` (the peer's `base_url` + `api_key`) and renders
add/edit/delete forms that POST to `/admin/dns-zones/{id}/remote-records`,
which forwards to the owner and round-trips validation refusals by microcopy
key (same catalogs on both instances). When the peer is unconfigured or
unreachable, the tab degrades to the read-only replica snapshot -- DNS keeps
serving; only editing needs the owner online.

Verification: `DnsCentralEditTest` (real HTTP) covers the API CRUD +
serial bumps + session-cookie refusal + 422s + the 409 replica guard, and
the read-through/forwarding/fallback flows against a scripted peer.

Signed zones federate unchanged: a primary's DNSKEY, RRSIGs and NSEC records
ride the existing AXFR as ordinary records, and the secondary detects the
apex DNSKEY and serves them as a signed zone (it does not re-sign -- the
private key never leaves the owning primary). The daily re-sign task bumps
each signed primary zone's serial so replicas pull the fresh RRSIGs long
before the 14-day signature window closes; RRSIG churn between two builds of
the same records is inherent to online signing. See `authoritative-dns.md`
for DNSSEC itself.

The DNS story is now feature-complete: authoritative serving, ACME DNS-01,
zone-file import/export, federation (hidden primary + secondaries), central
editing, DNSSEC, and response-rate-limiting are all implemented.
