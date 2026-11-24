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

- `read` reads any record in full by its code: every field and kind setting of
  the form the reader's own panel shows (an operator the admin form, a customer
  the /manage one), a setting at its default marked so, a related record by its
  code and name, a secret only as set or not set; an app's variables are read
  with `app_list_variables`. `history` tells what happened to one, and `list`
  lists the records of one kind (`list` with `kind` `site`, `app`, `server`,
  `user`, ...), each by its code, a page at a time; most kinds take a search
  word, so a hostname or an app's name leads to its code in one call.
- Every other tool is one operation the panel already places (deploy an app,
  renew a certificate, switch a site off), named after the kind of record it
  acts on and what it does (`site_pause`, `certificate_renew`). Its description
  is the operation's own written description, the text the panel shows beside
  the same action, and its arguments are named and described in its schema.
  Not yet described: a template's own variables when an app is made from it,
  and the DNS publishing choice of a certificate order.
- Each call goes through the same gate, checks and history as the panel's
  button, so an assistant can never do more than its key's owner, and a tool
  the key's owner may not use is not listed.

Records are named by short codes, never by internal ids: `S14` is a site, `AP33`
an app, `SR2` a server, `Z8` a DNS zone, `C12` a certificate, `RL5` a deploy. The
server's instructions name every kind's letters, and say once what the
`version` argument of a changing tool does: the version a read showed (`v8`),
so a record changed since is refused instead of overwritten.

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
