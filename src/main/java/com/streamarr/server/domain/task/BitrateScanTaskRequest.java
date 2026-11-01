package com.streamarr.server.domain.task;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * The inputs of one bitrate scan task instance: which file, and the source snapshot, keyframe
 * positions digest and measurement version that its chunks follow. {@code failedAttempts} counts
 * the attempts of the file's next chunk that ended without a result.
 */
@Builder(toBuilder = true)
public record BitrateScanTaskRequest(
    @NonNull UUID mediaFileId,
    @NonNull String filepathUri,
    @NonNull SourceFileSnapshot snapshot,
    @NonNull String keyframePositionsDigest,
    int measurementVersion,
    int failedAttempts) {

  public BitrateScanKey key() {
    return new BitrateScanKey(snapshot, keyframePositionsDigest, measurementVersion);
  }
}
