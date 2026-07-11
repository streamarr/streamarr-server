package com.streamarr.server.repositories.streaming;

import static com.streamarr.server.jooq.generated.tables.AccountProfile.ACCOUNT_PROFILE;
import static com.streamarr.server.jooq.generated.tables.AuthSession.AUTH_SESSION;
import static com.streamarr.server.jooq.generated.tables.HouseholdMembership.HOUSEHOLD_MEMBERSHIP;
import static com.streamarr.server.jooq.generated.tables.LibraryDeletionIntent.LIBRARY_DELETION_INTENT;
import static com.streamarr.server.jooq.generated.tables.MediaFile.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.tables.MediaFileDeletionIntent.MEDIA_FILE_DELETION_INTENT;
import static com.streamarr.server.jooq.generated.tables.Profile.PROFILE;
import static com.streamarr.server.jooq.generated.tables.StreamSession.STREAM_SESSION;
import static com.streamarr.server.jooq.generated.tables.StreamSessionTerminationIntent.STREAM_SESSION_TERMINATION_INTENT;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;

import com.streamarr.server.jooq.generated.enums.StreamSessionStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Select;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StreamSessionEnforcementRepositoryImpl implements StreamSessionEnforcementRepository {

  private final DSLContext dsl;

  @Override
  public Optional<Instant> admit(StreamSessionAuthority authority, Duration provisioningTimeout) {
    if (!lockMediaSource(authority)) {
      return Optional.empty();
    }

    var admittedAt =
        dsl.insertInto(
                STREAM_SESSION,
                STREAM_SESSION.ID,
                STREAM_SESSION.AUTH_SESSION_ID,
                STREAM_SESSION.ACCOUNT_ID,
                STREAM_SESSION.HOUSEHOLD_ID,
                STREAM_SESSION.PROFILE_ID,
                STREAM_SESSION.MEDIA_FILE_ID,
                STREAM_SESSION.STATUS)
            .select(
                DSL.select(
                        DSL.val(authority.streamSessionId()),
                        DSL.val(authority.authSessionId()),
                        DSL.val(authority.accountId()),
                        DSL.val(authority.householdId()),
                        DSL.val(authority.profileId()),
                        DSL.val(authority.mediaFileId()),
                        DSL.val(StreamSessionStatus.PROVISIONING))
                    .whereExists(liveAuthority(authority))
                    .andExists(mediaSource(DSL.val(authority.mediaFileId()))))
            .onConflict(STREAM_SESSION.ID)
            .doNothing()
            .returning(STREAM_SESSION.LAST_ACCESSED_AT)
            .fetchOptional(STREAM_SESSION.LAST_ACCESSED_AT)
            .map(java.time.OffsetDateTime::toInstant);
    if (admittedAt.isEmpty()) {
      return Optional.empty();
    }

    var fallbackAt = admittedAt.orElseThrow().plus(provisioningTimeout);
    dsl.insertInto(
            STREAM_SESSION_TERMINATION_INTENT,
            STREAM_SESSION_TERMINATION_INTENT.STREAM_SESSION_ID,
            STREAM_SESSION_TERMINATION_INTENT.TERMINAL_AT,
            STREAM_SESSION_TERMINATION_INTENT.TERMINAL_REASON,
            STREAM_SESSION_TERMINATION_INTENT.REPLAY_AFTER,
            STREAM_SESSION_TERMINATION_INTENT.ARMED)
        .values(
            authority.streamSessionId(),
            fallbackAt.atOffset(ZoneOffset.UTC),
            com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason
                .PROVISIONING_TIMEOUT,
            fallbackAt.atOffset(ZoneOffset.UTC),
            false)
        .execute();
    return admittedAt;
  }

  @Override
  public boolean activate(StreamSessionAuthority authority, Duration provisioningTimeout) {
    var locked =
        dsl.select(AUTH_SESSION.ID)
            .from(AUTH_SESSION)
            .where(AUTH_SESSION.ID.eq(authority.authSessionId()))
            .and(AUTH_SESSION.ACCOUNT_ID.eq(authority.accountId()))
            .forUpdate()
            .fetchOptional()
            .isPresent();
    if (!locked) {
      return false;
    }

    var activated =
        dsl.update(STREAM_SESSION)
                .set(STREAM_SESSION.STATUS, StreamSessionStatus.ACTIVE)
                .where(rowAuthorityMatches(authority))
                .and(STREAM_SESSION.STATUS.eq(StreamSessionStatus.PROVISIONING))
                .andExists(liveAuthority(authority))
                .andExists(mediaSource(DSL.val(authority.mediaFileId())))
                .execute()
            == 1;
    if (!activated) {
      return false;
    }

    var fallbackAt = databaseTime().plus(provisioningTimeout).atOffset(ZoneOffset.UTC);
    var guardRenewed =
        dsl.update(STREAM_SESSION_TERMINATION_INTENT)
                .set(STREAM_SESSION_TERMINATION_INTENT.TERMINAL_AT, fallbackAt)
                .set(
                    STREAM_SESSION_TERMINATION_INTENT.TERMINAL_REASON,
                    com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason
                        .STARTUP_FAILURE)
                .set(STREAM_SESSION_TERMINATION_INTENT.REPLAY_AFTER, fallbackAt)
                .set(STREAM_SESSION_TERMINATION_INTENT.ARMED, false)
                .where(
                    STREAM_SESSION_TERMINATION_INTENT.STREAM_SESSION_ID.eq(
                        authority.streamSessionId()))
                .execute()
            == 1;
    if (!guardRenewed) {
      throw new IllegalStateException("Stream session compensation guard is missing");
    }
    return true;
  }

  @Override
  public Optional<Instant> touchIfPlaybackRequestMatches(PlaybackRequestAuthority authority) {
    var statementTimestamp =
        DSL.function(DSL.name("statement_timestamp"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    return dsl.update(STREAM_SESSION)
        .set(
            STREAM_SESSION.LAST_ACCESSED_AT,
            DSL.greatest(STREAM_SESSION.LAST_ACCESSED_AT, statementTimestamp))
        .where(STREAM_SESSION.ID.eq(authority.streamSessionId()))
        .and(STREAM_SESSION.STATUS.eq(StreamSessionStatus.ACTIVE))
        .and(STREAM_SESSION.AUTH_SESSION_ID.eq(authority.authSessionId()))
        .and(STREAM_SESSION.ACCOUNT_ID.eq(authority.accountId()))
        .and(STREAM_SESSION.HOUSEHOLD_ID.eq(authority.householdId()))
        .and(STREAM_SESSION.PROFILE_ID.eq(authority.profileId()))
        .andExists(
            liveIdentityAuthority(
                authority.authSessionId(),
                authority.accountId(),
                authority.householdId(),
                authority.profileId()))
        .andExists(mediaSource(STREAM_SESSION.MEDIA_FILE_ID))
        .returning(STREAM_SESSION.LAST_ACCESSED_AT)
        .fetchOptional(STREAM_SESSION.LAST_ACCESSED_AT)
        .map(java.time.OffsetDateTime::toInstant);
  }

  @Override
  public List<java.util.UUID> findTerminatingIds(int limit) {
    return dsl.select(STREAM_SESSION.ID)
        .from(STREAM_SESSION)
        .where(STREAM_SESSION.STATUS.eq(StreamSessionStatus.TERMINATING))
        .orderBy(STREAM_SESSION.ID)
        .limit(limit)
        .fetch(STREAM_SESSION.ID);
  }

  @Override
  public List<java.util.UUID> findTerminatingIdsAfter(java.util.UUID afterId, int limit) {
    return dsl.select(STREAM_SESSION.ID)
        .from(STREAM_SESSION)
        .where(STREAM_SESSION.STATUS.eq(StreamSessionStatus.TERMINATING))
        .and(STREAM_SESSION.ID.gt(afterId))
        .orderBy(STREAM_SESSION.ID)
        .limit(limit)
        .fetch(STREAM_SESSION.ID);
  }

  @Override
  public List<java.util.UUID> terminalizeByMediaFiles(MediaStreamTermination termination) {
    if (termination.mediaFileIds().isEmpty()) {
      return List.of();
    }
    var terminalAt = termination.terminalAt().atOffset(ZoneOffset.UTC);
    var terminalReason =
        com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason.valueOf(
            termination.reason().name());
    return dsl.update(STREAM_SESSION)
        .set(STREAM_SESSION.STATUS, StreamSessionStatus.TERMINATING)
        .set(STREAM_SESSION.TERMINAL_AT, terminalAt)
        .set(STREAM_SESSION.TERMINAL_REASON, terminalReason)
        .where(STREAM_SESSION.MEDIA_FILE_ID.in(termination.mediaFileIds()))
        .and(STREAM_SESSION.STATUS.in(StreamSessionStatus.PROVISIONING, StreamSessionStatus.ACTIVE))
        .returning(STREAM_SESSION.ID)
        .fetch(STREAM_SESSION.ID);
  }

  @Override
  public List<java.util.UUID> terminalizeMissingMediaSources(Instant terminalAt) {
    return dsl.update(STREAM_SESSION)
        .set(STREAM_SESSION.STATUS, StreamSessionStatus.TERMINATING)
        .set(STREAM_SESSION.TERMINAL_AT, terminalAt.atOffset(ZoneOffset.UTC))
        .set(
            STREAM_SESSION.TERMINAL_REASON,
            com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason.SOURCE_DELETED)
        .where(
            STREAM_SESSION.STATUS.in(StreamSessionStatus.PROVISIONING, StreamSessionStatus.ACTIVE))
        .andNotExists(mediaSource(STREAM_SESSION.MEDIA_FILE_ID))
        .returning(STREAM_SESSION.ID)
        .fetch(STREAM_SESSION.ID);
  }

  @Override
  public boolean terminalize(StreamSessionTermination termination) {
    var terminalAt = termination.terminalAt().atOffset(ZoneOffset.UTC);
    var terminalReason =
        com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason.valueOf(
            termination.reason().name());

    return dsl.update(STREAM_SESSION)
            .set(STREAM_SESSION.STATUS, StreamSessionStatus.TERMINATING)
            .set(STREAM_SESSION.TERMINAL_AT, terminalAt)
            .set(STREAM_SESSION.TERMINAL_REASON, terminalReason)
            .where(STREAM_SESSION.ID.eq(termination.streamSessionId()))
            .and(
                STREAM_SESSION.STATUS.in(
                    StreamSessionStatus.PROVISIONING, StreamSessionStatus.ACTIVE))
            .execute()
        == 1;
  }

  @Override
  public boolean recordTerminationIntent(StreamSessionTermination termination) {
    var terminalReason =
        com.streamarr.server.jooq.generated.enums.StreamSessionTerminalReason.valueOf(
            termination.reason().name());
    return dsl.update(STREAM_SESSION_TERMINATION_INTENT)
            .set(
                STREAM_SESSION_TERMINATION_INTENT.TERMINAL_AT,
                termination.terminalAt().atOffset(ZoneOffset.UTC))
            .set(STREAM_SESSION_TERMINATION_INTENT.TERMINAL_REASON, terminalReason)
            .set(STREAM_SESSION_TERMINATION_INTENT.REPLAY_AFTER, statementTimestamp())
            .set(STREAM_SESSION_TERMINATION_INTENT.ARMED, true)
            .where(
                STREAM_SESSION_TERMINATION_INTENT.STREAM_SESSION_ID.eq(
                    termination.streamSessionId()))
            .and(STREAM_SESSION_TERMINATION_INTENT.ARMED.isFalse())
            .execute()
        == 1;
  }

  @Override
  public List<StreamSessionTermination> findTerminationIntents() {
    return dsl.select(
            STREAM_SESSION_TERMINATION_INTENT.STREAM_SESSION_ID,
            STREAM_SESSION_TERMINATION_INTENT.TERMINAL_AT,
            STREAM_SESSION_TERMINATION_INTENT.TERMINAL_REASON)
        .from(STREAM_SESSION_TERMINATION_INTENT)
        .where(
            STREAM_SESSION_TERMINATION_INTENT
                .ARMED
                .isTrue()
                .or(STREAM_SESSION_TERMINATION_INTENT.REPLAY_AFTER.le(statementTimestamp())))
        .orderBy(STREAM_SESSION_TERMINATION_INTENT.STREAM_SESSION_ID)
        .fetch(
            intentRecord ->
                StreamSessionTermination.builder()
                    .streamSessionId(intentRecord.value1())
                    .terminalAt(intentRecord.value2().toInstant())
                    .reason(
                        com.streamarr.server.domain.streaming.StreamSessionTerminalReason.valueOf(
                            intentRecord.value3().getLiteral()))
                    .build());
  }

  @Override
  public boolean completeCreation(java.util.UUID streamSessionId) {
    var status =
        dsl.select(STREAM_SESSION.STATUS)
            .from(STREAM_SESSION)
            .where(STREAM_SESSION.ID.eq(streamSessionId))
            .forUpdate()
            .fetchOptional(STREAM_SESSION.STATUS);
    if (status.filter(StreamSessionStatus.ACTIVE::equals).isEmpty()) {
      return false;
    }

    deleteTerminationIntent(streamSessionId);
    return true;
  }

  @Override
  public boolean replayTerminationIntent(java.util.UUID streamSessionId) {
    var status =
        dsl.select(STREAM_SESSION.STATUS)
            .from(STREAM_SESSION)
            .where(STREAM_SESSION.ID.eq(streamSessionId))
            .forUpdate()
            .fetchOptional(STREAM_SESSION.STATUS);
    if (status.isEmpty()) {
      return false;
    }
    if (status.filter(StreamSessionStatus.TERMINATING::equals).isPresent()) {
      deleteTerminationIntent(streamSessionId);
      return true;
    }

    var stored =
        dsl.select(
                STREAM_SESSION_TERMINATION_INTENT.TERMINAL_AT,
                STREAM_SESSION_TERMINATION_INTENT.TERMINAL_REASON)
            .from(STREAM_SESSION_TERMINATION_INTENT)
            .where(STREAM_SESSION_TERMINATION_INTENT.STREAM_SESSION_ID.eq(streamSessionId))
            .and(
                STREAM_SESSION_TERMINATION_INTENT
                    .ARMED
                    .isTrue()
                    .or(STREAM_SESSION_TERMINATION_INTENT.REPLAY_AFTER.le(statementTimestamp())))
            .forUpdate()
            .fetchOptional();
    if (stored.isEmpty()) {
      return false;
    }

    var intent = stored.orElseThrow();
    var termination =
        StreamSessionTermination.builder()
            .streamSessionId(streamSessionId)
            .terminalAt(intent.value1().toInstant())
            .reason(
                com.streamarr.server.domain.streaming.StreamSessionTerminalReason.valueOf(
                    intent.value2().getLiteral()))
            .build();
    if (!terminalize(termination)) {
      return false;
    }
    deleteTerminationIntent(streamSessionId);
    return true;
  }

  @Override
  public boolean deleteTerminationIntent(java.util.UUID streamSessionId) {
    return dsl.deleteFrom(STREAM_SESSION_TERMINATION_INTENT)
            .where(STREAM_SESSION_TERMINATION_INTENT.STREAM_SESSION_ID.eq(streamSessionId))
            .execute()
        == 1;
  }

  @Override
  public boolean deleteTerminating(java.util.UUID streamSessionId) {
    return dsl.deleteFrom(STREAM_SESSION)
            .where(STREAM_SESSION.ID.eq(streamSessionId))
            .and(STREAM_SESSION.STATUS.eq(StreamSessionStatus.TERMINATING))
            .execute()
        == 1;
  }

  private boolean lockMediaSource(StreamSessionAuthority authority) {
    return dsl.select(MEDIA_FILE.ID)
        .from(MEDIA_FILE)
        .where(MEDIA_FILE.ID.eq(authority.mediaFileId()))
        .andNotExists(mediaDeletionIntent(MEDIA_FILE.ID))
        .andNotExists(libraryDeletionIntent(MEDIA_FILE.LIBRARY_ID))
        .forKeyShare()
        .fetchOptional()
        .isPresent();
  }

  private Select<?> liveAuthority(StreamSessionAuthority authority) {
    return liveIdentityAuthority(
        authority.authSessionId(),
        authority.accountId(),
        authority.householdId(),
        authority.profileId());
  }

  private Select<?> mediaSource(org.jooq.Field<java.util.UUID> mediaFileId) {
    return dsl.selectOne()
        .from(MEDIA_FILE)
        .where(MEDIA_FILE.ID.eq(mediaFileId))
        .andNotExists(mediaDeletionIntent(MEDIA_FILE.ID))
        .andNotExists(libraryDeletionIntent(MEDIA_FILE.LIBRARY_ID));
  }

  private Select<?> mediaDeletionIntent(org.jooq.Field<java.util.UUID> mediaFileId) {
    return dsl.selectOne()
        .from(MEDIA_FILE_DELETION_INTENT)
        .where(MEDIA_FILE_DELETION_INTENT.MEDIA_FILE_ID.eq(mediaFileId));
  }

  private Select<?> libraryDeletionIntent(org.jooq.Field<java.util.UUID> libraryId) {
    return dsl.selectOne()
        .from(LIBRARY_DELETION_INTENT)
        .where(LIBRARY_DELETION_INTENT.LIBRARY_ID.eq(libraryId));
  }

  private org.jooq.Field<java.time.OffsetDateTime> statementTimestamp() {
    return DSL.function(DSL.name("statement_timestamp"), SQLDataType.TIMESTAMPWITHTIMEZONE);
  }

  private Instant databaseTime() {
    return dsl.select(statementTimestamp()).fetchSingle().value1().toInstant();
  }

  private Condition rowAuthorityMatches(StreamSessionAuthority authority) {
    return STREAM_SESSION
        .ID
        .eq(authority.streamSessionId())
        .and(STREAM_SESSION.AUTH_SESSION_ID.eq(authority.authSessionId()))
        .and(STREAM_SESSION.ACCOUNT_ID.eq(authority.accountId()))
        .and(STREAM_SESSION.HOUSEHOLD_ID.eq(authority.householdId()))
        .and(STREAM_SESSION.PROFILE_ID.eq(authority.profileId()))
        .and(STREAM_SESSION.MEDIA_FILE_ID.eq(authority.mediaFileId()));
  }

  private Select<?> liveIdentityAuthority(
      java.util.UUID authSessionId,
      java.util.UUID accountId,
      java.util.UUID householdId,
      java.util.UUID profileId) {
    return dsl.selectOne()
        .from(AUTH_SESSION)
        .join(USER_ACCOUNT)
        .on(USER_ACCOUNT.ID.eq(AUTH_SESSION.ACCOUNT_ID))
        .join(HOUSEHOLD_MEMBERSHIP)
        .on(HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID.eq(AUTH_SESSION.ACCOUNT_ID))
        .join(PROFILE)
        .on(PROFILE.HOUSEHOLD_ID.eq(HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID))
        .join(ACCOUNT_PROFILE)
        .on(ACCOUNT_PROFILE.ACCOUNT_ID.eq(AUTH_SESSION.ACCOUNT_ID))
        .and(ACCOUNT_PROFILE.PROFILE_ID.eq(PROFILE.ID))
        .where(sourceAuthorityMatches(authSessionId, accountId, householdId, profileId));
  }

  private Condition sourceAuthorityMatches(
      java.util.UUID authSessionId,
      java.util.UUID accountId,
      java.util.UUID householdId,
      java.util.UUID profileId) {
    return AUTH_SESSION
        .ID
        .eq(authSessionId)
        .and(AUTH_SESSION.ACCOUNT_ID.eq(accountId))
        .and(AUTH_SESSION.ACTIVE_HOUSEHOLD_ID.eq(householdId))
        .and(AUTH_SESSION.ACTIVE_PROFILE_ID.eq(profileId))
        .and(AUTH_SESSION.REVOKED_AT.isNull())
        .and(USER_ACCOUNT.ENABLED.isTrue())
        .and(HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID.eq(accountId))
        .and(HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID.eq(householdId))
        .and(PROFILE.ID.eq(profileId))
        .and(PROFILE.HOUSEHOLD_ID.eq(householdId))
        .and(ACCOUNT_PROFILE.ACCOUNT_ID.eq(accountId))
        .and(ACCOUNT_PROFILE.HOUSEHOLD_ID.eq(householdId))
        .and(ACCOUNT_PROFILE.PROFILE_ID.eq(profileId));
  }
}
