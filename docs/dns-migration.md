# DNS zone migration

How a zone moves from its current provider (FreeDNS/afraid.org, in every case
we have) onto Hohenheim's authoritative nameservers without a visible outage,
and how each step is PROVEN rather than assumed.

The gate is `tools/hoh-dns-diff`. It is a single-file node script, stdlib only,
carrying its own DNS wire codec: the questions a cutover has to answer -- is
this server AUTHORITATIVE for the zone, what did the PARENT publish, what RCODE
came back -- are exactly the ones `node:dns`'s `Resolver` cannot express (no AA
flag, no `+norec`, no authority section). It never writes anything, anywhere.

```
hoh-dns-diff compare <zone> --old <server[,server]> --new <server[,server]>
        [--names <file|a,b,c>] [--zone-file <bind file>]
        [--types A,AAAA,CNAME,MX,TXT,NS,SRV,CAA,SOA] [--strict] [--timeout 5]

hoh-dns-diff delegation <zone> [--expect-ns ns1.x,ns2.x] [--timeout 5]

hoh-dns-diff propagate <name> <type> --expect <value>
        [--resolvers 1.1.1.1,8.8.8.8,9.9.9.9] [--deadline 900] [--interval 15]
```

Exit codes: `0` agreement, `1` a difference or a broken delegation, `2` the tool
could not run. `compare` and `delegation` are safe to put in a checklist that a
human signs off; neither is safe to read from the summary line alone, because
what they classify as "expected" is documented below.

## What `compare` treats as a difference, and what it does not

- **Record ORDER and owner CASE are never a difference.** RRsets are sorted and
  owners lowercased before comparison; TXT and CAA VALUES keep their case,
  because a DKIM key is case-sensitive.
- **TTL is a WARNING, never a difference.** Two providers serving the same data
  on different TTLs are the same zone. `--strict` promotes it to a failure --
  use that only when you deliberately pre-lowered TTLs and want to prove it.
- **The SOA serial is reported, never compared.** The rest of the SOA (MNAME,
  RNAME, refresh, retry, expire, minimum) IS compared.
- **The apex NS RRset is its own verdict class, `apex-ns`, and does not fail the
  run.** Before a cutover it is EXPECTED to differ: the old provider publishes
  its own names and you are about to publish yours. `--strict` fails on it, which
  is what you want in the final post-cutover run.
- **NXDOMAIN is an answer.** Any other non-zero RCODE (REFUSED, SERVFAIL) is
  `error`, never an empty RRset -- "this server would not answer" and "this name
  does not exist" are different facts and the tool never blurs them.
- A question where the side that DID answer serves nothing either produces no
  row at all, so one unserved server cannot inflate into a row per question.

## The name list is the whole gate

`compare` only knows about names you give it. DNS has no way to enumerate a
zone without AXFR, so the name list is the union of:

1. every owner in `--zone-file` (a BIND file: `$ORIGIN`, `@`, blank continued
   owners and parenthesised SOAs are all handled),
2. every `--names` entry (a comma list or a file of names, bare labels are
   qualified against the zone),
3. the apex.

**Export the zone from the old provider and pass it as `--zone-file`.** A name
you forget is a name the gate cannot prove, and it will be the one that breaks.

## Per-zone cutover procedure

1. **Export** the zone from the current provider as standard zone-file text.
   Keep the file: it is both the import payload and the `--zone-file` name list.

2. **Import** it on the Hohenheim PRIMARY, on the zone's Zone-file tab (the
   paste form, `DnsZoneFiles.importText`). What it does, exactly:
   - it REPLACES every operator-managed record in the zone (rows with no
     `managed_by`); Hohenheim-generated rows are untouched,
   - it DROPS the SOA from the file -- zone metadata lives on the zone row and
     the serial is framework-managed,
   - it bumps the serial and reloads the zone,
   - anything it could not parse is named in the partial-import warning. Read
     that warning; a skipped line is a record the gate will report as
     `only-old` later, and finding it here is cheaper.

3. **Rewrite the apex NS rows.** Nothing generates them: the responder serves
   whatever NS rows the record table holds (`DnsZoneSnapshot`), so an imported
   zone still publishes the OLD provider's nameserver names. Delete those rows
   and add one NS row per Hohenheim nameserver name. If the nameserver names are
   in-bailiwick (`ns1.<zone>`), add their A/AAAA records too -- the registrar
   will demand glue for them.

4. **Replicate** to the secondaries (see `authoritative-dns.md`) and let them
   pull. Every listed nameserver must serve the zone BEFORE the delegation moves.

5. **Compare until IDENTICAL**, against every new server, from the old provider:

   ```
   hoh-dns-diff compare example.com \
       --old ns1.afraid.org --new 137.74.171.228,104.223.42.142 \
       --zone-file example.com.zone
   ```

   Expect exactly one non-identical row: `apex-ns`. Anything else is work you
   still owe. When only `apex-ns` remains, the data is ready.

6. **Change the registrar's delegation** to the Hohenheim nameserver names, with
   glue where they are in-bailiwick. This is the irreversible step; everything
   before it is reversible by doing nothing.

7. **Prove the delegation**, from the root down:

   ```
   hoh-dns-diff delegation example.com --expect-ns ns1.hohenheim.example,ns2.hohenheim.example
   ```

   It walks the root hints to the parent, reads the parent's delegation NS RRset
   and its glue, then asks EVERY delegated nameserver for the zone's SOA with
   recursion off, and reports per server: authoritative / not authoritative
   (LAME) / no answer, its serial, whether the parent's set equals the zone's own
   apex NS set, missing glue for in-bailiwick names, serial skew, and whether a
   DS is published at the parent. Lameness is judged per NAMESERVER, not per
   address: an address this machine has no route to (IPv6 from the workstation)
   is a warning about the PROBE, not a verdict about the zone.

8. **Watch propagation** of one changed name until every public resolver agrees:

   ```
   hoh-dns-diff propagate www.example.com A --expect 10.0.0.1
   ```

9. **Re-run `compare --strict`** once the old provider is still up but the
   delegation has moved. With `--strict` the apex NS must now MATCH, and any
   remaining TTL drift is surfaced. Only then retire the zone at the old
   provider.

Keep the old provider's zone alive for at least the old SOA expire (afraid's is
2419200 seconds, 28 days) or until `propagate` has been clean for a full day.
Deleting it early turns a stale cached delegation into an outage.

## Mail zones: the MX/TXT lines ARE the gate

For any zone that carries mail, the records that matter are the ones nobody
looks at:

- `MX` at the apex (and any subdomain that receives mail),
- the SPF `TXT` at the apex (`v=spf1 ...`),
- every DKIM selector, at `<selector>._domainkey.<zone>` -- these are the ones a
  name list forgets, because their owners appear nowhere except the zone file
  and the mail provider's dashboard,
- `_dmarc.<zone>` `TXT`,
- any `TXT` a third party verifies against (`google-site-verification=...`,
  `MS=...`): losing one silently de-verifies the domain weeks later,
- `CNAME`s a hosted mail provider requires (autodiscover, autoconfig).

A dropped DKIM selector does not bounce mail -- it silently downgrades it to
"unauthenticated", which is worse, because nothing reports it. So: pass the
exported zone file as `--zone-file`, and additionally name every selector you
know about in `--names`. Getting `IDENTICAL` on a mail zone without the DKIM
owners in the name list proves nothing about mail.

## The FreeDNS trap: nameserver names live in someone else's zone

`starfleet.life` is delegated to `nssl.mooo.com` and `nssl2.mooo.com`. Those
names are inside **afraid.org's own `mooo.com` zone**, not inside
`starfleet.life`. Three consequences:

- There is no glue at the `life.` parent and there does not need to be: the
  names are out-of-bailiwick, so a resolver looks them up in `mooo.com`. The
  `delegation` output showing `glue (none)` for such a zone is CORRECT, not a
  finding.
- We cannot change what those names resolve to. The moment the delegation moves
  to our own nameserver names, afraid's records for `nssl*.mooo.com` stop being
  part of our story -- but any resolver still holding the old delegation keeps
  following them to afraid's servers, which will still be answering. Both
  answers are live at once during the overlap, which is exactly why step 9 above
  compares them instead of assuming.
- The imported zone's SOA MNAME will read `nssl.mooo.com.` -- afraid's primary.
  `importText` drops the SOA, so this fixes itself; but if you ever hand-copy an
  SOA, that MNAME is wrong the moment we are primary.

Measured 2026-08-29: both `nssl.mooo.com` and `nssl2.mooo.com` resolve to the
SAME address (104.223.42.142), so `starfleet.life` has two nameserver NAMES and
one nameserver HOST -- no redundancy at all today. Moving to two genuinely
separate Hohenheim servers is an availability improvement, not just a move.

## A parent that publishes fewer nameservers than the zone

`delegation 11ways.be` reports a set MISMATCH: `.be` publishes three of
afraid's nameservers (`ns1`, `ns2`, `ns3`) while the zone's own apex NS RRset
lists four (`ns4` too). That is a real and long-standing inconsistency at the
registrar, not a tool artifact, and it is exactly the class of thing that goes
unnoticed until a cutover. Resolvers use the PARENT's set, so `ns4` receives no
traffic. Expect the same shape on the other afraid-hosted zones, and treat the
post-cutover `delegation` run as the moment it gets fixed rather than carried
over.

## Limitations you must not read past

- The tool asks only for the (name, type) pairs you gave it. It cannot discover
  names, and it does no AXFR.
- It compares what servers ANSWER, not what they store: a zone whose data is
  identical but whose wildcard or delegation behaviour differs can still pass.
  Nothing here exercises a wildcard, a subzone delegation or DNSSEC validation.
- `delegation` resolves out-of-bailiwick nameserver names through `1.1.1.1`. A
  resolver-level difference somewhere else in the world is invisible to it.
- A DS check reports presence at the parent only. It does not validate a chain.
- Everything is judged from ONE vantage point, this machine, over IPv4 in
  practice. `propagate` across the three big public resolvers is the only
  multi-vantage evidence the tool produces.

Tests: `node --test "tools/*.test.js"`. The delegation walk and the compare loop
take their query function as an argument, so every classification above is
proven offline against a fake network, and the wire codec is proven against a
real captured answer.
