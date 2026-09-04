package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.enums.HouseholdRole.ADMIN;
import static com.streamarr.server.jooq.generated.enums.HouseholdRole.MEMBER;
import static com.streamarr.server.jooq.generated.enums.HouseholdRole.valueOf;
import static com.streamarr.server.jooq.generated.tables.HouseholdGuard.HOUSEHOLD_GUARD;
import static com.streamarr.server.jooq.generated.tables.Profile.PROFILE;
import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.rowNumber;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

import com.streamarr.server.domain.auth.AccountAuthorityFacts;
import com.streamarr.server.domain.auth.AccountShareFacts;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileManagerEligibility;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.jooq.generated.enums.ProfileShareStatus;
import com.streamarr.server.jooq.generated.tables.records.UserAccountRecord;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.SortOrder;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.springframework.data.domain.AuditorAware;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@RequiredArgsConstructor
public class UserAccountRepositoryCustomImpl implements UserAccountRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;
  private final AuditorAware<UUID> auditorAware;
  private final Clock clock;

  @Override
  public void refresh(UserAccount account) {
    entityManager.refresh(account);
  }

  @Override
  public Map<UUID, List<UserAccount>> findAdministrationPages(
      Set<UUID> householdIds, MediaPaginationOptions options) {
    if (householdIds.isEmpty()) {
      return Map.of();
    }

    var reverse =
        options.getPaginationOptions().getPaginationDirection() == PaginationDirection.REVERSE;
    var sortOrder = reverse ? SortOrder.DESC : SortOrder.ASC;
    var displayName = lower(USER_ACCOUNT.DISPLAY_NAME);
    var cursorCondition = accountCursorCondition(options, displayName, sortOrder);
    var cursorFirst =
        options
            .getCursorId()
            .map(cursorId -> when(USER_ACCOUNT.ID.eq(cursorId), 0).otherwise(1))
            .orElse(val(0));
    var pageRow =
        rowNumber()
            .over()
            .partitionBy(USER_ACCOUNT.HOUSEHOLD_ID)
            .orderBy(
                cursorFirst.asc(), displayName.sort(sortOrder), USER_ACCOUNT.ID.sort(sortOrder))
            .as("administration_page_row");
    var ranked =
        dsl.select(USER_ACCOUNT.asterisk())
            .select(pageRow)
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.HOUSEHOLD_ID.in(householdIds))
            .and(cursorCondition)
            .asTable("ranked_administration_accounts");
    var rankedHouseholdId = ranked.field(USER_ACCOUNT.HOUSEHOLD_ID);
    var rankedPageRow = ranked.field(pageRow);
    var extraRows = options.getCursorId().isPresent() ? 2 : 1;
    var query =
        dsl.select(ranked.fields())
            .from(ranked)
            .where(rankedPageRow.le(options.getPaginationOptions().getLimit() + extraRows))
            .orderBy(rankedHouseholdId.asc(), rankedPageRow.asc());
    var accounts = JooqQueryHelper.nativeQuery(entityManager, query, UserAccount.class);
    Map<UUID, List<UserAccount>> pages = new LinkedHashMap<>();
    accounts.forEach(
        account ->
            pages.computeIfAbsent(account.getHouseholdId(), _ -> new ArrayList<>()).add(account));
    if (reverse) {
      pages.values().forEach(Collections::reverse);
    }

    return pages;
  }

  private Condition accountCursorCondition(
      MediaPaginationOptions options, Field<String> displayName, SortOrder sortOrder) {
    if (options.getCursorId().isEmpty()) {
      return noCondition();
    }

    var cursorName = lower(val(options.getMediaFilter().getPreviousSortFieldValue().toString()));
    var cursorId = options.getCursorId().orElseThrow();
    var fields = row(displayName, USER_ACCOUNT.ID);
    var cursor = row(cursorName, val(cursorId));
    var afterCursor =
        sortOrder == SortOrder.ASC ? fields.greaterThan(cursor) : fields.lessThan(cursor);
    return USER_ACCOUNT.ID.eq(cursorId).or(USER_ACCOUNT.ID.ne(cursorId).and(afterCursor));
  }

  @Override
  public Optional<AccountAuthorityFacts> findAuthorityFacts(UUID accountId) {
    return dsl.select(USER_ACCOUNT.ENABLED, USER_ACCOUNT.SERVER_ADMIN)
        .from(USER_ACCOUNT)
        .where(USER_ACCOUNT.ID.eq(accountId))
        .forShare()
        .fetchOptional(row -> new AccountAuthorityFacts(row.value1(), row.value2()));
  }

  @Override
  public Optional<AccountShareFacts> findShareFacts(UUID accountId) {
    return dsl.select(
            USER_ACCOUNT.HOUSEHOLD_ID,
            USER_ACCOUNT.HOUSEHOLD_ROLE,
            USER_ACCOUNT.PERSONAL_PROFILE_ID)
        .from(USER_ACCOUNT)
        .where(USER_ACCOUNT.ID.eq(accountId))
        .forShare()
        .fetchOptional(
            row ->
                new AccountShareFacts(
                    row.value1(), HouseholdRole.valueOf(row.value2().name()), row.value3()));
  }

  @Override
  public boolean mayUseHousehold(UUID accountId, UUID householdId) {
    var personalProfileShared =
        DSL.exists(
            DSL.selectOne()
                .from(PROFILE_HOUSEHOLD_SHARE)
                .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(USER_ACCOUNT.PERSONAL_PROFILE_ID))
                .and(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(householdId))
                .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.ACTIVE)));
    return dsl.fetchExists(
        dsl.selectOne()
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.ID.eq(accountId))
            .and(USER_ACCOUNT.HOUSEHOLD_ID.eq(householdId).or(personalProfileShared)));
  }

  @Override
  public List<UUID> findUsableHouseholdIds(UUID accountId) {
    var isMembership = PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(USER_ACCOUNT.HOUSEHOLD_ID);
    return dsl.selectDistinct(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID, isMembership)
        .from(USER_ACCOUNT)
        .join(PROFILE_HOUSEHOLD_SHARE)
        .on(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(USER_ACCOUNT.PERSONAL_PROFILE_ID))
        .where(USER_ACCOUNT.ID.eq(accountId))
        .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(ProfileShareStatus.ACTIVE))
        .orderBy(isMembership.desc(), PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.asc())
        .fetch(Record2::value1);
  }

  @Override
  public Set<UUID> lockByIds(Set<UUID> accountIds, Duration timeout) {
    requireActiveTransaction();
    dsl.setLocal(DSL.name("lock_timeout"), DSL.inline(timeout.toMillis() + "ms")).execute();
    return dsl.select(USER_ACCOUNT.ID)
        .from(USER_ACCOUNT)
        .where(USER_ACCOUNT.ID.in(accountIds))
        .orderBy(USER_ACCOUNT.ID)
        .forUpdate()
        .fetchSet(USER_ACCOUNT.ID);
  }

  /** Outside a transaction, SET LOCAL only warns and the timeout would silently not apply. */
  private static void requireActiveTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Account row locks require an active transaction.");
    }
  }

  @Override
  public boolean tryGrantServerAdmin(UUID accountId) {
    return transition(
        accountId, USER_ACCOUNT.SERVER_ADMIN, true, USER_ACCOUNT.SERVER_ADMIN.isFalse());
  }

  @Override
  public boolean tryRevokeServerAdmin(UUID accountId) {
    return transition(
        accountId, USER_ACCOUNT.SERVER_ADMIN, false, USER_ACCOUNT.SERVER_ADMIN.isTrue());
  }

  @Override
  public boolean tryPromoteToHouseholdAdmin(UUID accountId) {
    return transition(
        accountId, USER_ACCOUNT.HOUSEHOLD_ROLE, ADMIN, USER_ACCOUNT.HOUSEHOLD_ROLE.ne(ADMIN));
  }

  @Override
  public boolean tryDemoteToHouseholdMember(UUID accountId) {
    return transition(
        accountId, USER_ACCOUNT.HOUSEHOLD_ROLE, MEMBER, USER_ACCOUNT.HOUSEHOLD_ROLE.ne(MEMBER));
  }

  @Override
  public boolean tryDisable(UUID accountId) {
    return transition(accountId, USER_ACCOUNT.ENABLED, false, USER_ACCOUNT.ENABLED.isTrue());
  }

  @Override
  public boolean tryEnable(UUID accountId) {
    return transition(accountId, USER_ACCOUNT.ENABLED, true, USER_ACCOUNT.ENABLED.isFalse());
  }

  @Override
  public boolean tryRename(UUID accountId, String displayName) {
    return transition(accountId, USER_ACCOUNT.DISPLAY_NAME, displayName, DSL.trueCondition());
  }

  @Override
  public boolean trySetPasswordHash(UUID accountId, String passwordHash) {
    return transition(accountId, USER_ACCOUNT.PASSWORD_HASH, passwordHash, DSL.trueCondition());
  }

  private <V> boolean transition(
      UUID accountId, TableField<UserAccountRecord, V> field, V value, Condition transitionable) {
    return dsl.update(USER_ACCOUNT)
            .set(field, value)
            .set(USER_ACCOUNT.LAST_MODIFIED_ON, clock.instant().atOffset(ZoneOffset.UTC))
            .set(USER_ACCOUNT.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
            .where(USER_ACCOUNT.ID.eq(accountId))
            .and(transitionable)
            .execute()
        > 0;
  }

  @Override
  public Optional<UserAccount> findByIdAndReloadFromDatabase(UUID accountId) {
    var entity = entityManager.find(UserAccount.class, accountId);
    if (entity == null) {
      return Optional.empty();
    }

    entityManager.refresh(entity);
    return Optional.of(entity);
  }

  @Override
  public boolean tryTransfer(
      UUID accountId, UUID expectedHouseholdId, UUID destinationHouseholdId, HouseholdRole role) {
    return dsl.update(USER_ACCOUNT)
            .set(USER_ACCOUNT.HOUSEHOLD_ID, destinationHouseholdId)
            .set(USER_ACCOUNT.HOUSEHOLD_ROLE, valueOf(role.name()))
            .set(USER_ACCOUNT.LAST_MODIFIED_ON, clock.instant().atOffset(ZoneOffset.UTC))
            .set(USER_ACCOUNT.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
            .where(USER_ACCOUNT.ID.eq(accountId))
            .and(USER_ACCOUNT.HOUSEHOLD_ID.eq(expectedHouseholdId))
            .execute()
        > 0;
  }

  @Override
  public boolean tryDelete(UUID accountId, UUID expectedHouseholdId) {
    return dsl.deleteFrom(USER_ACCOUNT)
            .where(USER_ACCOUNT.ID.eq(accountId))
            .and(USER_ACCOUNT.HOUSEHOLD_ID.eq(expectedHouseholdId))
            .execute()
        > 0;
  }

  @Override
  public boolean lockIfCredentialsUnchanged(UUID accountId, String expectedPasswordHash) {
    return lockWhere(
        USER_ACCOUNT
            .ID
            .eq(accountId)
            .and(USER_ACCOUNT.PASSWORD_HASH.eq(expectedPasswordHash))
            .and(USER_ACCOUNT.ENABLED.isTrue()));
  }

  @Override
  public boolean tryLockEnabledServerAdmin(UUID accountId) {
    return lockWhere(
        USER_ACCOUNT
            .ID
            .eq(accountId)
            .and(USER_ACCOUNT.ENABLED.isTrue())
            .and(USER_ACCOUNT.SERVER_ADMIN.isTrue()));
  }

  /** One row lock, taken only while the condition still holds; false means nothing was locked. */
  private boolean lockWhere(Condition condition) {
    return dsl.select(USER_ACCOUNT.ID)
        .from(USER_ACCOUNT)
        .where(condition)
        .forUpdate()
        .fetchOptional()
        .isPresent();
  }

  @Override
  public Optional<HouseholdRole> roleForNewAccount(UUID householdId, HouseholdRole requestedRole) {
    var locked =
        dsl.select(HOUSEHOLD_GUARD.HOUSEHOLD_ID)
            .from(HOUSEHOLD_GUARD)
            .where(HOUSEHOLD_GUARD.HOUSEHOLD_ID.eq(householdId))
            .forUpdate()
            .fetchOptional()
            .isPresent();
    if (!locked) {
      return Optional.empty();
    }

    var householdHasAccount =
        dsl.fetchExists(
            dsl.selectOne().from(USER_ACCOUNT).where(USER_ACCOUNT.HOUSEHOLD_ID.eq(householdId)));
    return Optional.of(householdHasAccount ? requestedRole : HouseholdRole.ADMIN);
  }

  @Override
  public boolean isEligibleProfileManager(
      UUID accountId, UUID householdId, ProfileManagerEligibility eligibility) {
    var eligible =
        USER_ACCOUNT
            .ID
            .eq(accountId)
            .and(USER_ACCOUNT.HOUSEHOLD_ID.eq(householdId))
            .and(PROFILE.RESTRICTED.isFalse());
    if (eligibility == ProfileManagerEligibility.HOUSEHOLD_ADMIN) {
      eligible = eligible.and(USER_ACCOUNT.HOUSEHOLD_ROLE.eq(ADMIN));
    }

    return dsl.fetchExists(
        dsl.selectOne()
            .from(USER_ACCOUNT)
            .join(PROFILE)
            .on(PROFILE.ID.eq(USER_ACCOUNT.PERSONAL_PROFILE_ID))
            .where(eligible));
  }
}
