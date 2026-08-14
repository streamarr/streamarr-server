package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.enums.ProfileShareStatus.PENDING;
import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class ProfileHouseholdShareRepositoryCustomImpl
    implements ProfileHouseholdShareRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public ProfileHouseholdShareInsertResult insertPendingIfAbsent(UUID profileId, UUID householdId) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    var pending = PENDING;
    while (true) {
      var insertedId =
          dsl.insertInto(PROFILE_HOUSEHOLD_SHARE)
              .set(PROFILE_HOUSEHOLD_SHARE.ID, UUID.randomUUID())
              .set(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID, profileId)
              .set(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID, householdId)
              .set(PROFILE_HOUSEHOLD_SHARE.STATUS, pending)
              .set(PROFILE_HOUSEHOLD_SHARE.CREATED_BY, auditUser)
              .set(PROFILE_HOUSEHOLD_SHARE.LAST_MODIFIED_BY, auditUser)
              .onConflict(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID, PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID)
              .doNothing()
              .returning(PROFILE_HOUSEHOLD_SHARE.ID)
              .fetchOne(PROFILE_HOUSEHOLD_SHARE.ID);
      if (insertedId != null) {
        return new ProfileHouseholdShareInsertResult(
            share(insertedId, profileId, householdId, ProfileShareStatus.PENDING), true);
      }

      var existing =
          dsl.select(PROFILE_HOUSEHOLD_SHARE.ID, PROFILE_HOUSEHOLD_SHARE.STATUS)
              .from(PROFILE_HOUSEHOLD_SHARE)
              .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId))
              .and(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(householdId))
              .forUpdate()
              .fetchOptional();
      if (existing.isPresent()) {
        var record = existing.orElseThrow();
        return new ProfileHouseholdShareInsertResult(
            share(
                record.value1(),
                profileId,
                householdId,
                ProfileShareStatus.valueOf(record.value2().getLiteral())),
            false);
      }
    }
  }

  private ProfileHouseholdShare share(
      UUID id, UUID profileId, UUID householdId, ProfileShareStatus status) {
    return ProfileHouseholdShare.builder()
        .id(id)
        .profileId(profileId)
        .householdId(householdId)
        .status(status)
        .build();
  }
}
