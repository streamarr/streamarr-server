package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ProfileManager.PROFILE_MANAGER;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;
import static org.jooq.impl.DSL.inline;

import com.streamarr.server.jooq.generated.enums.HouseholdRole;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * The Accounts that a sharing Notification's guard could pass, in SQL (ADR 0063 §The sharing
 * kinds): the offering side, as sharing.cedar's {@code viewProfileShareHouseholds} permit allows it
 * at every status, and the Household's side, as reads.cedar's {@code viewHouseholdAdministration}
 * permit allows it and notifications.cedar narrows it once the offer or the share is over. Cedar
 * still decides every row this lets through; the condition only spares it the rows no permit can
 * allow, so it must stay as wide as those permits.
 */
public final class ProfileShareReaders {

  private ProfileShareReaders() {}

  /**
   * True when a permit could pass the Account for a Notification about a share of the Profile into
   * the Household: the Account is a ServerAdmin, a direct ProfileManager of the Profile or the
   * Account whose Personal Profile it is, or a HouseholdAdmin of the Household while the
   * Household's side still reads the Notification. Administering another Household that hosts the
   * Profile is not enough.
   *
   * @param householdSideReads whether the Household's side may still read the Notification: the
   *     offer or the share is live, or the Notification was addressed to that side. It may be wider
   *     than Cedar's reading, such as a stored status that has not yet caught up with an expiry.
   */
  public static Condition couldRead(
      Field<UUID> accountId,
      Field<UUID> profileId,
      Field<UUID> householdId,
      Condition householdSideReads) {
    var reader = USER_ACCOUNT.as("share_reader");
    return DSL.exists(
            DSL.selectOne()
                .from(reader)
                .where(reader.ID.eq(accountId))
                .and(
                    reader
                        .SERVER_ADMIN
                        .isTrue()
                        .or(reader.PERSONAL_PROFILE_ID.eq(profileId))
                        .or(
                            reader
                                .HOUSEHOLD_ROLE
                                .eq(inline(HouseholdRole.ADMIN))
                                .and(reader.HOUSEHOLD_ID.eq(householdId))
                                .and(householdSideReads))))
        .orExists(
            DSL.selectOne()
                .from(PROFILE_MANAGER)
                .where(PROFILE_MANAGER.ACCOUNT_ID.eq(accountId))
                .and(PROFILE_MANAGER.PROFILE_ID.eq(profileId)));
  }
}
