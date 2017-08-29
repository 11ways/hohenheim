# Hohenheim

## Permissions

Hohenheim will need to bind to port 80, set uid and set gid

```bash
sudo setcap 'cap_kill,cap_setuid,cap_setgid,cap_net_bind_service=+ep'
```