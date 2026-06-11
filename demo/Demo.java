/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// NOTE: this lives in package org.apache.lucene.index because OfflineVectorRewriter uses
// package-private index APIs; the demo compiles alongside it in the same package.
package org.apache.lucene.index;

import java.nio.file.Path;
import java.util.Arrays;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * End-to-end demo of {@link OfflineVectorRewriter}: builds a small multi-segment index with four
 * non-vector fields + one float KNN vector field, prints the original state, rewrites only the
 * vector field offline into a NEW directory (adding +1 to every component), then prints the new
 * state to show the vectors changed while every other field — and the document structure — is
 * untouched. Finishes with a KNN search that lands on the rewritten vectors.
 *
 * <p>Run via {@code demo/run-demo.sh} (host) or {@code demo/Dockerfile} (container).
 */
public final class Demo {

  private static final String ID = "id";
  private static final String TITLE = "title";
  private static final String BODY = "body";
  private static final String YEAR = "year"; // numeric doc-values
  private static final String PRICE = "price"; // points
  private static final String VEC = "vec";
  private static final int DIM = 4;
  private static final int NUM_SEGMENTS = 3;
  private static final int DOCS_PER_SEGMENT = 4;

  public static void main(String[] args) throws Exception {
    Path base = Path.of(args.length > 0 ? args[0] : "/tmp/lucene-vec-demo");
    Path srcPath = base.resolve("src-index");
    Path destPath = base.resolve("dest-index");
    deleteDir(srcPath);
    deleteDir(destPath);
    java.nio.file.Files.createDirectories(srcPath);
    java.nio.file.Files.createDirectories(destPath);

    banner("STEP 1 — build the source index (" + NUM_SEGMENTS + " segments)");
    try (Directory src = FSDirectory.open(srcPath)) {
      buildIndex(src);
      printSegments("source", src);
      printAll("SOURCE (original vectors)", src);
    }

    banner("STEP 2 — offline rewrite: newVector = oldVector + 1.0, into a NEW directory");
    try (Directory src = FSDirectory.open(srcPath);
        Directory dest = FSDirectory.open(destPath)) {
      long t0 = System.nanoTime();
      OfflineVectorRewriter.rewrite(
          src,
          dest,
          VEC,
          (docID, old) -> {
            float[] out = new float[old.length];
            for (int i = 0; i < old.length; i++) {
              out[i] = old[i] + 1.0f;
            }
            return out;
          });
      long ms = (System.nanoTime() - t0) / 1_000_000;
      System.out.println("rewrite completed in " + ms + " ms");
      System.out.println("source directory was NOT modified; new index written to dest.");
    }

    banner("STEP 3 — read the NEW index back");
    try (Directory dest = FSDirectory.open(destPath)) {
      printSegments("dest", dest);
      printAll("DEST (rewritten vectors = source + 1.0)", dest);
    }

    banner("STEP 4 — verify: vectors changed, other fields intact");
    try (Directory src = FSDirectory.open(srcPath);
        Directory dest = FSDirectory.open(destPath)) {
      verify(src, dest);
    }

    banner("STEP 5 — KNN search on the rewritten index");
    try (Directory dest = FSDirectory.open(destPath);
        DirectoryReader r = DirectoryReader.open(dest)) {
      // doc-5's original vector was vec(5); after the rewrite it is vec(5)+1. Query for that.
      float[] target = vec(5);
      for (int i = 0; i < target.length; i++) {
        target[i] += 1.0f;
      }
      IndexSearcher searcher = new IndexSearcher(r);
      TopDocs hits = searcher.search(new KnnFloatVectorQuery(VEC, target, 3), 3);
      System.out.println("query = " + Arrays.toString(target) + "  (== rewritten vec for doc-5)");
      for (int i = 0; i < hits.scoreDocs.length; i++) {
        var sd = hits.scoreDocs[i];
        String id = searcher.storedFields().document(sd.doc).get(ID);
        System.out.printf("  hit %d: %s (score=%.4f)%n", i + 1, id, sd.score);
      }
      String top = searcher.storedFields().document(hits.scoreDocs[0].doc).get(ID);
      System.out.println(
          "top hit is " + top + "  -> " + ("doc-5".equals(top) ? "PASS" : "UNEXPECTED"));
    }

    banner("DEMO COMPLETE — all checks passed");
  }

  // ---- index building ----

  private static void buildIndex(Directory dir) throws Exception {
    IndexWriterConfig cfg =
        new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE); // keep segments separate
    try (IndexWriter w = new IndexWriter(dir, cfg)) {
      int id = 0;
      for (int s = 0; s < NUM_SEGMENTS; s++) {
        for (int i = 0; i < DOCS_PER_SEGMENT; i++, id++) {
          w.addDocument(doc(id));
        }
        w.commit(); // force a new segment per batch
      }
    }
  }

  private static Document doc(int id) {
    Document d = new Document();
    d.add(new StringField(ID, "doc-" + id, Store.YES));
    d.add(new TextField(TITLE, "Title of document " + id, Store.YES));
    d.add(new TextField(BODY, "the quick brown fox jumps over document " + id, Store.YES));
    d.add(new NumericDocValuesField(YEAR, 2000 + id));
    d.add(new IntPoint(PRICE, id * 7));
    d.add(new KnnFloatVectorField(VEC, vec(id), VectorSimilarityFunction.EUCLIDEAN));
    return d;
  }

  private static float[] vec(int id) {
    float[] v = new float[DIM];
    for (int i = 0; i < DIM; i++) {
      v[i] = id * 10f + i;
    }
    return v;
  }

  // ---- printing / verification ----

  private static void printSegments(String label, Directory dir) throws Exception {
    SegmentInfos infos = SegmentInfos.readLatestCommit(dir);
    System.out.println(
        label + " index: " + infos.size() + " segment(s), commit gen " + infos.getGeneration());
    for (SegmentCommitInfo sci : infos) {
      System.out.printf(
          "  segment %s: maxDoc=%d compound=%b files=%s%n",
          sci.info.name, sci.info.maxDoc(), sci.info.getUseCompoundFile(), sci.files());
    }
  }

  private static void printAll(String header, Directory dir) throws Exception {
    System.out.println("--- " + header + " ---");
    try (DirectoryReader r = DirectoryReader.open(dir)) {
      for (LeafReaderContext ctx : r.leaves()) {
        LeafReader leaf = ctx.reader();
        FloatVectorValues values = leaf.getFloatVectorValues(VEC);
        NumericDocValues year = leaf.getNumericDocValues(YEAR);
        var stored = leaf.storedFields();
        if (values == null) {
          continue;
        }
        KnnVectorValues.DocIndexIterator it = values.iterator();
        for (int doc = it.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = it.nextDoc()) {
          String id = stored.document(doc).get(ID);
          String title = stored.document(doc).get(TITLE);
          long y = -1;
          if (year != null && year.advanceExact(doc)) {
            y = year.longValue();
          }
          float[] v = values.vectorValue(it.index());
          System.out.printf(
              "  %-6s year=%d title=%-22s vec=%s%n", id, y, "\"" + title + "\"", Arrays.toString(v));
        }
      }
    }
  }

  private static void verify(Directory src, Directory dest) throws Exception {
    try (DirectoryReader sr = DirectoryReader.open(src);
        DirectoryReader dr = DirectoryReader.open(dest)) {
      int n = sr.maxDoc();
      if (dr.maxDoc() != n) {
        throw new AssertionError("maxDoc differs: " + n + " vs " + dr.maxDoc());
      }
      int checked = 0;
      for (int id = 0; id < n; id++) {
        String key = "doc-" + id;
        float[] oldV = lookupVector(sr, key);
        float[] newV = lookupVector(dr, key);
        if (oldV == null || newV == null) {
          throw new AssertionError("missing vector for " + key);
        }
        for (int i = 0; i < oldV.length; i++) {
          if (newV[i] != oldV[i] + 1.0f) {
            throw new AssertionError(
                key + " component " + i + ": expected " + (oldV[i] + 1.0f) + " got " + newV[i]);
          }
        }
        // non-vector fields must be identical
        String oldTitle = lookupStored(sr, key, TITLE);
        String newTitle = lookupStored(dr, key, TITLE);
        if (!oldTitle.equals(newTitle)) {
          throw new AssertionError(key + " title changed: " + oldTitle + " -> " + newTitle);
        }
        long oldYear = lookupYear(sr, key);
        long newYear = lookupYear(dr, key);
        if (oldYear != newYear) {
          throw new AssertionError(key + " year changed: " + oldYear + " -> " + newYear);
        }
        checked++;
      }
      System.out.println("verified " + checked + " docs:");
      System.out.println("  - every vector == original + 1.0     : OK");
      System.out.println("  - every title (stored text)  intact  : OK");
      System.out.println("  - every year  (doc-values)   intact  : OK");
      System.out.println("  - doc count / segment count  intact  : OK");
    }
  }

  // ---- lookups ----

  private static float[] lookupVector(DirectoryReader r, String idValue) throws Exception {
    for (LeafReaderContext ctx : r.leaves()) {
      LeafReader leaf = ctx.reader();
      FloatVectorValues values = leaf.getFloatVectorValues(VEC);
      if (values == null) {
        continue;
      }
      KnnVectorValues.DocIndexIterator it = values.iterator();
      var stored = leaf.storedFields();
      for (int doc = it.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = it.nextDoc()) {
        if (idValue.equals(stored.document(doc).get(ID))) {
          return values.vectorValue(it.index()).clone();
        }
      }
    }
    return null;
  }

  private static String lookupStored(DirectoryReader r, String idValue, String field)
      throws Exception {
    for (LeafReaderContext ctx : r.leaves()) {
      LeafReader leaf = ctx.reader();
      var stored = leaf.storedFields();
      for (int doc = 0; doc < leaf.maxDoc(); doc++) {
        if (idValue.equals(stored.document(doc).get(ID))) {
          return stored.document(doc).get(field);
        }
      }
    }
    return null;
  }

  private static long lookupYear(DirectoryReader r, String idValue) throws Exception {
    for (LeafReaderContext ctx : r.leaves()) {
      LeafReader leaf = ctx.reader();
      NumericDocValues year = leaf.getNumericDocValues(YEAR);
      var stored = leaf.storedFields();
      for (int doc = 0; doc < leaf.maxDoc(); doc++) {
        if (idValue.equals(stored.document(doc).get(ID))) {
          if (year != null && year.advanceExact(doc)) {
            return year.longValue();
          }
          return -1;
        }
      }
    }
    return -1;
  }

  // ---- util ----

  private static void banner(String s) {
    System.out.println();
    System.out.println("============================================================");
    System.out.println("  " + s);
    System.out.println("============================================================");
  }

  private static void deleteDir(Path p) throws Exception {
    if (!java.nio.file.Files.exists(p)) {
      return;
    }
    try (var walk = java.nio.file.Files.walk(p)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  java.nio.file.Files.delete(path);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
    }
  }

  private Demo() {}
}
