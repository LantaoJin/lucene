# End-to-end demo: offline KNN vector-field rewrite

A runnable, self-contained demo of `OfflineVectorRewriter` (see
`../OFFLINE_VECTOR_REWRITE_POC.md`). It builds a small multi-segment Lucene index with
four non-vector fields + one float KNN vector field, rewrites **only** the vector field
offline into a new directory (adding `+1.0` to every component), and prints proof that the
vectors changed while every other field and the document structure stayed intact —
finishing with a KNN search that lands on the rewritten vectors.

## Quick start

```bash
# from the repo root of this worktree
./demo/run-demo.sh           # builds lucene-core, then runs the demo IN DOCKER (default)
./demo/run-demo.sh --local   # same, but runs on the host JDK (needs JDK 25)
```

That's it. The script:

1. builds `lucene-core-11.0.0-SNAPSHOT.jar` from this source tree (`./gradlew
   :lucene:core:jar`) — it's an unreleased snapshot, so we must build it, not download it;
2. stages the jar + `OfflineVectorRewriter.java` + `Demo.java` into `demo/.stage`;
3. compiles and runs the demo (in a `public.ecr.aws/amazoncorretto/amazoncorretto:25`
   container by default, or on the host with `--local`).

## Why Docker / Java 25

This Lucene snapshot targets **class-file version 25**, so the demo needs a JDK 25 runtime.
The container pins exactly that (`amazoncorretto:25` from AWS ECR Public — no Docker Hub
rate limit). `--add-modules jdk.incubator.vector` is passed so the Panama vector path is
available (Lucene logs a notice otherwise; it still runs without it).

`lucene-core` has **no runtime dependencies**, so the classpath is just the core jar plus
the two compiled demo classes — nothing else to install.

## Files

| File | Purpose |
|------|---------|
| `Demo.java` | The end-to-end program (build → rewrite → read back → verify → KNN search). In package `org.apache.lucene.index` because the rewriter uses package-private index APIs. |
| `run-demo.sh` | One-command runner (Docker by default, `--local` for host). Builds the jar, stages, compiles, runs. |
| `Dockerfile` | Standalone image definition (expects the `.stage` build context produced by `run-demo.sh`). |
| `.stage/` | Generated staging dir (jar + sources). Safe to delete; recreated each run. |

## Manual Docker build (optional)

`run-demo.sh` already runs everything in Docker. If you want a reusable image instead:

```bash
./demo/run-demo.sh                                  # once, to populate demo/.stage
docker build -f demo/Dockerfile -t lucene-vec-demo demo/.stage
docker run --rm lucene-vec-demo
```

## What you should see

```
STEP 1 — build the source index (3 segments)     # original vectors, e.g. doc-5 = [50,51,52,53]
STEP 2 — offline rewrite: newVector = old + 1.0   # rewrite into a NEW dir; source untouched
STEP 3 — read the NEW index back                  # rewritten vectors, e.g. doc-5 = [51,52,53,54]
STEP 4 — verify                                   # vectors +1; titles/years/doc-count intact
STEP 5 — KNN search on the rewritten index        # query == rewritten doc-5 -> top hit doc-5
DEMO COMPLETE — all checks passed
```

If the source index happens to be built with compound files (`.cfs`), STEP 1 prints
`compound=true` and STEP 3 shows the destination written as loose (non-compound) files —
demonstrating the tool's compound-file read path.

## Cleaning up

```bash
rm -rf demo/.stage          # staged jar + sources
rm -rf /tmp/lucene-vec-demo # the demo's src-index / dest-index (inside the container by default)
```
