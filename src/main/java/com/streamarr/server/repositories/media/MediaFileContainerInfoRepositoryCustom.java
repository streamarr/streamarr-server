package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.ChapterKnowledge;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.task.KeyframePacketScanPublication;
import com.streamarr.server.domain.task.ProbeAttemptFailure;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeState;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface MediaFileContainerInfoRepositoryCustom {

  /**
   * Replaces the stored outcome, its stream rows and its keyframe positions in one transaction, and
   * clears the recorded failure of the requested inputs. An outcome whose keyframes require a
   * packet scan keeps the positions that an earlier packet scan read for the same source snapshot
   * and the same chosen video stream, while both probe versions are on the current keyframe
   * timeline ({@link ProbeVersion#isOnCurrentKeyframeTimeline}). Such an outcome reads as known
   * keyframes and still owes its own packet scan. Returns {@code false} without writing when the
   * media file no longer exists or when a newer probe version already holds a result for the same
   * source snapshot, or when the latest requested inputs differ.
   */
  boolean publish(ProbePublication publication);

  /**
   * Stores what a packet scan learned on the outcome that owes the scan, under the media file lock,
   * in place of any positions that the outcome kept from an earlier scan. A {@link
   * KeyframesUnavailableReason#PACKET_SCAN_MEDIA_ERROR} replaces them too: bytes that an earlier
   * scan read and that no longer decode have changed under an unchanged size and modified time.
   * Returns {@code false} without writing unless the stored outcome still owes a packet scan for
   * the publication's source snapshot and probe version.
   */
  boolean publishKeyframePacketScan(KeyframePacketScanPublication publication);

  /**
   * Stores that every packet scan of these inputs failed, so no further scan runs. Positions that
   * the outcome kept from an earlier scan stand, because failed scans say nothing about the bytes
   * that the earlier scan read. An outcome without them stores unavailable keyframes. Returns
   * {@code false} without writing unless the stored outcome still owes a packet scan for these
   * inputs.
   */
  boolean publishKeyframePacketScanFailure(UUID mediaFileId, ProbeInputs inputs);

  /**
   * Whether the stored outcome still owes a keyframe packet scan: its probe asked for one, and no
   * scan has stored a result or failed for good since. An outcome that keeps the positions of an
   * earlier scan owes one. False when no outcome is stored.
   */
  boolean owesKeyframePacketScan(UUID mediaFileId);

  /**
   * Reads the source snapshot and probe version of a stored outcome that owes a packet scan, under
   * the media file lock. Empty when no stored outcome owes one. Requires the caller's transaction.
   */
  Optional<ProbeInputs> lockPacketScanInputs(UUID mediaFileId);

  /**
   * Saves desired inputs and invalidates a changed-source outcome under the media file lock.
   * Different inputs also clear the failure recorded for the previous inputs. Returns false when
   * the media file no longer exists.
   */
  boolean trySaveProbeRequest(UUID mediaFileId, ProbeInputs inputs);

  /**
   * Saves why an attempt at these inputs failed. Returns {@code false} without writing when they
   * are no longer the latest requested inputs.
   */
  boolean trySaveProbeFailure(UUID mediaFileId, ProbeInputs inputs, ProbeAttemptFailure failure);

  /** Returns the probe state of each listed media file that still exists. */
  List<ProbeState> findProbeStates(Collection<UUID> mediaFileIds);

  /**
   * Deletes the requested inputs and their failure once the source no longer exists, so no probe is
   * requested for the file. Requires the caller's transaction.
   */
  void withdrawProbeRequest(UUID mediaFileId);

  /**
   * Reads what the stored outcome says about the source's keyframes, with the decoded positions
   * when they are known. Empty when no outcome is stored. Throws {@link
   * com.streamarr.server.exceptions.MalformedKeyframePositionsException} when the stored positions
   * cannot be decoded, without marking the caller's transaction rollback-only.
   */
  Optional<KeyframeKnowledge> findKeyframeKnowledge(UUID mediaFileId);

  /**
   * Reads what each listed file's stored outcome says about its container's chapters, in one
   * statement. A file without a stored outcome has no entry. A failed outcome, or an outcome from a
   * probe version that reports no chapters, maps to {@link ChapterKnowledge.NotReported}.
   */
  Map<UUID, ChapterKnowledge> findChapterKnowledge(Collection<UUID> mediaFileIds);

  /**
   * Reads the measurement of the file's streams while it matches the stored outcome: the same
   * source snapshot, the same keyframe positions and the current measurement version. Empty
   * otherwise. Throws {@link com.streamarr.server.exceptions.MalformedStreamMeasurementException}
   * when a stored stream cannot be decoded, without marking the caller's transaction rollback-only.
   */
  Optional<StreamMeasurements> findStreamMeasurements(UUID mediaFileId);

  /** Reads desired inputs under the media file lock. Requires the caller's transaction. */
  Optional<ProbeInputs> lockProbeInputs(UUID mediaFileId);
}
