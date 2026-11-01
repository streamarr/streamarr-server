package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.MediaFileContainerInfo.MEDIA_FILE_CONTAINER_INFO;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import org.jooq.Condition;

/** Conditions on a media file's stored probe outcome. */
final class StoredOutcomeConditions {

  private StoredOutcomeConditions() {}

  static Condition snapshotMatches(SourceFileSnapshot snapshot) {
    return MEDIA_FILE_CONTAINER_INFO
        .SOURCE_SIZE
        .eq(snapshot.size())
        .and(
            MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_EPOCH_SECOND.eq(
                snapshot.modifiedAt().getEpochSecond()))
        .and(MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_NANOS.eq(snapshot.modifiedAt().getNano()));
  }
}
