package com.streamarr.server.repositories.media;

import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.task.KeyframePacketScanPublication;
import com.streamarr.server.domain.task.ProbeAttemptFailure;
import com.streamarr.server.domain.task.ProbeInputs;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeState;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaFileContainerInfoRepositoryCustom {

  /**
   * Replaces the stored outcome, its stream rows and its keyframe positions in one transaction, and
   * clears the recorded failure of the requested inputs. Returns {@code false} without writing when
   * the media file no longer exists or when a newer probe version already holds a result for the
   * same source snapshot, or when the latest requested inputs differ.
   */
  boolean publish(ProbePublication publication);

  /**
   * Stores what a packet scan learned on the outcome that asked for the scan, in one guarded
   * update. Returns {@code false} without writing unless the stored outcome still needs a packet
   * scan for the publication's source snapshot and probe version.
   */
  boolean publishKeyframePacketScan(KeyframePacketScanPublication publication);

  /**
   * Reads the source snapshot and probe version of a stored outcome that needs a packet scan, under
   * the media file lock. Empty when no stored outcome needs one. Requires the caller's transaction.
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

  /** Reads desired inputs under the media file lock. Requires the caller's transaction. */
  Optional<ProbeInputs> lockProbeInputs(UUID mediaFileId);
}
