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
package org.apache.lucene.index;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
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
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;

public class TestOfflineVectorRewriter extends LuceneTestCase {

  private static final String ID = "id";
  private static final String TEXT = "body";
  private static final String NUM = "num";
  private static final String POINT = "pt";
  private static final String VEC = "vec";
  private static final int DIM = 4;

  /** Pin the unquantized HNSW format so the random test codec doesn't pick a quantized one. */
  private static Codec unquantizedCodec() {
    return TestUtil.alwaysKnnVectorsFormat(new Lucene99HnswVectorsFormat());
  }

  private static float[] vec(int id) {
    float[] v = new float[DIM];
    for (int i = 0; i < DIM; i++) {
      v[i] = id * 10f + i;
    }
    return v;
  }

  private static Document doc(int id) {
    Document d = new Document();
    d.add(new StringField(ID, "doc-" + id, Store.YES));
    d.add(new TextField(TEXT, "content number " + id + " lorem ipsum", Store.YES));
    d.add(new NumericDocValuesField(NUM, id));
    d.add(new IntPoint(POINT, id));
    d.add(new KnnFloatVectorField(VEC, vec(id), VectorSimilarityFunction.EUCLIDEAN));
    return d;
  }

  /** Builds a multi-segment index: one commit per batch, NoMergePolicy so segments survive. */
  private void buildIndex(Directory dir, int numSegments, int docsPerSegment) throws Exception {
    buildIndex(dir, numSegments, docsPerSegment, random().nextBoolean());
  }

  private void buildIndex(Directory dir, int numSegments, int docsPerSegment, boolean compound)
      throws Exception {
    IndexWriterConfig cfg =
        new IndexWriterConfig()
            .setCodec(unquantizedCodec())
            .setUseCompoundFile(compound)
            .setMergePolicy(NoMergePolicy.INSTANCE);
    try (IndexWriter w = new IndexWriter(dir, cfg)) {
      int id = 0;
      for (int s = 0; s < numSegments; s++) {
        for (int i = 0; i < docsPerSegment; i++) {
          w.addDocument(doc(id++));
        }
        w.commit(); // force a new segment
      }
    }
  }

  public void testRewriteAddsConstantAcrossMultipleSegments() throws Exception {
    int numSegments = 3;
    int docsPerSegment = 7;
    int total = numSegments * docsPerSegment;

    try (Directory src = newDirectory();
        Directory dest = newDirectory()) {
      buildIndex(src, numSegments, docsPerSegment);

      // sanity: multiple segments really exist
      SegmentInfos srcInfos = SegmentInfos.readLatestCommit(src);
      assertEquals(numSegments, srcInfos.size());

      // transform: add +1 to every component
      OfflineVectorRewriter.rewrite(
          src,
          dest,
          VEC,
          (docID, old) -> {
            float[] out = new float[old.length];
            for (int i = 0; i < old.length; i++) {
              out[i] = old[i] + 1f;
            }
            return out;
          });

      // dest is structurally valid
      TestUtil.checkIndex(dest);

      // same number of segments, same docs
      assertEquals(numSegments, SegmentInfos.readLatestCommit(dest).size());

      try (DirectoryReader srcR = DirectoryReader.open(src);
          DirectoryReader destR = DirectoryReader.open(dest)) {
        assertEquals(total, srcR.maxDoc());
        assertEquals(total, destR.maxDoc());

        // 1) every vector is old+1; 2) every non-vector field is byte-for-byte intact
        verifyById(srcR, destR);
      }
    }
  }

  /**
   * For each doc (keyed by the stored id), check the new vector == old+1 and other fields equal.
   */
  private void verifyById(DirectoryReader srcR, DirectoryReader destR) throws Exception {
    for (int id = 0; id < srcR.maxDoc(); id++) {
      String key = "doc-" + id;
      float[] oldVec = lookupVector(srcR, key);
      float[] newVec = lookupVector(destR, key);
      assertNotNull("missing in src: " + key, oldVec);
      assertNotNull("missing in dest: " + key, newVec);
      float[] expected = new float[oldVec.length];
      for (int i = 0; i < oldVec.length; i++) {
        expected[i] = oldVec[i] + 1f;
      }
      assertArrayEquals("vector for " + key, expected, newVec, 0f);

      // non-vector fields unchanged
      DocFields s = lookupFields(srcR, key);
      DocFields d = lookupFields(destR, key);
      assertEquals("text for " + key, s.text, d.text);
      assertEquals("num for " + key, s.num, d.num);
    }
  }

  public void testRewriteFromCompoundSegments() throws Exception {
    int numSegments = 3;
    int docsPerSegment = 5;
    try (Directory src = newDirectory();
        Directory dest = newDirectory()) {
      buildIndex(src, numSegments, docsPerSegment, /* compound= */ true);

      // confirm the source really used compound files
      for (SegmentCommitInfo sci : SegmentInfos.readLatestCommit(src)) {
        assertTrue("expected compound source segment", sci.info.getUseCompoundFile());
      }

      OfflineVectorRewriter.rewrite(
          src,
          dest,
          VEC,
          (docID, old) -> {
            float[] out = new float[old.length];
            for (int i = 0; i < old.length; i++) {
              out[i] = old[i] + 1f;
            }
            return out;
          });

      TestUtil.checkIndex(dest);
      try (DirectoryReader srcR = DirectoryReader.open(src);
          DirectoryReader destR = DirectoryReader.open(dest)) {
        verifyById(srcR, destR);
      }
    }
  }

  public void testRewriteIdentityRoundTrips() throws Exception {
    try (Directory src = newDirectory();
        Directory dest = newDirectory()) {
      buildIndex(src, 2, 5);
      OfflineVectorRewriter.rewrite(src, dest, VEC, (docID, old) -> old);
      TestUtil.checkIndex(dest);
      try (DirectoryReader srcR = DirectoryReader.open(src);
          DirectoryReader destR = DirectoryReader.open(dest)) {
        for (int id = 0; id < srcR.maxDoc(); id++) {
          String key = "doc-" + id;
          assertArrayEquals(
              "identity vector for " + key, lookupVector(srcR, key), lookupVector(destR, key), 0f);
        }
      }
    }
  }

  public void testKnnSearchReflectsRewrittenVectors() throws Exception {
    try (Directory src = newDirectory();
        Directory dest = newDirectory()) {
      buildIndex(src, 2, 6);
      // shift so that the query target lands on a known doc post-rewrite
      OfflineVectorRewriter.rewrite(
          src,
          dest,
          VEC,
          (docID, old) -> {
            float[] out = new float[old.length];
            for (int i = 0; i < old.length; i++) {
              out[i] = old[i] + 1f;
            }
            return out;
          });
      try (DirectoryReader destR = DirectoryReader.open(dest)) {
        IndexSearcher searcher = new IndexSearcher(destR);
        // doc-3's original vector was vec(3); after +1 it is vec(3)+1. Query for that.
        float[] target = vec(3);
        for (int i = 0; i < target.length; i++) {
          target[i] += 1f;
        }
        TopDocs hits = searcher.search(new KnnFloatVectorQuery(VEC, target, 1), 1);
        assertEquals(1, hits.scoreDocs.length);
        assertEquals("doc-3", searcher.storedFields().document(hits.scoreDocs[0].doc).get(ID));
      }
    }
  }

  // ---- helpers ----

  private static float[] lookupVector(DirectoryReader r, String idValue) throws Exception {
    for (LeafReaderContext ctx : r.leaves()) {
      LeafReader leaf = ctx.reader();
      FloatVectorValues values = leaf.getFloatVectorValues(VEC);
      if (values == null) {
        continue;
      }
      KnnVectorValues.DocIndexIterator it = values.iterator();
      for (int doc = it.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = it.nextDoc()) {
        if (idValue.equals(leaf.storedFields().document(doc).get(ID))) {
          return values.vectorValue(it.index()).clone();
        }
      }
    }
    return null;
  }

  private record DocFields(String text, long num) {}

  private static DocFields lookupFields(DirectoryReader r, String idValue) throws Exception {
    for (LeafReaderContext ctx : r.leaves()) {
      LeafReader leaf = ctx.reader();
      NumericDocValues num = leaf.getNumericDocValues(NUM);
      for (int doc = 0; doc < leaf.maxDoc(); doc++) {
        if (idValue.equals(leaf.storedFields().document(doc).get(ID))) {
          long n = 0;
          if (num != null && num.advanceExact(doc)) {
            n = num.longValue();
          }
          String text = leaf.storedFields().document(doc).get(TEXT);
          return new DocFields(text, n);
        }
      }
    }
    return null;
  }
}
