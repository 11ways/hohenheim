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
an app, `SR2` a server, `Z8` a DNS zone, `C12` a certificate. The list tools
show the codes and most take a search word, so a hostname (`site_domain_list`)
or an app's name leads to its code in one call; the `read` tool reads any record
by its code.

Some panel actions have no tool yet, and say why on the operation itself: the
ones that open a shell or a screen in the browser, the ones that take or reveal
a secret (passwords, private keys, tokens), the ones whose input is a file's raw
body (putting a file into an app's volume), and the ones on records that carry no
code (single DNS records and bans).
