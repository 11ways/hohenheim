# Optional Authoritative DNS

STATUS (2026-07-17): delivery phases 1-4 below are implemented. Phase 4 is
the standards-based replication described in `dns-federation.md`: TSIG-
authenticated AXFR (both directions), NOTIFY, a secondary-zone subsystem with
SOA refresh/retry/expire discipline, per-zone primary/secondary roles, a peer
registry, and an ACME propagation wait so DNS-01 issuance blocks until the
secondaries serve the challenge. This lets Hohenheim run as a hidden primary
behind a closed port 53, with a public secondary (another Hohenheim, or an
off-the-shelf NSD/Knot) meeting the two-nameserver production threshold.
Central editing is also implemented: a secondary zone's Records tab reads the
owning peer's records live over its authenticated HTTPS API and forwards
edits to it (see `dns-federation.md`), so one instance can be the single pane
for every federated zone.

DNSSEC (phase 5) is implemented too: per-zone online signing with an ECDSA
P-256 CSK (algorithm 13). Enabling `dnssec` on a zone mints a key on first
use, signs every authoritative RRset, publishes an apex DNSKEY, and builds an
NSEC chain for authenticated denial; RRSIG/NSEC/DNSKEY are served only to
DO-bit queries, and the DS record for the registrar is shown on the zone's
Zone-file tab. A daily task re-signs before the 14-day RRSIG window closes
and bumps each zone's serial while doing so: secondaries replicate signed
records verbatim and only pull when the serial advances, so a silent re-sign
would leave replicas serving RRSIGs until they expire. NXDOMAIN responses
carry both the qname-covering NSEC and the NSEC denying the wildcard at the
closest encloser; wildcard answers are served with the RRSIG rewritten to the
synthesized owner plus the NSEC proving the exact name does not exist; DS
queries at a delegation are answered authoritatively by the parent.
Response-rate-limiting on the UDP listener (`dns.rate_limit_per_second`)
rounds out the abuse mitigations; verdicts key on the computed response, with
NXDOMAIN bucketed per zone and referrals per delegation point so
random-subdomain floods cannot dodge the limit. The DNS story is feature-complete.

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
- `DnsRecord`: zone, owner name, type, TTL, value, priority/weight/port where
  relevant, and enabled state. Multiple rows form one RRset.

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
name server. Implement authenticated AXFR first, followed by NOTIFY; IXFR can
come later. AXFR is TCP-only and NOTIFY is the standard prompt-refresh
mechanism; see [RFC 5936](https://www.rfc-editor.org/info/rfc5936) and
[RFC 1996](https://www.rfc-editor.org/info/rfc1996). Transfers must be limited
by address and TSIG. The UI should show secondary freshness and warn loudly
when a production zone has no healthy secondary.

Running two Hohenheim instances is a later topology. V1 can interoperate with
an existing secondary implementation, which gives redundancy without making
distributed Hohenheim state a prerequisite.

## Hohenheim integration

The existing `DnsTxtPublisher` registry is the integration seam. An `internal`
publisher will transactionally add the exact TXT value (without replacing
other simultaneous values), bump the zone serial, wait until the authoritative
listeners serve it, and remove only that value after ACME finishes. Wildcard
certificates then renew automatically without provider credentials or a shell
hook. Let's Encrypt explicitly permits multiple TXT values and DNS delegation;
see its [DNS-01 documentation](https://letsencrypt.org/docs/challenge-types/).

The product flow should also weave existing features together:

- Offer to create A/AAAA/CNAME records from a site's domains and listener IPs.
- Show DNS coverage and delegation health beside domain and certificate status.
- Allow a site-level wildcard record deliberately; never create one silently.
- Add attention items for lame delegation, unreachable TCP/UDP listeners,
  stale secondaries, missing glue and ACME records that failed to publish.
- Keep manual records editable; generated records declare ownership so site
  changes update only records Hohenheim owns.

## Delivery order

1. Zone/record models, validation, CMS resources, import/export of standard
   zone-file text, and immutable snapshots.
2. Authoritative UDP+TCP serving with protocol conformance tests; explicitly
   experimental until an external probe verifies delegation and both transports.
3. Internal ACME TXT publisher and DNS-01 renewal integration.
4. AXFR + TSIG + NOTIFY and secondary-health UI; this is the production-ready
   threshold.
5. DNSSEC signing and key rollover only as a separate security project. An
   unsigned but correct zone is safer than an incomplete DNSSEC implementation.
