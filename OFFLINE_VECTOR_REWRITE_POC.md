# POC: offline KNN vector-field rewrite

A standalone tool that rewrites the single float KNN vector field of an existing Lucene
index by **reading the on-disk vectors, transforming them in memory (streaming), and
writing a brand-new index** with the new vectors — leaving every non-vector field
untouched. This is an *offline, file-level* alternative to the online `IndexWriter`
generation-based approach in PR #16214.

- Branch: `worktree-knn-offline-rewrite` (forked from stock `main`).
- Tool: `lucene/core/src/test/org/apache/lucene/index/OfflineVectorRewriter.java`
- Tests: `lucene/core/src/test/org/apache/lucene/index/TestOfflineVectorRewriter.java`

It lives in the **test source set** on purpose: it reaches package-private/internal index
APIs (`SegmentInfos`, `SegmentInfo`, `SegmentCommitInfo`, `SegmentReadState/WriteState`)
that `lucene/core/src/java/module-info.java` does not export.

## What it does

```java
OfflineVectorRewriter.rewrite(srcDir, destDir, "vec",
    (docID, oldVector) -> /* return the new vector, same dimension */);
```

Per segment in `srcDir`'s latest commit:

1. If the segment is compound (`.cfs`), open it through the codec's `CompoundDirectory`;
   otherwise read loose files. Either way, members are read by name.
2. Run the codec's `KnnVectorsWriter` (`addField` → per-doc `addValue` in docID order →
   `flush(maxDoc, null)` → `finish`), feeding each doc's vector through the transform. This
   produces fresh `.vec/.vemf` (+ `.vex/.vem` graph), with a valid CRC footer.
3. Copy every **non-vector** member file verbatim into `destDir` (postings, stored fields,
   doc-values, points, norms, …). Vector files and `.si`/`.fnm` are skipped (rewritten).
4. Write a fresh `.fnm` **after** the vector writer (the per-field format/suffix attributes
   are stamped onto `FieldInfo` during `addField`, so order matters).
5. Write a fresh, **non-compound** `.si`, with the file set from a `TrackingDirectoryWrapper`.

Finally: `destDir.sync(allCreatedFiles)` then `SegmentInfos.commit(destDir)` — mirroring
`IndexWriter.startCommit`, which syncs all segment files before writing `segments_N`.
Opening `destDir` now yields the new vectors.

## Design decisions & rationale

- **New output directory, source never mutated.** Lucene files are immutable (whole-file
  CRC footer), so "in place" can't mean editing `.vec` bytes; we write fresh files. A
  fresh `destDir` also avoids name collisions (the single vector field reuses suffix `0`,
  so new vector filenames equal the originals).
- **Destination is always non-compound.** Uniformly handles CFS and non-CFS sources
  without having to repack a `.cfs` (which would need the package-private
  `IndexWriter.createCompoundFile`).
- **Streaming.** Vectors are transformed one doc at a time; only the codec writer's own
  buffering holds vectors, never the whole column in our code.
- **Identity of segments preserved.** Same segment names, ids, maxDoc, sort, and version,
  so the rewrite is a faithful copy except for the vector field.

## Assumptions / limitations (POC scope)

- Exactly **one float (unquantized) vector field**. Byte/quantized and multi-vector-field
  are straightforward extensions (the writer path is generic; quantized needs field-global
  requantization, same caveat as PR #16214).
- **No deletions carried forward** — the destination commits with `delGen == -1`. A
  liveDocs-aware variant would copy `.liveDocs` and the del count.
- Destination directory must be empty.
- Single-threaded; per-segment cost is O(segment vector count) (full-column rewrite +
  graph rebuild), like any from-scratch segment write.

## Run the tests

```bash
cd /workplace/ltjin/semantic/lucene/.claude/worktrees/knn-offline-rewrite
./gradlew -p lucene/core test --tests "org.apache.lucene.index.TestOfflineVectorRewriter"
# randomized soak (also exercises compound vs non-compound sources):
./gradlew -p lucene/core test --tests "org.apache.lucene.index.TestOfflineVectorRewriter" -Ptests.iters=20
```

Tests cover: multi-segment `+1` rewrite (vectors changed, text/DV intact, `checkIndex`
clean), identity round-trip, KNN search reflecting rewritten vectors, and a forced
compound-source segment.

## How this differs from PR #16214 (online generation updates)

| | PR #16214 (`updateFloatVectorValue`) | This POC (offline rewrite) |
|---|---|---|
| When | Online, via a live `IndexWriter` | Offline, no `IndexWriter` open on the index |
| Mechanism | New per-field **generation** file overlaid by a gen-aware reader | New **index** in a separate directory |
| Reader changes | Yes (`SegmentVectorsProducer` / `SegmentKnnVectors`) | None — output is a perfectly normal index |
| Graph | Deferred to merge (exact-scan until then) | Rebuilt immediately as part of the write |
| Scope | Subset of docs per call, last-write-wins | Whole field, one pass |
| Source mutated | Yes (adds gen files to the live index) | No (source untouched; swap dirs when done) |
