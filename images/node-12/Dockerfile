# Node 12 workspace/application runtime, for the oldest Alchemy apps the Phoenix
# migration carries (Taverne Tomberg 12.18.2, Invulassistent 12.16.2). See
# ../README.md for the contract every runtime image keeps (tini PID 1, /home/site,
# numeric uid, /bin/bash).
FROM node:12-buster-slim

# Buster is EOL: its packages moved off deb.debian.org to archive.debian.org, and the
# archived Release files are long expired, so an unpatched `apt-get update` fails twice
# over (404 on every index, then "Release file is not valid yet/expired"). Rewriting the
# sources and disabling the Valid-Until check is what makes this image buildable at all;
# it is safe here because the archive is signed by the same buster keyring that ships in
# the base image.
RUN rm -f /etc/apt/sources.list.d/*.list \
 && printf 'deb http://archive.debian.org/debian buster main\ndeb http://archive.debian.org/debian-security buster/updates main\n' > /etc/apt/sources.list \
 && printf 'Acquire::Check-Valid-Until "false";\n' > /etc/apt/apt.conf.d/99no-check-valid-until

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
