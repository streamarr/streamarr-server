package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.Person.PERSON;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class PersonRepositoryCustomImpl implements PersonRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public boolean insertOnConflictDoNothing(String sourceId, String name) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    var rowsAffected =
        dsl.insertInto(PERSON)
            .set(PERSON.SOURCE_ID, sourceId)
            .set(PERSON.NAME, name)
            .set(PERSON.CREATED_BY, auditUser)
            .set(PERSON.LAST_MODIFIED_BY, auditUser)
            .onConflict(PERSON.SOURCE_ID)
            .doNothing()
            .execute();
    return rowsAffected > 0;
  }
}
