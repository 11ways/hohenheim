# Node 16 workspace/application runtime, for the Alchemy apps the Phoenix migration
# carries (Microcopy, Udesign, Auditexport, Oogfonds: 16.13.2). See ../README.md for
# the contract every runtime image keeps (tini PID 1, /home/site, numeric uid,
# /bin/bash).
FROM node:16-bullseye-slim

# python3/make/g++: node-gyp, so an npm install with a native module that ships no
# prebuilt binary (measured 2026-08-29: alchemymvc's @picturae/mmmagic) builds
# instead of failing -- a Node runtime that cannot install native modules is not one.
# graphicsmagick/imagemagick: alchemy-media shells out to `gm` for every image
# derivative, so without them EVERY /media/image/... request is a 500 while the
# site's HTML still renders perfectly (measured 2026-08-30 on Taverne Tomberg and
# Udesign). Phoenix carries both system-wide, which is why the apps never declared
# the dependency.
# Deliberately NO libexiv2 here: the Phoenix-era `exiv2` native module links against
# libexiv2.so.14, and bullseye ships only libexiv2-27 (0.27, soname .so.27), which does
# not satisfy it -- installing it would add weight and satisfy nothing. Measured
# 2026-08-30: no node-16 app on robbedoes carries an exiv2 module at all, and buster's
# node-10/node-12 images are where the module actually lives. An app that needs exiv2
# on bullseye must have the module rebuilt against 0.27 (libexiv2-27 + libexiv2-dev),
# which the copy-node_modules-from-Phoenix rule deliberately avoids.
RUN apt-get update \
 && apt-get install -y --no-install-recommends bash busybox ca-certificates iproute2 util-linux curl git tini \
        python3 make g++ graphicsmagick imagemagick \
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
