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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.CompoundDirectory;
import org.apache.lucene.codecs.KnnFieldVectorsWriter;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.TrackingDirectoryWrapper;
import org.apache.lucene.util.InfoStream;

/**
 * Proof-of-concept offline tool that rewrites the single KNN vector field of an existing Lucene
 * index. It streams the on-disk vectors of every segment through a caller-supplied transform and
 * writes a brand-new index into a destination directory, copying every non-vector file verbatim.
 *
 * <p>Design notes:
 *
 * <ul>
 *   <li><b>Source is never mutated.</b> Lucene segment files are immutable (each carries a
 *       whole-file CRC footer), so we never edit {@code .vec}/{@code .vex} in place — we read the
 *       old vectors and write new ones into {@code destDir}.
 *   <li><b>Compound files are handled transparently.</b> If a segment is stored as a {@code .cfs},
 *       we read it through the codec's {@link CompoundDirectory}; either way each input file is
 *       read by name. Every destination segment is written <b>non-compound</b> for simplicity, so
 *       we never have to repack a {@code .cfs}.
 *   <li><b>The vector writer mutates {@link FieldInfo} attributes.</b> {@code
 *       PerFieldKnnVectorsFormat} stamps the per-field format name + suffix onto the {@link
 *       FieldInfo} when {@code addField} is called, so we must write the new {@code .fnm}
 *       <i>after</i> the vector writer has run, using the same {@link FieldInfos} instance.
 * </ul>
 *
 * <p>Assumptions for this POC: exactly one vector field (float, unquantized), no deletions carried
 * forward (a {@code .liveDocs}-aware variant is left as an extension), and the destination is a
 * fresh/empty directory.
 */
public final class OfflineVectorRewriter {

  /** Transform applied to each stored float vector. */
  @FunctionalInterface
  public interface FloatVectorTransform {
    /**
     * @param docID segment-local docID that owns the vector
     * @param oldVector the existing vector (do not retain; copy if you need to keep it)
     * @return the replacement vector (same dimension as {@code oldVector})
     */
    float[] apply(int docID, float[] oldVector) throws IOException;
  }

  private OfflineVectorRewriter() {}

  /**
   * Reads the index in {@code srcDir}, rewrites the one float vector field {@code vectorField} via
   * {@code transform}, and writes the resulting index into {@code destDir} (which must be empty).
   * After this returns, opening {@code destDir} yields the new vectors with all other fields
   * unchanged.
   */
  public static void rewrite(
      Directory srcDir, Directory destDir, String vectorField, FloatVectorTransform transform)
      throws IOException {
    SegmentInfos srcInfos = SegmentInfos.readLatestCommit(srcDir);

    // Build the destination commit from scratch, preserving the created-version major so codecs
    // and back-compat checks behave identically to the source.
    SegmentInfos destInfos = new SegmentInfos(srcInfos.getIndexCreatedVersionMajor());
    destInfos.counter = srcInfos.counter;
    destInfos.version = srcInfos.version;
    destInfos.setUserData(srcInfos.getUserData(), false);

    Set<String> createdFiles = new HashSet<>();
    for (SegmentCommitInfo srcCommit : srcInfos) {
      SegmentCommitInfo destCommit = rewriteSegment(srcCommit, destDir, vectorField, transform);
      destInfos.add(destCommit);
      createdFiles.addAll(destCommit.files());
    }

    // Durably sync every segment file BEFORE writing segments_N (mirrors IndexWriter.startCommit).
    // Without this, a crash — or MockDirectoryWrapper's simulated crash at close — can drop these
    // unsynced files, leaving a segments_N that points at a missing .si/.vec.
    destDir.sync(createdFiles);

    // Write + sync segments_N into destDir; the new index is now readable.
    destInfos.commit(destDir);
  }

  private static SegmentCommitInfo rewriteSegment(
      SegmentCommitInfo srcCommit,
      Directory destDir,
      String vectorField,
      FloatVectorTransform transform)
      throws IOException {
    SegmentInfo srcInfo = srcCommit.info;
    Codec codec = srcInfo.getCodec();
    Directory srcDir = srcInfo.dir;

    // If the segment is compound, read its members through the compound reader; otherwise read
    // directly from the segment directory.
    Directory readDir = srcDir;
    CompoundDirectory cfs = null;
    if (srcInfo.getUseCompoundFile()) {
      cfs = codec.compoundFormat().getCompoundReader(srcDir, srcInfo);
      readDir = cfs;
    }
    try {
      FieldInfos fieldInfos =
          codec.fieldInfosFormat().read(readDir, srcInfo, "", IOContext.READONCE);

      // Build a fresh, non-compound destination SegmentInfo with the same identity (name, id,
      // maxDoc, sort, version). Files are filled in once we know what we wrote.
      SegmentInfo destInfo =
          new SegmentInfo(
              destDir,
              srcInfo.getVersion(),
              srcInfo.getMinVersion(),
              srcInfo.name,
              srcInfo.maxDoc(),
              false, // non-compound destination
              srcInfo.getHasBlocks(),
              codec,
              srcInfo.getDiagnostics(),
              srcInfo.getId(),
              srcInfo.getAttributes(),
              srcInfo.getIndexSort());

      TrackingDirectoryWrapper trackingDir = new TrackingDirectoryWrapper(destDir);

      // 1) Stream the vector field through the transform into new vector files.
      writeRewrittenVectors(
          codec, trackingDir, readDir, srcInfo, fieldInfos, vectorField, transform);

      // 2) Copy every other (non-vector) file of this segment verbatim into destDir. We skip the
      //    old vector files (we just wrote fresh ones) and the .si/.fnm (we rewrite those below).
      copyNonVectorFiles(readDir, trackingDir, srcInfo);

      // 3) Persist FieldInfos (.fnm). MUST be after the vector writer, since addField stamped the
      //    per-field format/suffix attributes onto the FieldInfo instances in `fieldInfos`.
      codec.fieldInfosFormat().write(trackingDir, destInfo, "", fieldInfos, IOContext.DEFAULT);

      // 4) Record the full file set and write the .si (non-compound, so .si stays standalone).
      destInfo.setFiles(new HashSet<>(trackingDir.getCreatedFiles()));
      codec.segmentInfoFormat().write(destDir, destInfo, IOContext.DEFAULT);

      // Fresh commit: no deletes/field-updates/dv-updates carried (POC assumption).
      return new SegmentCommitInfo(destInfo, 0, 0, -1L, -1L, -1L, srcCommit.getId());
    } finally {
      if (cfs != null) {
        cfs.close();
      }
    }
  }

  /** Runs the codec's vector writer, feeding it transformed vectors in docID order. */
  private static void writeRewrittenVectors(
      Codec codec,
      TrackingDirectoryWrapper trackingDir,
      Directory readDir,
      SegmentInfo srcInfo,
      FieldInfos fieldInfos,
      String vectorField,
      FloatVectorTransform transform)
      throws IOException {
    FieldInfo fi = fieldInfos.fieldInfo(vectorField);
    if (fi == null || fi.hasVectorValues() == false) {
      throw new IllegalArgumentException("not a vector field: " + vectorField);
    }
    if (fi.getVectorEncoding() != VectorEncoding.FLOAT32) {
      throw new IllegalArgumentException(
          "POC supports FLOAT32 only, got " + fi.getVectorEncoding() + " for " + vectorField);
    }

    SegmentReadState readState =
        new SegmentReadState(readDir, srcInfo, fieldInfos, IOContext.READONCE);
    SegmentWriteState writeState =
        new SegmentWriteState(
            InfoStream.getDefault(), trackingDir, srcInfo, fieldInfos, null, IOContext.DEFAULT);

    KnnVectorsReader reader = codec.knnVectorsFormat().fieldsReader(readState);
    KnnVectorsWriter writer = codec.knnVectorsFormat().fieldsWriter(writeState);
    boolean success = false;
    try {
      @SuppressWarnings("unchecked")
      KnnFieldVectorsWriter<float[]> fieldWriter =
          (KnnFieldVectorsWriter<float[]>) writer.addField(fi);

      FloatVectorValues values = reader.getFloatVectorValues(vectorField);
      KnnVectorValues.DocIndexIterator it = values.iterator();
      for (int doc = it.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = it.nextDoc()) {
        float[] old = values.vectorValue(it.index());
        // Defensive copy: the reader may reuse the backing array across calls.
        float[] in = Arrays.copyOf(old, old.length);
        float[] out = transform.apply(doc, in);
        if (out.length != fi.getVectorDimension()) {
          throw new IllegalArgumentException(
              "transform returned dimension " + out.length + " != " + fi.getVectorDimension());
        }
        fieldWriter.addValue(doc, out);
      }
      writer.flush(srcInfo.maxDoc(), null);
      writer.finish();
      success = true;
    } finally {
      if (success) {
        reader.close();
        writer.close();
      } else {
        org.apache.lucene.util.IOUtils.closeWhileHandlingException(reader, writer);
      }
    }
  }

  private static boolean isVectorFile(String fileName) {
    return fileName.endsWith(".vec")
        || fileName.endsWith(".vemf")
        || fileName.endsWith(".vex")
        || fileName.endsWith(".vem")
        || fileName.endsWith(".veq") // quantized data (defensive; POC is unquantized)
        || fileName.endsWith(".vemq");
  }

  /**
   * Copies every logical member file of the source segment into destDir verbatim, except: the old
   * vector files (replaced by the freshly written ones), and the {@code .si}/{@code .fnm}
   * (rewritten by the caller). When the source segment is compound, {@code readDir} is the {@link
   * CompoundDirectory} and {@code listAll()} returns the logical members; otherwise we list the
   * segment's referenced files. Identifying vector files by extension is robust in both cases
   * (unlike {@code srcInfo.files()}, which for a compound segment only names {@code .cfs}/{@code
   * .cfe}/{@code .si}, not the packed vector members).
   */
  private static void copyNonVectorFiles(
      Directory readDir, TrackingDirectoryWrapper trackingDir, SegmentInfo srcInfo)
      throws IOException {
    List<String> members;
    if (srcInfo.getUseCompoundFile()) {
      members = new ArrayList<>(Arrays.asList(readDir.listAll()));
    } else {
      members = new ArrayList<>(srcInfo.files());
    }
    for (String name : members) {
      if (isVectorFile(name)) {
        continue; // replaced by freshly written vectors
      }
      if (name.endsWith(".si") || name.endsWith(".fnm")) {
        continue; // rewritten by the caller / FieldInfos write
      }
      if (name.endsWith(".cfs") || name.endsWith(".cfe")) {
        continue; // compound container itself is not a logical member
      }
      trackingDir.copyFrom(readDir, name, name, IOContext.DEFAULT);
    }
  }
}
