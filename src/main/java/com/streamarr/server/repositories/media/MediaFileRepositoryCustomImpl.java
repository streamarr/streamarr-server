package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;

import com.streamarr.server.domain.media.MatchingFailure;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.jooq.generated.enums.ItemResultFailureReason;
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
import org.jooq.impl.DSL;
import org.springframework.data.domain.AuditorAware;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class MediaFileRepositoryCustomImpl implements MediaFileRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;
  private final AuditorAware<UUID> auditorAware;

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

  @Override
  @Transactional
  public boolean tryRecordMatchingFailure(UUID mediaFileId, MatchingFailure failure) {
    ItemResultFailureReason reason = null;
    if (failure.reason() != null) {
      reason = ItemResultFailureReason.lookupLiteral(failure.reason().name());
    }

    return dsl.update(MEDIA_FILE)
            .set(MEDIA_FILE.STATUS, toJooq(failure.status()))
            .set(MEDIA_FILE.FAILURE_REASON, reason)
            .set(MEDIA_FILE.LAST_MODIFIED_ON, DSL.currentOffsetDateTime())
            .set(MEDIA_FILE.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
            .where(MEDIA_FILE.ID.eq(mediaFileId))
            .and(MEDIA_FILE.STATUS.ne(toJooq(MediaFileStatus.MATCHED)))
            .execute()
        > 0;
  }

  // The generated enum shares its name with the domain enum it mirrors.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.MediaFileStatus toJooq(
      MediaFileStatus status) {
    return com.streamarr.server.jooq.generated.enums.MediaFileStatus.lookupLiteral(status.name());
  }
}
