#!/usr/bin/env bash
# Setup script for Claude Code cloud environments.
# Paste into: cloud environment settings > Setup script.
# OpenJDK 21, Node 22, Docker and docker compose are preinstalled there; Java 25, pnpm and Terraform are not.
set -euo pipefail

# Java 25 (Temurin) — the project's Gradle toolchain targets 25
if ! java -version 2>&1 | grep -q '"25'; then
  mkdir -p /opt/jdk25
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse" -o /tmp/jdk25.tar.gz
  tar -xzf /tmp/jdk25.tar.gz -C /opt/jdk25 --strip-components=1
  ln -sf /opt/jdk25/bin/java /usr/local/bin/java
  ln -sf /opt/jdk25/bin/javac /usr/local/bin/javac
  echo 'export JAVA_HOME=/opt/jdk25' >> /etc/profile.d/jdk25.sh
fi

# Node tooling
command -v pnpm >/dev/null 2>&1 || npm install -g pnpm

# Terraform (fmt/validate only — apply is blocked by project settings)
if ! command -v terraform >/dev/null 2>&1; then
  TF_VERSION="${TF_VERSION:-$(curl -fsSL https://checkpoint-api.hashicorp.com/v1/check/terraform | jq -r .current_version)}"
  curl -fsSL "https://releases.hashicorp.com/terraform/${TF_VERSION}/terraform_${TF_VERSION}_linux_amd64.zip" -o /tmp/tf.zip
  unzip -o /tmp/tf.zip -d /usr/local/bin
fi

# Warm caches: Gradle dependencies and Docker images for Testcontainers / compose
if [ -f gradlew ]; then ./gradlew --no-daemon help >/dev/null 2>&1 || true; fi
if [ -f deploy/compose/docker-compose.yml ]; then
  docker compose -f deploy/compose/docker-compose.yml pull || true
fi
