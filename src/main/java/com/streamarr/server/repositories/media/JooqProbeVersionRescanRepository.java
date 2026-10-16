package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.MediaFileContainerInfo.MEDIA_FILE_CONTAINER_INFO;
import static com.streamarr.server.jooq.generated.tables.MediaFileProbeTaskRequest.MEDIA_FILE_PROBE_TASK_REQUEST;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JooqProbeVersionRescanRepository implements ProbeVersionRescanRepository {

  private final DSLContext dsl;

  @Override
  @Transactional(readOnly = true)
  public List<UUID> findLibraryIdsProbedBefore(int probeVersion) {
    // PostgreSQL's GREATEST ignores a NULL argument and is NULL only when both are NULL.
    return dsl.selectDistinct(MEDIA_FILE.LIBRARY_ID)
        .from(MEDIA_FILE)
        .leftJoin(MEDIA_FILE_PROBE_TASK_REQUEST)
        .on(MEDIA_FILE_PROBE_TASK_REQUEST.MEDIA_FILE_ID.eq(MEDIA_FILE.ID))
        .leftJoin(MEDIA_FILE_CONTAINER_INFO)
        .on(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(MEDIA_FILE.ID))
        .where(
            DSL.greatest(
                    MEDIA_FILE_PROBE_TASK_REQUEST.PROBE_VERSION,
                    MEDIA_FILE_CONTAINER_INFO.PROBE_VERSION)
                .lt(probeVersion))
        .fetch(MEDIA_FILE.LIBRARY_ID);
  }
}
