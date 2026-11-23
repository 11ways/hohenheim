# Signing in to the panel through Proteus

Hohenheim's panel always offers password sign-in. Next to it, it can offer sign-in through a Proteus realm. Only
people who hold one named Proteus permission get in that way; everyone else the realm knows is refused.

## Settings

All five live under `hohenheim.auth_proteus` in `settings/local.dry` and take effect after a restart.

| Setting | Meaning |
| --- | --- |
| `enabled` | Offer Proteus sign-in (default off). |
| `endpoint` | URL of the Proteus realm server. Host-only: set it in `settings/local.dry` or the environment. |
| `realm_client` | Slug of this server's realm client in Proteus. |
| `access_key` | The realm client's access key. Written in plain text by hand, it is sealed at the next settings write. |
| `authenticator` | Slug of one Proteus sign-in method. Empty (the default): the person lands on the realm's own login page and picks there. |
| `required_permission` | The Proteus permission a person must hold to sign in, for example `hohenheim.robbedoes`. |

```
{
    "hohenheim": {
        "auth_proteus": {
            "enabled": true,
            "endpoint": "https://proteus.example.com",
            "realm_client": "hohenheim",
            "access_key": "<access key of the realm client>",
            "required_permission": "hohenheim.robbedoes"
        }
    }
}
```

## The required permission

The permission is read from the realm's answer at every sign-in. A person is admitted when the answer grants the
permission itself, or a wildcard that covers it: `hohenheim.*` or `*` cover `hohenheim.robbedoes`. An explicit deny of
the permission in Proteus keeps the person out even when a wildcard grants it. Each server names its own permission, so
two servers (`hohenheim.robbedoes`, `hohenheim.kuifje`) can admit different people from the same realm.

A refused person reads "Your account has no access to this application." The permission's name is never shown. No
account is made, linked or changed, and no session starts.

## Fail closed

Proteus sign-in switched on without `required_permission` is not offered at all: the sign-in page shows only password
sign-in, and the dashboard shows the attention item "Proteus sign-in is not offered" with a link to the Sign-in
settings. A Proteus sign-in that lets in everyone the realm knows cannot be configured.

## What it does not do

It decides who may SIGN IN, not what they may do: roles, projects and every other right stay in Hohenheim's own
access settings. A person admitted for the first time gets an account without rights.

Taking the permission away in Proteus stops that person's next sign-in. It does not end a session that is already open,
and it does not revoke API keys the person made; disable the account in Hohenheim for that.
