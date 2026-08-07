package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;

import com.streamarr.server.repositories.JooqQueryHelper;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class MediaFileRepositoryCustomImpl implements MediaFileRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;

  @Override
  public List<UUID> findMediaFileIdsByMediaIds(Collection<UUID> mediaIds) {
    return dsl.select(MEDIA_FILE.ID)
        .from(MEDIA_FILE)
        .where(MEDIA_FILE.MEDIA_ID.in(mediaIds))
        .fetch(MEDIA_FILE.ID);
  }

  @Override
  public Optional<UUID> findMediaIdByMediaFileId(UUID mediaFileId) {
    return dsl.select(MEDIA_FILE.MEDIA_ID)
        .from(MEDIA_FILE)
        .where(MEDIA_FILE.ID.eq(mediaFileId))
        .fetchOptional(MEDIA_FILE.MEDIA_ID);
  }

  @Override
  public Set<UUID> findDistinctMediaIdsByMediaIdIn(Collection<UUID> mediaIds) {
    var query =
        dsl.selectDistinct(MEDIA_FILE.MEDIA_ID)
            .from(MEDIA_FILE)
            .where(MEDIA_FILE.MEDIA_ID.in(mediaIds));

    return new HashSet<>(JooqQueryHelper.nativeQuery(entityManager, query, UUID.class));
  }
}
