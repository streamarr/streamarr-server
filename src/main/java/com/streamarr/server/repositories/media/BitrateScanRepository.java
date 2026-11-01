package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.streaming.StreamMeasurementUnavailableReason;
import com.streamarr.server.domain.task.BitrateScanChunkPublication;
import com.streamarr.server.domain.task.BitrateScanInputs;
import com.streamarr.server.domain.task.BitrateScanKey;
import com.streamarr.server.domain.task.StoredBitrateScanChunk;
import com.streamarr.server.domain.task.StreamMeasurementPublication;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A media file's bitrate scan (ADR 0054): what it reads, its stored chunks and its result. */
public interface BitrateScanRepository {

  /**
   * Reads what a bitrate scan of the file reads, while the file needs one: its stored outcome is a
   * success with known keyframe positions, its chosen video stream is H.264, HEVC or AV1, it is not
   * an MP4 or MOV file whose keyframe positions came from the container index, and it has neither a
   * measurement nor an unavailable measurement for its snapshot, its keyframe positions and the
   * current measurement version. Empty otherwise.
   */
  Optional<BitrateScanInputs> findBitrateScanInputs(UUID mediaFileId);

  /**
   * Reads the same as {@link #findBitrateScanInputs} under the media file lock. Requires the
   * caller's transaction.
   */
  Optional<BitrateScanInputs> lockBitrateScanInputs(UUID mediaFileId);

  /**
   * Stores a finished chunk only while the stored outcome holds the key's snapshot and keyframe
   * positions. Returns {@code false} without writing otherwise, or when the chunk is already
   * stored.
   */
  boolean storeChunk(BitrateScanChunkPublication publication);

  /** Reads the file's stored chunks that follow the key, in keyframe interval order. */
  List<StoredBitrateScanChunk> findChunks(UUID mediaFileId, BitrateScanKey key);

  /** Deletes the file's stored chunks that follow another key. */
  void deleteChunksNotFollowing(UUID mediaFileId, BitrateScanKey key);

  /** Deletes every stored chunk of the file. */
  void deleteChunks(UUID mediaFileId);

  /**
   * Stores the measurement in place of the file's earlier one, only while the stored outcome holds
   * the key's snapshot and keyframe positions and the key names the current measurement version.
   * Returns whether it stored the measurement.
   */
  boolean publishMeasurement(StreamMeasurementPublication publication);

  /**
   * Marks the file's measurement unavailable for the key, under the same guard as {@link
   * #publishMeasurement}. Returns whether it stored the mark.
   */
  boolean markMeasurementUnavailable(
      UUID mediaFileId, BitrateScanKey key, StreamMeasurementUnavailableReason reason);
}
