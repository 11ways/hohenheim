# Linked Hohenheim servers

Several complete Hohenheims can be linked so one panel manages them all later. This page describes the link
itself: how two servers join, what they say to each other, the states a link shows and how it ends. Nothing else is
shared yet: no record of another server is listed, no page of another server is shown.

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
3. The master calls the other server's **join**. Both servers now hold a row in `linked_servers`: the master one
   with role `manages`, the managed server one with role `managed_by`.

Every creation of a link row, the join answered on the managed server and the add on the master, runs one at a
time under one core lease (`hohenheim:cluster_link`), its checks inside it: one master, a managed server manages
nobody, the name (ignoring case) and the address are free. Two joins at the same instant never both succeed; the
second is refused in words.

Each side keeps the key it presents (encrypted) and only a keyed digest of the key it accepts. A key proves which
server asks; it grants no permission. Each side also pins the other's controller identity
(`ControllerIdentity.token()`).

## The calls on the wire

All three are `POST` with a JSON body over https; redirects are never followed and the certificate is verified.
Linked servers are reached on the public internet only, unless the host declares
`hohenheim.cluster.allow_private_networks` in its host network declaration.

| Call | Path | Credential | Answer |
| --- | --- | --- | --- |
| join | `/api/cluster/join` | the join code in the body | `name`, `token`, `build`, `time` and the key the master presents from now on; or `refusal` |
| status | `/api/cluster/status` | `Authorization: Bearer <link key>` | `name`, `token`, `build`, `time` |
| leave | `/api/cluster/leave` | `Authorization: Bearer <link key>` | `left`; the called side deletes its row |

The join body carries `code`, the master's `name`, `base_url`, `token`, `build` and `key` (the key the managed
server presents to the master). A join refusal answers one of `no_such_code`, `expired`, `too_many_tries` (429),
`has_master`, `manages_others`, `busy` (409) or `unreadable` (422); `busy` means another server is being linked
there at this moment. Wrong codes from one address spend a failure budget of five tries per fifteen minutes; once it
is spent, no code is looked at. An unknown key answers 401 and names nothing.

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

The Servers page says "Does not answer since ..." from the first failed call. The alert (`linked_server_down`) and
the dashboard's attention item come only once the server has failed for two minutes, the same grace a site outage
waits (`Alerts.DOWN_AFTER`), and at once after "Not the server that joined". One more alert follows when it answers
again (`linked_server_recovered`), only when the first was sent. "Runs another version" is an attention item at once
and sends no alert. After "Not the server that joined" nothing more is sent to that address, not even by Check now
or Remove, until an administrator adds the server again.

## Removing

**Remove** asks the other server to forget the link (leave) and then forgets it here, whatever the answer:

- it answered: the link is gone on both servers;
- it did not answer: the link is gone here, and the other server keeps it until it is removed there too;
- another database answers at the address: nothing is sent, the link is gone here.

## After an update

Nothing to do: the new tables start empty and an upgraded server is linked to nothing.
