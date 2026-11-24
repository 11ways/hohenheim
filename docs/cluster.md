# Linked Hohenheim servers

Several complete Hohenheims can be linked so one panel manages them all later. This page describes the link
itself: how two servers join, what they say to each other, the states a link shows and how it ends, and what a
master remembers of the servers it manages. Lists that mix servers and pages of another server's things come later.

## Words

- **Server**: one complete Hohenheim install, with its own database, panel, certificates, apps and accounts.
- **Master**: the server whose panel manages another. **Linked server**: a server it manages.
- A server has at most one master. A server with a master manages no other servers, and a server that manages
  others cannot get a master. Each refusal says so in words.
- A server's own name is the setting `hohenheim.cluster.name` (Settings, Installation). Left empty, the server is
  named after its machine's host name up to the first dot. The master also needs its own https address,
  `network.main_url`: the managed server calls it there.

## Joining

1. On the server that will be managed, an administrator opens Servers and presses **Let another Hohenheim manage
   this server**, beside **Add a Hohenheim server**; it is unavailable, with the reason, on a server that already
   has a master or manages others. A join code of twelve letters is shown once, large, in the card under the list,
   in groups of four with Copy and until when it is valid (ten minutes), owned by that account (core `OneTimeCodes`;
   only a keyed digest is stored, in `linked_server_codes`). A reload shows it no more; the card then says a code is
   waiting.
2. On the master, **Add a Hohenheim server** takes a name, the other server's https address and the code.
3. The master calls the other server's **join**. Inside the one transaction that redeems the code, the managed server
   makes its row of the master, takes its code numbers from it (see "One code per thing") and renumbers; any failure
   rolls all of that back and refuses the join. Both servers then hold a row in `linked_servers`: the master one with
   role `manages`, the managed server one with role `managed_by`.

Every creation of a link row, the join answered on the managed server and the add on the master, runs one at a
time under one core lease (`hohenheim:cluster_link`), its checks inside it: one master, a managed server manages
nobody, the name (ignoring case) and the address are free. Two joins at the same instant never both succeed; the
second is refused in words.

Each side keeps the key it presents (encrypted) and only a keyed digest of the key it accepts. A key proves which
server asks; it grants no permission. Each side also pins the other's controller identity
(`ControllerIdentity.token()`).

## The calls on the wire

Every call is a `POST` with a JSON body over https; redirects are never followed and the certificate is verified.
Linked servers are reached on the public internet only, unless the host declares
`hohenheim.cluster.allow_private_networks` in its host network declaration.

| Call | Path | Credential | Answer |
| --- | --- | --- | --- |
| join | `/api/cluster/join` | the join code in the body | `name`, `token`, `build`, `time` and the key the master presents from now on; or `refusal` |
| status | `/api/cluster/status` | `Authorization: Bearer <link key>` | `name`, `token`, `build`, `time` |
| leave | `/api/cluster/leave` | `Authorization: Bearer <link key>` | `left`; the called side deletes its row |
| generations | `/api/cluster/generations` | the key of this server's master | `generations`: per shared kind (model id) its stored generation |
| rows | `/api/cluster/rows` | the key of this server's master | one page of one kind's rows: `before`, `after` (the kind's generation around the page), `rows` (`key`, `code`, `title`, `document`) and `next` |
| changed | `/api/cluster/changed` | the key of a server this one manages | `asked`; the master syncs that server |
| codes | `/api/cluster/codes` | the key this server presents to its master; at joining also the master's `ticket` in the body | `blocks`: per prefix `prefix`, `first`, `last`; or `refusal` |

The join body carries `code`, the master's `name`, `base_url`, `token`, `build`, `key` (the key the managed
server presents to the master) and `ticket` (see "One code per thing"). A join refusal answers one of `no_such_code`,
`expired`, `too_many_tries` (429), `has_master`, `manages_others`, `busy` (409), `unreadable` (422) or `codes` (502);
`busy` means another link or change is under way there at this moment (the link lease, or the codes lease held past
its wait during the join), `codes` that it could not take its code numbers
from the master. Wrong codes from one address spend a failure budget of five tries per fifteen minutes; once it
is spent, no code is looked at. An unknown key answers 401 and names nothing.

The status answer also carries `apps` and `sites`: the server's own counts, as its dashboard (Apps) and its Sites
list count them for an administrator. A rows body carries `model`, `after` (the previous page's `next`, or null) and
`limit` (the master asks 200 rows a page; a page answer is read up to 32 MiB). Generations and rows answer only this
server's master: a request with no key, with an API key or any other bearer that is no link key, or with a wrong key
answers 401; a server this one manages, presenting its own valid key, answers 403. Changed and codes answer only a
server this one manages (codes also the server a valid ticket names) and 403 to its master. A refusal names its
reason's code alone.

## Checking each other

Every minute the task **Check the linked Hohenheim servers** (`hohenheim:probe_linked_servers`) asks every linked
server of both roles for its status: the master checks the servers it manages, and a managed server checks its
master. The servers are asked at the same time, at most eight at once: up to eight silent ones, each waiting one
call deadline of ten seconds, cost those ten seconds together, not one after another. A failing server is asked
every minute too, so its return is noticed within a minute. **Check now** asks at once.

| State | When |
| --- | --- |
| Reachable | it answered the last status call with the identity that joined, running this version |
| Not checked yet | it was never asked |
| Does not answer since ... | nothing was sent, nothing came back in time, or nothing readable came back |
| Refuses this server since ... | it answered, refusing this server's key |
| Not the server that joined | it answered with another controller identity: another database answers there |
| Runs another version | it answered, running another version than this server |
| Restored to an earlier state | its database was restored: a kind it shares holds an earlier state than the master keeps |

The Servers page says "Does not answer since ..." from the first failed call. The alert (`linked_server_down`) and
the dashboard's attention item come only once the server has failed for two minutes, the same grace a site outage
waits (`Alerts.DOWN_AFTER`), and at once after "Not the server that joined". One more alert follows when it answers
again (`linked_server_recovered`), only when the first was sent. "Runs another version" is an attention item at once
and sends no alert. After "Not the server that joined" nothing more is sent to that address, not even by Check now
or Remove, until an administrator adds the server again.

## What the master remembers

A master keeps its own lasting record of everything a server it manages has, so it knows what should be where also
while that server is silent. The record is core's (`PeerRecords`, tables `zenit_peer_records` and
`zenit_peer_models`): no model of Hohenheim reads it, so the master's own proxy, nameserver, certificates,
supervisors, backups and tasks never act on another server's things.

What is shared is declared per kind (`SharedWithPeersBehaviour` on the model): a kind a person or an assistant
declares, which says what should exist. Shared: access lists and their rules, backup targets, build hooks, database
engines, databases, DynDNS credentials, DNS peers, records, zones and zone secondaries, environments, game domains,
Git connections, apps (instances) with their attached databases, devices, files, quotas, variables and volumes, app
templates with their databases, files, variables and volumes, notification channels, projects, protected paths,
runtime images, hosts, site sign-in providers, sites with their addresses and redirects, stacks with their files and
services, and the backups and database dumps (which say what exists off the server). Not shared: what the system
produces (artifact and build operations, artifact sources, blocked addresses, certificates, install media fetches,
app logs and snapshots, port allocations, preview deployments, reconcile findings, release operations, released
addresses, site outages and sessions, stack deployments, webhook deliveries) and what belongs to the install itself
(its controller identity, its Spamservice installation, its system users, its linked servers and join codes). A kind
added later is not shared until someone declares it.

The master takes in everything at once when a server is added, in the background. After that the managed server
tells its master after every committed change of a shared kind (changed; at most one call on its way and one
waiting), and the master syncs it: per kind it compares the generation (a counter every committed write advances)
and reads a changed kind's rows again whole, a page at a time. The minute check syncs after every good status answer
too, so a lost nudge heals within a minute. A server that does not answer is not retried in between. One sync per
server runs at a time (a lease per server); a nudge during a sync causes exactly one more after it.

The server's page (its name in the Servers list) shows its facts and, in a card under them, "What <master> remembers
of <server>": per kind, named by the reader's word for it (apps, hosts, templates), the number of records kept, each
opening the remembered codes and names fifty at a time, and since when the record is in step. The kinds stand in the
order the panel's sidebar declares their entries (a kind whose entry stands under another, like sites under Apps, at
that entry's place), then the kinds without a place there by their word. How changes arrive stands under that card. A
record's full definition is kept but never shown on a page.

When a kind's generation is lower than the one the master keeps, that server's database was restored to an earlier
state. Nothing is taken in for that kind, nothing kept changes and nothing is repaired: the Servers list shows
"Restored to an earlier state", the dashboard raises one attention item and one alert is sent
(`linked_server_restored`), once. Kinds one version shares and the other does not are named on the server's page
and never guessed.

The master's database now holds the full definitions of the servers it manages, their secrets included (encrypted
at rest with the master's keyring). Its backups matter as much as theirs.

A shared kind that the system rewrites on a schedule makes the master read that whole kind again after each such
write. Known today, left as they are: hosts (the host probe stamps `last_seen_at` and the last error at every
probe), apps (status reconciliation stamps `status` and `status_observed_at`; the disk observation writes
`disk_used_bytes` and `disk_observed_at` every ten minutes), app volumes (`used_bytes`, `observed_at`, measured),
DNS zones (a secondary's refresh stamps `last_checked_at`, `last_transfer_at` and `serial`), DNS zone secondaries
(every transfer and notify stamps `last_axfr_*` and `last_notify_*`), build hooks (`last_triggered_at` at every call)
and backups and database dumps (a scheduled run adds rows and moves their status).

## One code per thing

A code (`AP12`, `S14`) names one thing in the whole cluster, the same on every server, with no server letter in it.
The master draws numbers from its own sequence per prefix and hands each server it manages blocks of them in
advance (core `CodeGrants`, `CodeBlocks`), so a managed server creates things while its master is off. Where a
server's numbers come from is one fact, read inside every allocating transaction: it has a master and holds a block
that master granted. Every allocation and every change of that fact hold the lease `hohenheim:cluster_codes` until
their transaction ends, so no creation ever straddles a join or a leave.

- **Joining**: the managed server counts its coded records per prefix and asks for a number for each plus one spare
  block, above the highest number any of its codes of that prefix holds (codes, presenting the master's `ticket`
  from the join body, since the master holds no row of it yet); the master raises its own sequence to that number
  first. The managed server keeps the blocks and gives every code a new number from them, in the join's own
  transaction. The old spelling of a renumbered code keeps naming its thing on that server. A join that took more
  than half the master's call deadline, or got no block of a prefix it asked for, is refused there, so the master
  never gives up on a join the managed server kept. Before joining, the join card says how many things get a new
  code (core `CodeBlocks.renumberedAtJoining`): every coded thing, exactly.
- **More numbers**: a prefix with fewer than half a block left asks one more block: after every creation on a
  managed server the numbers left are compared with that mark in the background, and the minute check that reaches
  the master asks too. With none left and the
  master silent, a creation is refused in words ("No numbers are left for new apps; this server gets more from its
  master when that answers.") and writes nothing.
- **A link without blocks** (made before codes were cluster-wide; no such link exists): it keeps its own sequence
  and is never renumbered unannounced; it takes part once it is joined again.
- **Leaving**: when the link ends on the managed server, it goes back to its own sequence, raised above every number
  it holds or was granted; its codes stay.
- **Where a code lives**: the master answers it (core `CodeHomes.peerOf`): a shared kind's thing from its record of
  that server, any other code by the grant that holds its number, else its own.

The master records a join's grant void (core `CodeGrants.grantJoining`) and puts it in force in the transaction that
adds the joined server's row (`CodeGrants.joined`). A refused or abandoned join leaves a void grant: its numbers are
never handed out again, and the master answers that a code among them lives on no linked server.
A join the managed server kept while the master never recorded it (the master's add lost the answer) leaves the
managed server's codes in such a void grant as well: the master answers that they live on no linked server.

## Removing

**Remove** asks the other server to forget the link (leave) and then forgets it here, whatever the answer:

- it answered: the link is gone on both servers;
- it did not answer: the link is gone here, and the other server keeps it until it is removed there too;
- another database answers at the address: nothing is sent, the link is gone here.

Removing a link also forgets, in the same step, everything the master kept of that server.

## After an update

Nothing to do: the new tables and columns start empty and an upgraded server is linked to nothing. A master that
is already linked reads its servers' counts at the next check and takes in their records at the next sync.
Both servers of a link run this version: a join from a master without the codes ticket is refused as unreadable.
