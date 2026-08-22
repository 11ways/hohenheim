# Java 21 workspace/application runtime. See ../README.md for the contract.
FROM eclipse-temurin:21-jdk-noble

RUN apt-get update \
 && apt-get install -y --no-install-recommends bash busybox ca-certificates iproute2 util-linux curl git tini \
 && rm -rf /var/lib/apt/lists/*

# The Incus lane's PID 1 (see ../hohenheim-init): shared by every runtime image and
# copied into each build context by RuntimeImages.materializeContext.
COPY hohenheim-init /sbin/hohenheim-init
COPY hohenheim-dhcp /sbin/hohenheim-dhcp
RUN chmod 0755 /sbin/hohenheim-init /sbin/hohenheim-dhcp

RUN mkdir -p /home/site && chmod 0777 /home/site

ENV HOME=/home/site \
    GRADLE_USER_HOME=/home/site/.gradle \
    MAVEN_OPTS=-Duser.home=/home/site

WORKDIR /home/site

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["bash", "-lc", "java -version && sleep infinity"]
