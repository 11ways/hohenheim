# Tools for assistants (MCP)

Hohenheim serves one MCP server for AI assistants at `/zn/mcp/hohenheim`, over
the Streamable HTTP transport, on the same address as the admin panel.

## Connecting

1. Sign in and open `/account/api-keys`.
2. Create a key with the **Hohenheim assistant** preset. The preset adds the
   scope `mcp:hohenheim`, which admits the key to the server, and lets the key
   follow your own access: it can do what you can do, and no more.
3. Point the assistant at `https://<your panel address>/zn/mcp/hohenheim`, with
   the key sent as `Authorization: Bearer <key>`.

A key without `mcp:hohenheim` is refused at the door, whatever else it holds.
The key is shown once; revoke it on the same page.

## What the tools are

The tools are not written by hand. Each one is an operation the panel already
places (deploy an app, renew a certificate, switch a site off), or the list of a
record source the panel already reads, and its name, description and hints come
from that operation's verb and the kind of record it acts on (`site_pause`,
`certificate_renew`, `site_list`). Each call goes through the same gate, checks
and history as the panel's button, so an assistant can never do more than its
key's owner.

Records are named by short codes, never by internal ids: `S14` is a site, `AP33`
an app, `SR2` a server, `Z8` a DNS zone, `C12` a certificate, `RL5` a deploy. The
list tools show the codes and most take a search word, so a hostname
(`address_list`) or an app's name (`app_list`) leads to its code in one call; the
`read` tool reads any record by its code.

Work that outlives a call, such as deploying an app or rolling it back
(`app_deploy`, `app_roll_back`), answers at once with the code of its run
(`RUN12`). The `run_read` tool reads that run: its status, its steps and how it
ended. A run is read by whoever started it and by whoever may read its app,
nobody else; to anyone else its code names nothing.

Some panel actions have no tool yet, and say why on the operation itself: the
ones that open a shell or a screen in the browser, the ones that take or reveal
a secret (passwords, private keys, tokens), the ones whose input is a file's raw
body (putting a file into an app's volume), the ones on records that carry no
code (single DNS records and bans), and the background work whose run another
customer could read (a site's rollback, the restores of backups and database
dumps, putting a service online).

No tool takes a secret value. An app's plain variables are set and removed with
tools, and `app_generate_secret` has the server make a secret for a key that holds
none; its value is never shown, and it never replaces an existing value.
