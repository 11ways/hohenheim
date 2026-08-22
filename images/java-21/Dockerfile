# Java 21 workspace/application runtime. See ../README.md for the contract.
FROM eclipse-temurin:21-jdk-noble

RUN apt-get update \
 && apt-get install -y --no-install-recommends bash ca-certificates curl git tini \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /home/site && chmod 0777 /home/site

ENV HOME=/home/site \
    GRADLE_USER_HOME=/home/site/.gradle \
    MAVEN_OPTS=-Duser.home=/home/site

WORKDIR /home/site

ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["bash", "-lc", "java -version && sleep infinity"]
