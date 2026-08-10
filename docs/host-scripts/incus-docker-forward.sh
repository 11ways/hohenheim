#!/bin/bash
# Docker sets the IPv4 FORWARD policy to DROP, which silently blackholes forwarded
# traffic for Incus bridges (IPv6 is unaffected because Docker does not manage it).
# DOCKER-USER is evaluated before Docker's own rules, so allow the Incus bridges there.
#
# WILDCARDS, not an enumeration: hhx-<token> extra-NIC bridges are created and
# deleted at RUNTIME (one per hohenheim controller), so any boot-time list goes
# stale the moment a new controller deploys -- and the failure it produces is the
# half-masked kind (IPv4 blackholed, IPv6 fine). iptables' trailing '+' matches
# any interface name with that prefix, so one boot-time rule covers every bridge
# that will ever exist.
#
# Idempotent: -C checks before inserting.
#
# Install: /usr/local/sbin/incus-docker-forward.sh, run by the oneshot
# incus-docker-forward.service (After=docker.service incus.service,
# PartOf=docker.service, WantedBy=multi-user.target docker.service).
set -u
for br in 'incusbr+' 'hhx-+' 'hohenheim-extra'; do
    for cmd in iptables ip6tables; do
        $cmd -C DOCKER-USER -i "$br" -j ACCEPT 2>/dev/null || $cmd -I DOCKER-USER -i "$br" -j ACCEPT
        $cmd -C DOCKER-USER -o "$br" -j ACCEPT 2>/dev/null || $cmd -I DOCKER-USER -o "$br" -j ACCEPT
    done
done
exit 0
