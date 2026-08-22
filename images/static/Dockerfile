# A file server for a workspace that only holds files: nginx over /home/site,
# listening on the runtime image's default port. See ../README.md for the contract.
FROM nginx:1.27-bookworm

RUN apt-get update \
 && apt-get install -y --no-install-recommends bash busybox ca-certificates iproute2 util-linux tini \
 && rm -rf /var/lib/apt/lists/*

# The Incus lane's PID 1 (see ../hohenheim-init): shared by every runtime image and
# copied into each build context by RuntimeImages.materializeContext.
COPY hohenheim-init /sbin/hohenheim-init
COPY hohenheim-dhcp /sbin/hohenheim-dhcp
RUN chmod 0755 /sbin/hohenheim-init /sbin/hohenheim-dhcp

RUN mkdir -p /home/site && chmod 0777 /home/site

# Everything nginx writes moves under /tmp so the process needs no root and no
# writable image layer: the workspace uid is a plain number with no account.
COPY nginx.conf /etc/nginx/nginx.conf

ENV HOME=/home/site

WORKDIR /home/site

EXPOSE 8080

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["nginx", "-g", "daemon off;"]
