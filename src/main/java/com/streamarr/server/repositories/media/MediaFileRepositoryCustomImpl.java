package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class MediaFileRepositoryCustomImpl implements MediaFileRepositoryCustom {

  private final DSLContext dsl;

  @Override
  public List<UUID> findMediaFileIdsByMediaIds(Collection<UUID> mediaIds) {
    return dsl.select(MEDIA_FILE.ID)
        .from(MEDIA_FILE)
        .where(MEDIA_FILE.MEDIA_ID.in(mediaIds))
        .fetch(MEDIA_FILE.ID);
  }
}
