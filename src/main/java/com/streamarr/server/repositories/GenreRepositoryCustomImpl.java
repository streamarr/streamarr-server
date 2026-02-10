package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.Genre.GENRE;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class GenreRepositoryCustomImpl implements GenreRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public boolean insertOnConflictDoNothing(String sourceId, String name) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    var rowsAffected =
        dsl.insertInto(GENRE)
            .set(GENRE.SOURCE_ID, sourceId)
            .set(GENRE.NAME, name)
            .set(GENRE.CREATED_BY, auditUser)
            .set(GENRE.LAST_MODIFIED_BY, auditUser)
            .onConflict(GENRE.SOURCE_ID)
            .doNothing()
            .execute();
    return rowsAffected > 0;
  }
}
