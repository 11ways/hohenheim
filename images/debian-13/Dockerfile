# A plain Debian userland: the "I will install it myself" workspace runtime.
# See ../README.md for the contract.
FROM debian:trixie-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends bash ca-certificates curl git \
      less procps tini \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /home/site && chmod 0777 /home/site

ENV HOME=/home/site

WORKDIR /home/site

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["bash", "-lc", "sleep infinity"]
