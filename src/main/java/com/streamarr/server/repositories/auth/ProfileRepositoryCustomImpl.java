package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.Profile.PROFILE;
import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.jooq.generated.enums.ProfileShareStatus;
import com.streamarr.server.repositories.JooqQueryHelper;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

@RequiredArgsConstructor
public class ProfileRepositoryCustomImpl implements ProfileRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;

  @Override
  public List<Profile> findAvailableInHousehold(UUID householdId) {
    var query =
        dsl.select(PROFILE.fields())
            .from(PROFILE)
            .join(PROFILE_HOUSEHOLD_SHARE)
            .on(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(PROFILE.ID))
            .where(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(householdId))
            // Inlined: a JPA native query would bind the enum as an ordinal smallint.
            .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(DSL.inline(ProfileShareStatus.ACTIVE)))
            .orderBy(PROFILE.NAME.asc(), PROFILE.ID.asc());
    return JooqQueryHelper.nativeQuery(entityManager, query, Profile.class);
  }
}
