# Node 22 workspace/application runtime. See ../README.md for the contract every
# runtime image keeps (tini PID 1, /home/site, numeric uid, /bin/bash).
FROM node:22-bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends bash ca-certificates curl git tini \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /home/site && chmod 0777 /home/site

ENV HOME=/home/site \
    NPM_CONFIG_CACHE=/home/site/.npm \
    PATH=/home/site/node_modules/.bin:$PATH

WORKDIR /home/site

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["bash", "-lc", "node --version && sleep infinity"]
