#!/bin/sh
MAVEN_VERSION=3.9.16
MAVEN_BASE="$HOME/.m2/wrapper/dists/apache-maven-$MAVEN_VERSION"
MAVEN_HOME="$MAVEN_BASE/apache-maven-$MAVEN_VERSION"
if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  mkdir -p "$MAVEN_BASE" || exit 1
  TMP="${TMPDIR:-/tmp}/apache-maven-$MAVEN_VERSION-bin.tar.gz"
  curl -fsSL "https://dlcdn.apache.org/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz" -o "$TMP" || exit 1
  tar -xzf "$TMP" -C "$MAVEN_BASE" || exit 1
  rm -f "$TMP"
fi
exec "$MAVEN_HOME/bin/mvn" "$@"
