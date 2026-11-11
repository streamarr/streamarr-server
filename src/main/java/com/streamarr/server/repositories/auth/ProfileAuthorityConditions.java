package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.Profile.PROFILE;
import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static com.streamarr.server.jooq.generated.tables.ProfileManager.PROFILE_MANAGER;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;
import static org.jooq.impl.DSL.inline;

import com.streamarr.server.jooq.generated.enums.HouseholdRole;
import com.streamarr.server.jooq.generated.enums.ProfileKind;
import com.streamarr.server.jooq.generated.enums.ProfileShareStatus;
import com.streamarr.server.jooq.generated.tables.Profile;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * Who manages and who supervises a row of {@code PROFILE}, in SQL, as the Cedar facts {@code
 * managedByPrincipal} (ProfileManagementContributor) and {@code supervisedByPrincipal}
 * (ProfileSupervisionContributor) decide it for one Account. A read that answers for many Profiles
 * at once uses these in place of one decision for each Profile, so they must stay equal to those
 * facts; ProfileReaderAuthorityParityIT holds them together.
 */
public final class ProfileAuthorityConditions {

  private ProfileAuthorityConditions() {}

  /**
   * The Account holds a direct grant for the Profile, or the Profile is the Account's own Personal
   * Profile while it is an unrestricted Adult.
   */
  public static Condition managedBy(Field<UUID> accountId) {
    var owner = USER_ACCOUNT.as("managing_owner");
    return DSL.exists(
            DSL.selectOne()
                .from(PROFILE_MANAGER)
                .where(PROFILE_MANAGER.ACCOUNT_ID.eq(accountId))
                .and(PROFILE_MANAGER.PROFILE_ID.eq(PROFILE.ID)))
        .or(
            unrestrictedAdult(PROFILE)
                .andExists(
                    DSL.selectOne()
                        .from(owner)
                        .where(owner.ID.eq(accountId))
                        .and(owner.PERSONAL_PROFILE_ID.eq(PROFILE.ID))));
  }

  /**
   * The Profile is restricted and available in the Account's membership Household, where the
   * Account is a HouseholdAdmin whose own Personal Profile is an unrestricted Adult.
   */
  public static Condition supervisedBy(Field<UUID> accountId) {
    var admin = USER_ACCOUNT.as("supervising_admin");
    var adminsProfile = PROFILE.as("supervising_admins_profile");
    return DSL.not(unrestrictedAdult(PROFILE))
        .andExists(
            DSL.selectOne()
                .from(admin)
                .join(adminsProfile)
                .on(adminsProfile.ID.eq(admin.PERSONAL_PROFILE_ID))
                .join(PROFILE_HOUSEHOLD_SHARE)
                .on(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(admin.HOUSEHOLD_ID))
                .where(admin.ID.eq(accountId))
                .and(admin.HOUSEHOLD_ROLE.eq(inline(HouseholdRole.ADMIN)))
                .and(unrestrictedAdult(adminsProfile))
                .and(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(PROFILE.ID))
                .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(inline(ProfileShareStatus.ACTIVE))));
  }

  // Inlined: a JPA native query would bind an enum as an ordinal smallint.
  private static Condition unrestrictedAdult(Profile profile) {
    return profile
        .KIND
        .eq(inline(ProfileKind.ADULT))
        .and(profile.MAXIMUM_ALLOWED_RATING_AGE.isNull());
  }
}
