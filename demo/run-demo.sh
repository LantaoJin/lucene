#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# End-to-end demo runner for the offline KNN vector-field rewrite POC.
#
#   ./demo/run-demo.sh            # build lucene-core, then run the demo IN DOCKER (default)
#   ./demo/run-demo.sh --local    # build lucene-core, then run the demo on the host JDK
#
# In both modes the lucene-core jar is built from THIS source tree (it is an unreleased
# 11.0.0-SNAPSHOT), so there is no version mismatch.

set -euo pipefail

# Resolve repo root = parent of this script's directory.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
STAGE="${SCRIPT_DIR}/.stage"
MODE="docker"
if [[ "${1:-}" == "--local" ]]; then MODE="local"; fi

REWRITER_SRC="${REPO_ROOT}/lucene/core/src/test/org/apache/lucene/index/OfflineVectorRewriter.java"
DEMO_SRC="${SCRIPT_DIR}/Demo.java"
IMAGE="public.ecr.aws/amazoncorretto/amazoncorretto:25"

echo ">> [1/3] Building lucene-core jar from source (./gradlew :lucene:core:jar) ..."
( cd "${REPO_ROOT}" && ./gradlew :lucene:core:jar -q )
CORE_JAR="$(ls "${REPO_ROOT}"/lucene/core/build/libs/lucene-core-*.jar | head -1)"
echo "   core jar: ${CORE_JAR}"

echo ">> [2/3] Staging build context ..."
rm -rf "${STAGE}"
mkdir -p "${STAGE}/src/org/apache/lucene/index"
cp "${CORE_JAR}" "${STAGE}/lucene-core.jar"
cp "${REWRITER_SRC}" "${STAGE}/src/org/apache/lucene/index/OfflineVectorRewriter.java"
cp "${DEMO_SRC}" "${STAGE}/src/org/apache/lucene/index/Demo.java"

run_steps() {
  # Compiles the rewriter + demo against the core jar, then runs the demo.
  # $1 = path to java/javac home prefix command ("" for host, container uses image's javac).
  echo ">> [3/3] Compiling demo + rewriter against lucene-core ..."
  javac --add-modules jdk.incubator.vector \
    -cp /work/lucene-core.jar -d /work/out \
    /work/src/org/apache/lucene/index/OfflineVectorRewriter.java \
    /work/src/org/apache/lucene/index/Demo.java
  echo ">> Running demo ..."
  java --add-modules jdk.incubator.vector \
    -cp /work/lucene-core.jar:/work/out \
    org.apache.lucene.index.Demo /tmp/lucene-vec-demo
}

if [[ "${MODE}" == "local" ]]; then
  echo ">> Mode: LOCAL (host JDK $(java -version 2>&1 | head -1))"
  mkdir -p "${STAGE}/out"
  javac --add-modules jdk.incubator.vector \
    -cp "${STAGE}/lucene-core.jar" -d "${STAGE}/out" \
    "${STAGE}/src/org/apache/lucene/index/OfflineVectorRewriter.java" \
    "${STAGE}/src/org/apache/lucene/index/Demo.java"
  java --add-modules jdk.incubator.vector \
    -cp "${STAGE}/lucene-core.jar:${STAGE}/out" \
    org.apache.lucene.index.Demo /tmp/lucene-vec-demo
else
  echo ">> Mode: DOCKER (${IMAGE})"
  # Mount the staged context read-only at /work-in, copy to a writable /work, then build+run.
  docker run --rm \
    -v "${STAGE}:/work-in:ro" \
    "${IMAGE}" \
    bash -lc '
      set -euo pipefail
      mkdir -p /work/out
      cp -r /work-in/* /work/
      echo ">> [3/3] Compiling demo + rewriter against lucene-core (in container) ..."
      javac --add-modules jdk.incubator.vector \
        -cp /work/lucene-core.jar -d /work/out \
        /work/src/org/apache/lucene/index/OfflineVectorRewriter.java \
        /work/src/org/apache/lucene/index/Demo.java
      echo ">> Running demo (in container) ..."
      java --add-modules jdk.incubator.vector \
        -cp /work/lucene-core.jar:/work/out \
        org.apache.lucene.index.Demo /tmp/lucene-vec-demo
    '
fi

echo
echo ">> Demo finished. (staging dir: ${STAGE})"
