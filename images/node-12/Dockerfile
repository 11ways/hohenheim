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
# graphicsmagick/imagemagick: alchemy-media shells out to `gm` for every image
# derivative, so without them EVERY /media/image/... request is a 500 ("Could not
# execute GraphicsMagick/ImageMagick ... the gm/convert binaries can't be found")
# while the site's HTML still renders perfectly -- measured 2026-08-30 on Taverne
# Tomberg and Udesign. Phoenix carries GraphicsMagick 1.3.23 and ImageMagick
# system-wide, which is why the apps never declared the dependency.
# libexiv2-14: the `exiv2` native module of this era links against libexiv2.so.14, so
# without it `require('exiv2')` throws "libexiv2.so.14: cannot open shared object file"
# and alchemy-media loses image metadata (measured 2026-08-30 on Udesign Preview, which
# runs on node-10; no node-12 app carries the module today, but they are the same
# vintage and buster is the base that still ships the matching soname).
# webp (cwebp): alchemy-media negotiates WebP by USER-AGENT, so a real browser gets
# "500: Error: Could not find cwebp path" on every image while curl -- whose UA asks
# for no WebP -- gets a perfectly good JPEG from the same URL. That asymmetry is why
# the gap survived a byte-for-byte cutover comparison: measured 2026-08-30 on the LIVE
# tavernetomberg.be, where every photo was missing in a browser and identical to
# Phoenix under curl.
RUN apt-get update \
 && apt-get install -y --no-install-recommends bash busybox ca-certificates iproute2 util-linux curl git tini \
        python3 make g++ graphicsmagick imagemagick libexiv2-14 webp \
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
