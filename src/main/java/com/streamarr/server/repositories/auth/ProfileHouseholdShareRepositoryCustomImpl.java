package com.streamarr.server.repositories.auth;

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
  public ProfileHouseholdShare insertPendingIfAbsent(UUID profileId, UUID householdId) {
    var id = UUID.randomUUID();
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    var pending = com.streamarr.server.jooq.generated.enums.ProfileShareStatus.PENDING;
    var insertedId =
        dsl.insertInto(PROFILE_HOUSEHOLD_SHARE)
            .set(PROFILE_HOUSEHOLD_SHARE.ID, id)
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
      return share(insertedId, profileId, householdId, ProfileShareStatus.PENDING);
    }

    var existing =
        dsl.select(PROFILE_HOUSEHOLD_SHARE.ID, PROFILE_HOUSEHOLD_SHARE.STATUS)
            .from(PROFILE_HOUSEHOLD_SHARE)
            .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId))
            .and(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(householdId))
            .fetchSingle();
    return share(
        existing.value1(),
        profileId,
        householdId,
        ProfileShareStatus.valueOf(existing.value2().getLiteral()));
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
