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

# Self-contained image for the offline KNN vector-field rewrite demo.
#
# IMPORTANT: this expects a build context produced by `run-demo.sh` (the `.stage` dir),
# which contains the freshly built lucene-core jar plus the rewriter + demo sources.
# Build/run it from this directory like:
#
#   ./run-demo.sh                              # easiest: builds context + runs (no manual docker)
#
# or manually, after running run-demo.sh once to populate .stage:
#
#   docker build -f Dockerfile -t lucene-vec-demo .stage
#   docker run --rm lucene-vec-demo
#
# Java 25 is required because this Lucene snapshot targets class-file version 25.

FROM public.ecr.aws/amazoncorretto/amazoncorretto:25

WORKDIR /work
# Build context is the .stage directory: lucene-core.jar + src/.../*.java
COPY lucene-core.jar /work/lucene-core.jar
COPY src /work/src

RUN javac --add-modules jdk.incubator.vector \
      -cp /work/lucene-core.jar -d /work/out \
      /work/src/org/apache/lucene/index/OfflineVectorRewriter.java \
      /work/src/org/apache/lucene/index/Demo.java

ENTRYPOINT ["java", "--add-modules", "jdk.incubator.vector", \
            "-cp", "/work/lucene-core.jar:/work/out", \
            "org.apache.lucene.index.Demo", "/tmp/lucene-vec-demo"]
