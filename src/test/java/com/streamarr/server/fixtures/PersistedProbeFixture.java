package com.streamarr.server.fixtures;

import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.MediaFileContainerInfo.MediaFileContainerInfoBuilder;
import com.streamarr.server.domain.media.MediaFileStreamInfo;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import lombok.experimental.UtilityClass;

@UtilityClass
public class PersistedProbeFixture {

  public static MediaFileContainerInfoBuilder storedProbeBuilder(
      UUID mediaFileId, ProbeOutcome.Success probe) {
    return MediaFileContainerInfo.builder()
        .mediaFileId(mediaFileId)
        .snapshot(new SourceFileSnapshot(1000, Instant.EPOCH))
        .probeVersion(ProbeVersion.CURRENT)
        .container(probe.container())
        .streams(
            probe.streams().stream()
                .map(stream -> MediaFileStreamInfo.builder().stream(mediaFileId, stream).build())
                .toList());
  }

  public static void storeProbe(EntityManager entityManager, MediaFileContainerInfo row) {
    entityManager.persist(
        MediaFileContainerInfo.builder()
            .mediaFileId(row.getMediaFileId())
            .snapshot(row.getSnapshot())
            .probeVersion(row.getProbeVersion())
            .container(row.getContainer())
            .probeError(row.getProbeError().orElse(null))
            .build());
    if (row.getOutcome() instanceof ProbeOutcome.Success success) {
      success
          .streams()
          .forEach(
              stream ->
                  entityManager.persist(
                      MediaFileStreamInfo.builder().stream(row.getMediaFileId(), stream).build()));
    }

    entityManager.flush();
    entityManager.clear();
  }
}
