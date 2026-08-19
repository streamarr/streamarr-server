package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.Household.HOUSEHOLD;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class HouseholdRepositoryCustomImpl implements HouseholdRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;
  private final Clock clock;

  @Override
  public boolean tryRename(UUID householdId, String name) {
    return dsl.update(HOUSEHOLD)
            .set(HOUSEHOLD.NAME, name)
            .set(HOUSEHOLD.LAST_MODIFIED_ON, clock.instant().atOffset(ZoneOffset.UTC))
            .set(HOUSEHOLD.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
            .where(HOUSEHOLD.ID.eq(householdId))
            .execute()
        > 0;
  }
}
