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

## Health: secondary freshness and delegation (shipped 2026-08-30)

The transfer bookkeeping above is what a SECONDARY believes about itself. A
secondary that silently stopped pulling (dead peer row, firewall change, a
NOTIFY nobody answers) is invisible from there, so the PRIMARY keeps its own
view on every `dns_zone_peers` link: `ProbeDnsSecondaries` (every 5 minutes)
sends a real SOA query to the peer's transfer host/port and stores
`served_serial`, `probed_at` and `probe_error`; `behind_since` is stamped by
the first probe that finds the peer behind (RFC 1982) or silent and cleared
when it serves our serial again. A lag older than
`DnsSecondaryFreshness.STALE_AFTER` (15 minutes; a constant, because a knob
would only hide a broken secondary) raises a warning attention item naming
peer and zone, linking to the zone's Secondaries tab, and ONE
`dns_secondary_stale` alert per lag (`stale_alerted_at`, cleared on catch-up).
The Secondaries tab shows a freshness pill (not probed / current / behind /
stale), the served serial and the probe time per link.

The primary also traces what IT did for each peer (`DnsFederationTrace`; the
first real starfleet-to-OVH run showed a primary journaling nothing for a
transfer it had just streamed): one `dns.axfr_served` / `dns.axfr_refused`
structured log line per AXFR request (zone, serial, TSIG key name, ok or the
refusal reason) and one `dns.notify_sent` per NOTIFY (zone, serial, peer, the
ack's rcode / `timeout` / the send error), with the last of each stamped on the
link (`last_axfr_at`, `last_axfr_serial`, `last_notify_at`,
`last_notify_outcome`) and shown on the Secondaries tab. A served AXFR is
attributed to the peer by the TSIG key name the request carried.

`CheckDnsDelegations` (hourly) judges each primary zone from the parent's side:
`SystemDelegationLookup` finds the parent zone's nameservers through the
system resolver, `DelegationCheck` asks one of them for the zone's NS with
recursion OFF (a recursive answer would come from our own servers and could
never show a registrar-side mismatch), reads the delegation and glue from the
referral, compares it with the apex NS rows we serve, and asks every delegated
server for the zone SOA. Verdicts are the closed `DelegationVerdict`
vocabulary, least severe first: matches, parent unreachable (inconclusive,
never healthy), not delegated, listed-not-delegated, delegated-not-listed,
stale serial (a delegated server answers behind our serial), missing glue
(in-bailiwick NS without a glue address), lame (a delegated server does not
answer authoritatively). The worst verdict
and one line per finding land on `dns_zones.delegation_status`,
`delegation_detail` and `delegation_checked_at` (read-only in the zone form,
a badge column in the list); a verdict with a severity is an attention item
whose detail is the verdict label, and the `dns_delegation_broken` alert fires
on a CHANGE of verdict only. A zone without an apex NS RRset is skipped (the
"no NS records" item already covers it). The zone row action "Check health"
runs both checks on demand and reports the verdict and how many secondaries
are behind.

During a zone migration the expected sequence of verdicts is:
delegated-not-listed + listed-not-delegated while the registrar still points
at the old provider, then matches after the NS change. Verification:
`DnsFederationHealthTest` (real sockets, fake parent and secondary
nameservers on loopback).

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
editing, DNSSEC, response-rate-limiting, and dynamic DNS are all implemented.

## Dynamic DNS (dyndns2)

An A or AAAA record can be made *dynamic*: the mint row action (offered only
on address records) creates the record's credential row in
`dns_dyndns_credentials` and discloses the plaintext `hdyn_` token exactly
once in its toast -- only the sha256 digest is at rest, re-minting rotates it,
and the revoke row action (offered only while a credential exists) deletes it.
The public endpoint

    GET /nic/update?hostname=<fqdn>&myip=<ip>

speaks the de-facto dyndns2 protocol, so routers, ddclient, and any existing
DDNS client work by pointing at it with the token as the HTTP Basic password
(the token is also accepted as the username; a `?token=` query param is
deliberately refused -- credentials do not travel in URLs). Replies are
the bare `good <ip>` / `nochg <ip>` / `badauth` / `nohost` lines clients
expect. `myip` is honored when present, otherwise the trusted-proxy-resolved
caller IP is used; the IP family must match the record type (an A record takes
IPv4, AAAA takes IPv6, a comma-separated dual-stack `myip` picks the matching
one). An unchanged address returns `nochg` WITHOUT bumping the serial, since
clients poll every few minutes. A changed address rewrites the record row and
rides the normal serial-bump -> re-sign -> NOTIFY path, so secondaries and
DNSSEC signatures stay correct with no special-casing. Updates act only on the
owning primary; a hit on a replica returns `!yours`. The endpoint is public
(the token is the credential) and rate-limited per IP.
