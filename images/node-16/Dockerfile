# Node 16 workspace/application runtime, for the Alchemy apps the Phoenix migration
# carries (Microcopy, Udesign, Auditexport, Oogfonds: 16.13.2). See ../README.md for
# the contract every runtime image keeps (tini PID 1, /home/site, numeric uid,
# /bin/bash).
FROM node:16-bullseye-slim

# python3/make/g++: node-gyp, so an npm install with a native module that ships no
# prebuilt binary (measured 2026-08-29: alchemymvc's @picturae/mmmagic) builds
# instead of failing -- a Node runtime that cannot install native modules is not one.
RUN apt-get update \
 && apt-get install -y --no-install-recommends bash busybox ca-certificates iproute2 util-linux curl git tini \
        python3 make g++ \
 && rm -rf /var/lib/apt/lists/*

# The Incus lane's PID 1 (see ../hohenheim-init): shared by every runtime image and
# copied into each build context by RuntimeImages.materializeContext.
COPY hohenheim-init /sbin/hohenheim-init
COPY hohenheim-dhcp /sbin/hohenheim-dhcp
RUN chmod 0755 /sbin/hohenheim-init /sbin/hohenheim-dhcp

RUN mkdir -p /home/site && chmod 0777 /home/site

ENV HOME=/home/site \
    NPM_CONFIG_CACHE=/home/site/.npm \
    PATH=/home/site/node_modules/.bin:$PATH

WORKDIR /home/site

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["bash", "-lc", "node --version && sleep infinity"]
