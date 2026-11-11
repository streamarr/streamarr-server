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
 * The Accounts that a share's read rule could allow, in SQL: the rule that the Account may view the
 * Profile's shares or the target Household's administration (sharing.cedar's {@code
 * viewProfileShareHouseholds} and reads.cedar's {@code viewHouseholdAdministration} permits). Cedar
 * still decides every row this lets through; the condition only spares it the rows no permit can
 * allow, so it must stay as wide as those permits.
 */
public final class ProfileShareReaders {

  private ProfileShareReaders() {}

  /**
   * True when a permit could allow the Account to read a share of the Profile into the Household:
   * the Account is a ServerAdmin, a direct ProfileManager of the Profile or the Account whose
   * Personal Profile it is, or a HouseholdAdmin of the Household. Administering another Household
   * that hosts the Profile is not enough.
   */
  public static Condition couldRead(
      Field<UUID> accountId, Field<UUID> profileId, Field<UUID> householdId) {
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
                                .and(reader.HOUSEHOLD_ID.eq(householdId)))))
        .orExists(
            DSL.selectOne()
                .from(PROFILE_MANAGER)
                .where(PROFILE_MANAGER.ACCOUNT_ID.eq(accountId))
                .and(PROFILE_MANAGER.PROFILE_ID.eq(profileId)));
  }
}
