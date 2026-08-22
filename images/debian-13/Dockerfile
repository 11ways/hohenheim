# A plain Debian userland: the "I will install it myself" workspace runtime.
# See ../README.md for the contract.
FROM debian:trixie-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends bash busybox ca-certificates iproute2 util-linux curl git \
      less procps tini \
 && rm -rf /var/lib/apt/lists/*

# The Incus lane's PID 1 (see ../hohenheim-init): shared by every runtime image and
# copied into each build context by RuntimeImages.materializeContext.
COPY hohenheim-init /sbin/hohenheim-init
COPY hohenheim-dhcp /sbin/hohenheim-dhcp
RUN chmod 0755 /sbin/hohenheim-init /sbin/hohenheim-dhcp

RUN mkdir -p /home/site && chmod 0777 /home/site

ENV HOME=/home/site

WORKDIR /home/site

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["bash", "-lc", "sleep infinity"]
