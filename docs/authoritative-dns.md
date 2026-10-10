# Optional Authoritative DNS

Moving an existing zone onto these nameservers is its own procedure with its own
gate: see `dns-migration.md` and `tools/hoh-dns-diff`.

## What is implemented

- Zone and record models with validation, CMS resources, zone-file import and
  export, and immutable serving snapshots. One malformed record does not take
  its whole zone out of the serving snapshot.
- Authoritative UDP and TCP serving.
- The `internal` ACME TXT publisher and DNS-01 renewal.
- The standards-based replication described in `dns-federation.md`:
  TSIG-authenticated AXFR in both directions, NOTIFY, a secondary-zone
  subsystem with SOA refresh/retry/expire discipline, per-zone primary and
  secondary roles, a peer registry, and an ACME propagation wait so DNS-01
  issuance blocks until the secondaries serve the challenge. This lets
  Hohenheim run as a hidden primary behind a closed port 53, with a public
  secondary (another Hohenheim, or an off-the-shelf NSD/Knot) meeting the
  two-nameserver production threshold.
- Central editing: a secondary zone's Records tab reads the owning peer's
  records live over its authenticated HTTPS API and forwards edits to it (see
  `dns-federation.md`), so one instance can be the single pane for every
  federated zone.
- DNSSEC: per-zone online signing with an ECDSA P-256 CSK (algorithm 13).
  Enabling `dnssec` on a zone mints a key on first use, signs every
  authoritative RRset, publishes an apex DNSKEY, and builds an NSEC chain for
  authenticated denial; RRSIG/NSEC/DNSKEY are served only to DO-bit queries,
  and the DS record for the registrar is shown on the zone's Zone-file tab. A
  daily task re-signs before the 14-day RRSIG window closes and bumps each
  zone's serial while doing so: secondaries replicate signed records verbatim
  and only pull when the serial advances, so a silent re-sign would leave
  replicas serving RRSIGs until they expire. NXDOMAIN responses carry both the
  qname-covering NSEC and the NSEC denying the wildcard at the closest
  encloser; wildcard answers are served with the RRSIG rewritten to the
  synthesized owner plus the NSEC proving the exact name does not exist; DS
  queries at a delegation are answered authoritatively by the parent.
- Response-rate-limiting on the UDP listener (`dns.rate_limit_per_second`).
  Verdicts key on the computed response, with NXDOMAIN bucketed per zone and
  referrals per delegation point, so random-subdomain floods cannot dodge the
  limit.
- Released hostnames have a DNS consequence, the counterpart of the
  certificate tier's orphan sweeper. `DnsClaimReleases` disables a released
  name's non-generated records, clears their dyndns credentials and revokes
  their record grants in the same transaction as the release (site soft
  delete, domain row delete or rename); a name still covered by another live
  domain row, or belonging to a merely DISABLED site, is untouched. Without
  it, a departed tenant's records kept being served and a dyndns token minted
  under a claim kept rewriting the record after the claim was released.
- The dyndns credential (its own `dns_dyndns_credentials` table; a credential
  row IS the dynamic flag, only the sha256 digest at rest) is grant-gated on
  the model write pipeline, so hostname authority alone cannot arm a token.
- A CNAME at the zone apex is refused: the synthesized SOA is not a row, so
  the sibling scan would never see the conflict.
- Secondary freshness. `ProbeDnsSecondaries` (every 5 minutes, DNS role) asks
  each linked secondary of every primary zone for the zone SOA over its
  transfer channel and records on the `dns_zone_peers` link what it serves
  (`served_serial`, `probed_at`, `probe_error`, `behind_since`,
  `stale_alerted_at`); a link behind or silent for longer than
  `DnsSecondaryFreshness.STALE_AFTER` (15 minutes, a constant) is a WARNING
  attention item and one `dns_secondary_stale` alert per lag.
- Delegation health. `CheckDnsDelegations` (hourly, DNS role) runs
  `DelegationCheck` for every primary zone: the parent's NS RRset and glue
  read with recursion off, compared with the apex NS rows, then every
  delegated server asked for the zone SOA. The closed verdict vocabulary is
  `DelegationVerdict` (matches, parent unreachable, not delegated,
  listed-not-delegated, delegated-not-listed, stale serial, missing glue,
  lame); the worst verdict plus one line per finding lands on
  `dns_zones.delegation_status/detail/checked_at`, a verdict with a severity
  is an attention item, and the `dns_delegation_broken` alert fires only when
  the verdict CHANGES. The zone row action "Check health" runs both on demand.
- `AttentionCollector`'s `dnsIssues` raises an ERROR item for a DNS listener
  that failed to bind, linking to settings and naming the startup error, plus
  a WARNING for an enabled zone whose apex carries no NS RRset (this checks
  OUR zone data, not the parent's delegation).

Not implemented: an attention item or alert for ACME records that failed to
publish, and DNSSEC key rollover.

Hohenheim can become the authoritative DNS service for zones it manages. This
removes the runtime dependency on a hosted DNS control panel and gives ACME
DNS-01 a first-party TXT publisher, but it does not replace the domain
registrar: the registrar still delegates the domain to Hohenheim's name
servers.

## Operational contract

Production authoritative DNS is not just another HTTP listener:

- Every delegated name server must answer on public UDP **and** TCP port 53.
- The service is authoritative-only. It must never offer recursion for names
  outside its zones.
- A production delegation needs at least two name servers on different IPs;
  proper operation puts them on different networks. A single home server is an
  explicit experimental/single-point-of-failure mode.
- A home installation needs stable, globally routable addresses and port 53
  allowed by the ISP, router and firewall. Carrier-grade NAT cannot host it.
- In-bailiwick names such as `ns1.example.com` need matching glue records at
  the registrar. Delegation NS records, authoritative NS records and glue must
  agree.

These are protocol/registry constraints, not product preferences. See the
[IANA authoritative name-server requirements](https://www.iana.org/help/nameserver-requirements),
[RFC 1035](https://www.rfc-editor.org/info/rfc1035), and
[ICANN's glue-record definition](https://www.icann.org/en/icann-acronyms-and-terms/glue-record-en).

## Recommended architecture

Keep the first implementation in Hohenheim: it has one concrete consumer and
is tightly integrated with sites, domains and certificates. Promote a generic
mechanism only after a second application needs it.

Persist two normal models:

- `DnsZone`: origin, SOA primary/contact, serial, default/negative TTL, enabled
  state and optional secondary/transfer policy.
- `DnsRecord`: zone, owner name, type, TTL, value, a type-schema'd `data`
  column for type-specific extras (MX priority; SRV priority/weight/port --
  the TYPE enum value declares the sub-schema, `SchemaField.schemaFrom`), and
  enabled state. Multiple rows form one RRset.

The first record vocabulary should cover SOA, NS, A, AAAA, CNAME, MX, TXT, CAA
and SRV. Wildcard owner names are ordinary authoritative records. The CMS
resource should validate zone containment, CNAME exclusivity, apex rules,
addresses and type-specific values before persistence.

The serving side is a dedicated authoritative-only service:

1. Bind configurable public UDP and TCP listeners (default port 53).
2. Parse and emit DNS wire messages with a maintained protocol library such as
   dnsjava; keep zone lookup, authority and policy in Hohenheim.
3. Answer with AA set, correct NXDOMAIN versus NODATA behavior, SOA authority
   data, wildcard synthesis, CNAME processing, EDNS sizing and UDP truncation
   with TCP retry.
4. Build immutable in-memory zone snapshots from the database. A committed
   record change bumps the SOA serial and atomically swaps the snapshot.
5. Refuse recursion, out-of-zone updates and unrestricted zone transfers.

Do not expose a recursive resolver. That is a different security and caching
product and would turn Hohenheim into an amplification target.

## Redundancy and transfers

The useful production shape is Hohenheim as primary plus at least one secondary
name server. Transfers use authenticated AXFR plus NOTIFY; IXFR is not
implemented. AXFR is TCP-only and NOTIFY is the standard prompt-refresh
mechanism; see [RFC 5936](https://www.rfc-editor.org/info/rfc5936) and
[RFC 1996](https://www.rfc-editor.org/info/rfc1996). Transfers must be limited
by address and TSIG. Secondary freshness is probed and a stale secondary is an
attention item and an alert (see What is implemented).

The secondary can be another Hohenheim instance or an existing secondary
implementation; the latter gives redundancy without making distributed
Hohenheim state a prerequisite.

## Hohenheim integration

The existing `DnsTxtPublisher` registry is the integration seam. An `internal`
publisher will transactionally add the exact TXT value (without replacing
other simultaneous values), bump the zone serial, wait until the authoritative
listeners serve it, and remove only that value after ACME finishes. Wildcard
certificates then renew automatically without provider credentials or a shell
hook. Let's Encrypt explicitly permits multiple TXT values and DNS delegation;
see its [DNS-01 documentation](https://letsencrypt.org/docs/challenge-types/).

The `internal` publisher is only offered where this instance can actually write
the zone. A zone this instance is PRIMARY for is written locally. A zone it only
REPLICATES is written on its owning primary: when that primary is a Hohenheim
peer with a stored admin API key, the challenge is created there over the peer
record API and this instance waits until its own replica transfers it -- the
transfer is what proves the CA will see it -- and cleanup deletes it over the
same channel. The forwarded row carries the same `managed_by=acme` stamp a
locally issued challenge gets -- the peer record API accepts `managed_by` on
create for exactly the values the model declares, and refuses any other -- so
the primary can tell a machine-owned challenge from a hand-typed record, and a
zone-file import there (which replaces only unstamped rows) leaves it alone. A
replicated zone whose primary is a plain nameserver (or a
Hohenheim peer with no admin credentials) is NOT publishable: the certificate
request is refused naming the owning peer, and the operator requests it on that
instance, or uses HTTP-01 or the manual DNS mode instead. A replica's serial
belongs to its primary and is never bumped locally.

The product flow should also weave existing features together:

- Offer to create A/AAAA/CNAME records from a site's domains and listener IPs.
- Show DNS coverage and delegation health beside domain and certificate status.
- Allow a site-level wildcard record deliberately; never create one silently.
- Add attention items for lame delegation, unreachable TCP/UDP listeners,
  stale secondaries, missing glue and ACME records that failed to publish.
- Keep manual records editable; generated records declare ownership so site
  changes update only records Hohenheim owns.

## Layering

Each layer builds on the one before it:

1. Zone/record models, validation, CMS resources, import/export of standard
   zone-file text, and immutable snapshots.
2. Authoritative UDP+TCP serving with protocol conformance tests. A delegation
   is only trustworthy once an external probe verifies it over both transports.
3. Internal ACME TXT publisher and DNS-01 renewal integration.
4. AXFR + TSIG + NOTIFY and secondary health; this is the production-ready
   threshold.
5. DNSSEC signing. An unsigned but correct zone is safer than an incomplete
   DNSSEC implementation, so signing is opt-in per zone.

## Declared nameservers

`dns.nameservers` is the controller's declared nameserver set (`DnsNameservers`):
a new primary zone is seeded with one apex NS row per name, a zone-file import
substitutes them for the file's apex NS set unless told to keep it, and the
delegation check reports a served apex set that disagrees with them as
`apex_undeclared`. Seeded once, never re-asserted: the apex NS rows stay ordinary
records an operator may edit. The migration procedure that relies on it is
`docs/dns-migration.md`.
