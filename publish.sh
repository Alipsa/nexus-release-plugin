#!/usr/bin/env bash
set -eo pipefail
if [ -n "$(git status --porcelain)" ]; then
  echo "Error: Git working tree is not clean. Commit or stash changes before publishing."
  exit 1
fi

if command -v jdk17 &> /dev/null; then
  echo "Found jdk17 command, executing it..."
  . jdk17
  export PATH="$JAVA_HOME/bin:$PATH"
fi
set -u
java -version
./gradlew clean build
./gradlew publishPlugins --validate-only
./gradlew release
./gradlew publishPlugins
