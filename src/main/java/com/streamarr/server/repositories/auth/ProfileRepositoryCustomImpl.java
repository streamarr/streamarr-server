package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.enums.ProfileKind.ADULT;
import static com.streamarr.server.jooq.generated.tables.HouseholdGuard.HOUSEHOLD_GUARD;
import static com.streamarr.server.jooq.generated.tables.Profile.PROFILE;
import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfilePolicySnapshot;
import com.streamarr.server.domain.auth.ProfilePolicyTarget;
import com.streamarr.server.jooq.generated.enums.ProfileShareStatus;
import com.streamarr.server.jooq.generated.tables.records.ProfileRecord;
import com.streamarr.server.repositories.JooqQueryHelper;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.SelectSeekStep1;
import org.jooq.UpdateSetMoreStep;
import org.jooq.impl.DSL;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class ProfileRepositoryCustomImpl implements ProfileRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;
  private final AuditorAware<UUID> auditorAware;
  private final Clock clock;

  @Override
  public boolean lockByShareId(UUID shareId) {
    return dsl.selectOne()
        .from(PROFILE)
        .join(PROFILE_HOUSEHOLD_SHARE)
        .on(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(PROFILE.ID))
        .where(PROFILE_HOUSEHOLD_SHARE.ID.eq(shareId))
        .forUpdate()
        .of(PROFILE)
        .fetchOptional()
        .isPresent();
  }

  @Override
  public boolean lockSharedByShareId(UUID shareId) {
    return dsl.selectOne()
        .from(PROFILE)
        .join(PROFILE_HOUSEHOLD_SHARE)
        .on(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(PROFILE.ID))
        .where(PROFILE_HOUSEHOLD_SHARE.ID.eq(shareId))
        .forShare()
        .of(PROFILE)
        .fetchOptional()
        .isPresent();
  }

  @Override
  public Optional<ProfilePolicySnapshot> lockPolicyById(UUID profileId) {
    return dsl.select(PROFILE.KIND, PROFILE.MAXIMUM_ALLOWED_RATING_AGE, USER_ACCOUNT.ID)
        .from(PROFILE)
        .leftJoin(USER_ACCOUNT)
        .on(USER_ACCOUNT.PERSONAL_PROFILE_ID.eq(PROFILE.ID))
        .where(PROFILE.ID.eq(profileId))
        .forUpdate()
        .of(PROFILE)
        .fetchOptional(
            row ->
                new ProfilePolicySnapshot(
                    ProfileKind.valueOf(row.value1().name()), row.value2(), row.value3()));
  }

  @Override
  public boolean lockIfUnrestricted(UUID profileId) {
    return dsl.select(PROFILE.ID)
        .from(PROFILE)
        .where(PROFILE.ID.eq(profileId))
        .and(PROFILE.KIND.eq(ADULT))
        .and(PROFILE.MAXIMUM_ALLOWED_RATING_AGE.isNull())
        .forShare()
        .fetchOptional()
        .isPresent();
  }

  @Override
  public boolean lockById(UUID profileId) {
    return dsl.select(PROFILE.ID)
        .from(PROFILE)
        .where(PROFILE.ID.eq(profileId))
        .forUpdate()
        .fetchOptional()
        .isPresent();
  }

  @Override
  public void lockProfileAvailabilityAcrossHouseholds(UUID profileId) {
    var householdIds =
        profileHouseholdIds(
            profileId, PROFILE_HOUSEHOLD_SHARE.STATUS.eq(DSL.inline(ProfileShareStatus.ACTIVE)));
    var guards = orderedHouseholdGuards(householdIds);
    guards.forShare().fetch();
  }

  @Override
  public void lockProfileTransitionAcrossHouseholds(
      UUID profileId, List<UUID> additionalHouseholdIds) {
    var householdIds =
        profileHouseholdIds(
            profileId,
            PROFILE_HOUSEHOLD_SHARE.STATUS.in(
                DSL.inline(ProfileShareStatus.ACTIVE), DSL.inline(ProfileShareStatus.PENDING)));
    householdIds.addAll(additionalHouseholdIds);
    var guards = orderedHouseholdGuards(householdIds);
    guards.forUpdate().fetch();
  }

  @Override
  public void lockProfileDeletionAcrossHouseholds(UUID profileId) {
    var guards = orderedHouseholdGuards(profileHouseholdIds(profileId, DSL.noCondition()));
    guards.forUpdate().fetch();
  }

  private Set<UUID> profileHouseholdIds(UUID profileId, Condition shareCondition) {
    var householdIds =
        new HashSet<>(
            dsl.select(PROFILE.HOUSEHOLD_ID)
                .from(PROFILE)
                .where(PROFILE.ID.eq(profileId))
                .fetchSet(PROFILE.HOUSEHOLD_ID));
    householdIds.addAll(
        dsl.select(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID)
            .from(PROFILE_HOUSEHOLD_SHARE)
            .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId))
            .and(shareCondition)
            .fetchSet(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID));
    return householdIds;
  }

  private SelectSeekStep1<Record1<UUID>, UUID> orderedHouseholdGuards(Set<UUID> householdIds) {
    return dsl.select(HOUSEHOLD_GUARD.HOUSEHOLD_ID)
        .from(HOUSEHOLD_GUARD)
        .where(HOUSEHOLD_GUARD.HOUSEHOLD_ID.in(householdIds))
        .orderBy(HOUSEHOLD_GUARD.HOUSEHOLD_ID);
  }

  @Override
  public boolean tryApplyPolicy(UUID profileId, ProfilePolicyTarget target) {
    return dsl.update(PROFILE)
            .set(PROFILE.KIND, jooqKind(target.kind()))
            .set(PROFILE.MAXIMUM_ALLOWED_RATING_AGE, target.maximumAllowedRatingAge())
            .set(PROFILE.LAST_MODIFIED_ON, clock.instant().atOffset(ZoneOffset.UTC))
            .set(PROFILE.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
            .where(PROFILE.ID.eq(profileId))
            .execute()
        > 0;
  }

  @Override
  public boolean tryRehome(UUID profileId, UUID expectedHouseholdId, UUID destinationHouseholdId) {
    return dsl.update(PROFILE)
            .set(PROFILE.HOUSEHOLD_ID, destinationHouseholdId)
            .set(PROFILE.LAST_MODIFIED_ON, clock.instant().atOffset(ZoneOffset.UTC))
            .set(PROFILE.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
            .where(PROFILE.ID.eq(profileId))
            .and(PROFILE.HOUSEHOLD_ID.eq(expectedHouseholdId))
            .execute()
        > 0;
  }

  @Override
  public boolean tryDeleteUnlinked(UUID profileId) {
    var profileExists =
        dsl.select(PROFILE.ID)
            .from(PROFILE)
            .where(PROFILE.ID.eq(profileId))
            .forUpdate()
            .fetchOptional()
            .isPresent();
    if (!profileExists || hasLinkedAccount(profileId)) {
      return false;
    }

    return dsl.deleteFrom(PROFILE).where(PROFILE.ID.eq(profileId)).execute() > 0;
  }

  private boolean hasLinkedAccount(UUID profileId) {
    return dsl.fetchExists(
        dsl.selectOne().from(USER_ACCOUNT).where(USER_ACCOUNT.PERSONAL_PROFILE_ID.eq(profileId)));
  }

  @Override
  public boolean tryRename(UUID profileId, String name) {
    return updateColumn(profileId, update -> update.set(PROFILE.NAME, name));
  }

  @Override
  public boolean trySetPicture(UUID profileId, String picture) {
    return updateColumn(profileId, update -> update.set(PROFILE.PICTURE, picture));
  }

  @Override
  public boolean trySetPinHash(UUID profileId, String pinHash) {
    return updateColumn(profileId, update -> update.set(PROFILE.PIN_HASH, pinHash));
  }

  private boolean updateColumn(
      UUID profileId, UnaryOperator<UpdateSetMoreStep<ProfileRecord>> change) {
    var update =
        dsl.update(PROFILE).set(PROFILE.LAST_MODIFIED_ON, clock.instant().atOffset(ZoneOffset.UTC));
    return change
            .apply(
                update.set(PROFILE.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null)))
            .where(PROFILE.ID.eq(profileId))
            .execute()
        > 0;
  }

  // Unavoidable collision: the domain and the generated jOOQ enum share the simple name.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.ProfileKind jooqKind(ProfileKind kind) {
    return com.streamarr.server.jooq.generated.enums.ProfileKind.valueOf(kind.name());
  }

  @Override
  public Optional<Profile> findRefreshedById(UUID profileId) {
    var entity = entityManager.find(Profile.class, profileId);
    if (entity == null) {
      return Optional.empty();
    }

    entityManager.refresh(entity);
    return Optional.of(entity);
  }

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

  @Override
  public boolean existsAvailableInHouseholdWithNameIgnoreCase(UUID householdId, String name) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PROFILE)
            .join(PROFILE_HOUSEHOLD_SHARE)
            .on(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(PROFILE.ID))
            .where(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(householdId))
            .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(DSL.inline(ProfileShareStatus.ACTIVE)))
            .and(PROFILE.NAME.equalIgnoreCase(name)));
  }
}
