package com.streamarr.server.services.auth;

import com.streamarr.server.config.CanonicalBaseUrl;
import com.streamarr.server.config.security.DeviceAuthProperties;
import com.streamarr.server.domain.auth.DeviceAuthorization;
import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.DeviceCodeExpiredException;
import com.streamarr.server.exceptions.DeviceCodeNotFoundException;
import com.streamarr.server.exceptions.DeviceCodeNotPendingException;
import com.streamarr.server.exceptions.DevicePairingNotConfiguredException;
import com.streamarr.server.exceptions.EsnRequiredException;
import com.streamarr.server.exceptions.TooManyDeviceAttemptsException;
import com.streamarr.server.repositories.auth.DeviceAuthorizationDecisionCommand;
import com.streamarr.server.repositories.auth.DeviceAuthorizationInsertCommand;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import com.streamarr.server.repositories.auth.DeviceCodeCollisionException;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.repositories.auth.EsnBlockRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.auth.UserCodeCollisionException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues, decides, and redeems device pairings (ADR 0021). */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceAuthorizationService {

  private static final int DEVICE_CODE_BYTES = 32;
  private static final int DEVICE_CODE_CHARACTERS = 43;
  private static final int DEVICE_CODE_ATTEMPTS = 5;
  private static final int SLOW_DOWN_INCREMENT_SECONDS = 5;
  private static final int USER_CODE_ATTEMPTS = 5;

  private final DeviceAuthorizationRepository authorizationRepository;
  private final UserAccountRepository userAccountRepository;
  private final DeviceRegistrationRepository deviceRegistrationRepository;
  private final EsnBlockRepository esnBlockRepository;
  private final DeviceRegistrationLifecycle registrationLifecycle;
  private final RefreshTokenService refreshTokenService;
  private final AccessTokenIssuer accessTokenIssuer;
  private final UserCodeGenerator userCodeGenerator;
  private final DeviceCodeGenerator deviceCodeGenerator;
  private final DeviceGuessThrottle guessThrottle;
  private final DeviceAuthProperties properties;
  private final CanonicalBaseUrl baseUrl;
  private final Clock clock;

  public boolean isPairingEnabled() {
    return baseUrl.isConfigured();
  }

  /**
   * Deliberately not transactional: each attempt below opens its own transaction, so a code
   * collision retries cleanly instead of retrying inside one already marked rollback-only.
   */
  public IssuedDeviceCode issue(String rawDeviceName, String esn) {
    if (!isPairingEnabled()) {
      throw new DevicePairingNotConfiguredException();
    }

    if (esn == null || esn.isBlank()) {
      // The registration the winning poll creates is keyed by hardware identity (ADR 0024).
      throw new EsnRequiredException();
    }

    var now = clock.instant();
    var interval = properties.pollIntervalSeconds();
    DeviceCodeCollisionException lastCollision = null;
    for (var attempt = 0; attempt < DEVICE_CODE_ATTEMPTS; attempt++) {
      var deviceCode = deviceCodeGenerator.generate();
      try {
        var userCode =
            saveWithUniqueUserCode(deviceCode, rawDeviceName, esn.strip(), interval, now);
        return IssuedDeviceCode.builder()
            .deviceCode(deviceCode)
            .userCode(UserCode.forDisplay(userCode))
            .verificationUri(baseUrl.resolve(properties.verificationPath()))
            .interval(interval)
            .expiresIn(properties.codeTtl().toSeconds())
            .build();
      } catch (DeviceCodeCollisionException e) {
        lastCollision = e;
        log.warn("Device code collided with an outstanding pairing code; retrying.");
      }
    }

    throw new IllegalStateException(
        "Could not mint a unique device code in %d attempts.".formatted(DEVICE_CODE_ATTEMPTS),
        lastCollision);
  }

  /**
   * One row-locked transaction. Every poller and the approving writer serialize on the same row, so
   * classification sees state nothing can change mid-decision and exactly one caller creates the
   * session. Any failure afterwards rolls back and leaves the row APPROVED, so the device simply
   * polls again.
   */
  @Transactional
  public DevicePollResult redeem(String deviceCode) {
    if (!isCanonicalDeviceCode(deviceCode)) {
      // Indistinguishable from an unknown code, and cheaper: no hash, no query.
      return new DevicePollResult.Expired();
    }

    var locked = authorizationRepository.lockByDeviceCodeDigest(digestOf(deviceCode));
    if (locked.isEmpty()) {
      return new DevicePollResult.Expired();
    }

    var authorization = locked.get();
    var now = clock.instant();
    if (authorization.hasExpiredAt(now)) {
      return new DevicePollResult.Expired();
    }

    return switch (authorization.getStatus()) {
      case APPROVED -> consume(authorization, now);
      case DENIED -> new DevicePollResult.Denied();
      case CONSUMED -> new DevicePollResult.Expired();
      case PENDING -> advanceCadence(authorization, now);
    };
  }

  @Transactional(readOnly = true)
  public DeviceAuthorizationDetails lookup(String typedUserCode, UUID callerAccountId) {
    guessThrottle.registerAttempt(callerAccountId);

    var authorization = findUnexpired(UserCode.normalize(typedUserCode));

    return detailsOf(authorization, authorization.getStatus());
  }

  /**
   * Resolves a typed code to its pairing grant for the approval ceremony: the guessing budget is
   * spent here, once per presented code, before Cedar or any validation sees the request.
   */
  @Transactional(readOnly = true)
  public ResolvedGrant resolveForDecision(String typedUserCode, UUID callerAccountId) {
    guessThrottle.registerAttempt(callerAccountId);
    var authorization =
        authorizationRepository
            .findByUserCode(UserCode.normalize(typedUserCode))
            .orElseThrow(DeviceCodeNotFoundException::new);
    // The approver is mid-flow on a code they demonstrably saw, so expiry earns its own answer
    // here; a bare not-found would read as a typo. Lookup collapses it to 404 on purpose.
    if (authorization.hasExpiredAt(clock.instant())) {
      throw new DeviceCodeExpiredException();
    }

    return new ResolvedGrant(
        authorization.getId(), authorization.getEsn(), authorization.getDeviceName());
  }

  /**
   * The conditional decision write. Deliberately not throttled: {@link #resolveForDecision} already
   * spent the budget for this presentation, and the ceremony calls both in one request.
   */
  @Transactional
  public DeviceAuthorizationDetails decide(DeviceDecisionCommand command) {
    var userCode = UserCode.normalize(command.userCode());
    var authorization =
        authorizationRepository
            .findByUserCode(userCode)
            .orElseThrow(DeviceCodeNotFoundException::new);

    // The approver is mid-flow on a code they demonstrably saw, so expiry earns its own answer
    // here; a bare not-found would read as a typo. Lookup collapses it to 404 on purpose.
    var now = clock.instant();
    if (authorization.hasExpiredAt(now)) {
      throw new DeviceCodeExpiredException();
    }

    if (authorization.getStatus() != DeviceAuthorizationStatus.PENDING) {
      throw new DeviceCodeNotPendingException();
    }

    // Everything the response needs is read before the write: re-reading a row this transaction
    // mutated through jOOQ would hand back Hibernate's stale managed copy.
    var decidedStatus = command.decision().resultingStatus();
    var details = detailsOf(authorization, decidedStatus);

    var decided =
        authorizationRepository.decide(
            DeviceAuthorizationDecisionCommand.builder()
                .userCode(userCode)
                .status(decidedStatus)
                .decidedByAccountId(command.decidedByAccountId())
                .chosenHouseholdId(command.chosenHouseholdId())
                .now(now)
                .build());

    if (decided == 0) {
      throw classifyLostDecision(userCode);
    }

    return details;
  }

  private DevicePollResult consume(DeviceAuthorization authorization, Instant now) {
    var approver = findEnabledApprover(authorization);
    if (approver.isEmpty()) {
      // The approving account is gone or disabled, so its approval no longer authorizes anything.
      return new DevicePollResult.Expired();
    }

    // The session is born here, at the winning poll — never at approval, which would mean storing
    // a raw refresh token to wait for pickup. It is born in the approver's membership Household
    // with no Profile selected (the TV picks one through select-profile); everything the session
    // must carry is in its insert, because a jOOQ update of a row Hibernate has only queued would
    // run before the row exists.
    var account = approver.get();
    var household =
        authorization.getChosenHouseholdId() == null
            ? account.getHouseholdId()
            : authorization.getChosenHouseholdId();
    if (!userAccountRepository.mayUseHousehold(account.getId(), household)) {
      // Approval facts went stale; the poll answers exactly like an expired code.
      return new DevicePollResult.Expired();
    }

    if (authorization.getEsn() == null || isEsnBlocked(authorization.getEsn(), household)) {
      // No hardware identity, no registration, no session: an ESN-less grant (pre-V059 rows)
      // would mint an unbound "device" session that dodges the device forbid.
      return new DevicePollResult.Expired();
    }

    var registrationId = registerDevice(authorization, account, household, now);
    if (registrationId.isEmpty()) {
      return new DevicePollResult.Expired();
    }

    var issued =
        refreshTokenService.createSession(
            CreateAuthSessionCommand.builder()
                .accountId(account.getId())
                .deviceName(authorization.getDeviceName())
                .contextHouseholdId(household)
                .registrationId(registrationId.get())
                .build());
    var accessToken = accessTokenIssuer.issue(TokenContext.of(account, issued.session()));

    authorizationRepository.markConsumed(authorization.getId(), now);

    return new DevicePollResult.Success(accessToken, issued.rawToken());
  }

  /**
   * One TV, one live Household context: a re-paired ESN supersedes its previous registration — and
   * that registration's sessions — before the new one is written, so the partial unique index never
   * trips and the old binding cannot outlive the new consent.
   */
  private Optional<UUID> registerDevice(
      DeviceAuthorization authorization, UserAccount account, UUID household, Instant now) {
    registrationLifecycle.revokeAllByEsn(
        authorization.getEsn(), null, account.getId(), "superseded by a new pairing", now);
    if (isEsnBlocked(authorization.getEsn(), household)) {
      return Optional.empty();
    }

    return Optional.of(
        deviceRegistrationRepository
            .saveAndFlush(
                DeviceRegistration.builder()
                    .esn(authorization.getEsn())
                    .displayName(authorization.getDeviceName())
                    .householdId(household)
                    .authorizingAccountId(account.getId())
                    .authorizationId(authorization.getId())
                    .build())
            .getId());
  }

  private boolean isEsnBlocked(String esn, UUID householdId) {
    return esnBlockRepository.existsByEsnAndHouseholdIdIsNull(esn)
        || esnBlockRepository.existsByEsnAndHouseholdId(esn, householdId);
  }

  private Optional<UserAccount> findEnabledApprover(DeviceAuthorization authorization) {
    if (authorization.getDecidedByAccountId() == null) {
      return Optional.empty();
    }

    return userAccountRepository
        .findById(authorization.getDecidedByAccountId())
        .filter(UserAccount::isEnabled);
  }

  private DevicePollResult advanceCadence(DeviceAuthorization authorization, Instant now) {
    if (authorization.isPollDueAt(now)) {
      reschedulePoll(authorization.getId(), authorization.getPollIntervalSeconds(), now);
      return new DevicePollResult.Pending();
    }

    // RFC 8628 §3.5: each early poll costs five more seconds, cumulatively. Checked addition —
    // the growth is caller-driven, so it must never wrap into a negative interval that would make
    // every subsequent poll due immediately.
    reschedulePoll(
        authorization.getId(),
        Math.addExact(authorization.getPollIntervalSeconds(), SLOW_DOWN_INCREMENT_SECONDS),
        now);
    return new DevicePollResult.SlowDown();
  }

  private void reschedulePoll(UUID id, int intervalSeconds, Instant now) {
    authorizationRepository.updateCadence(
        id, intervalSeconds, now.plusSeconds(intervalSeconds), now);
  }

  private DeviceAuthorization findUnexpired(String userCode) {
    var authorization =
        authorizationRepository
            .findByUserCode(userCode)
            .orElseThrow(DeviceCodeNotFoundException::new);

    // A probe deserves no oracle: expired collapses into not-found, matching the poll's
    // expired_token.
    if (authorization.hasExpiredAt(clock.instant())) {
      throw new DeviceCodeNotFoundException();
    }

    return authorization;
  }

  private static DeviceAuthorizationDetails detailsOf(
      DeviceAuthorization authorization, DeviceAuthorizationStatus status) {
    return DeviceAuthorizationDetails.builder()
        .userCode(UserCode.forDisplay(authorization.getUserCode()))
        .deviceName(authorization.getDeviceName())
        .status(status)
        .requestedAt(authorization.getCreatedOn())
        .build();
  }

  /** The row moved between the read and the conditional write; a scalar read says which way. */
  private RuntimeException classifyLostDecision(String userCode) {
    return authorizationRepository
        .findStatusByUserCode(userCode)
        .<RuntimeException>map(DeviceAuthorizationService::lostDecisionForStatus)
        .orElseGet(DeviceCodeNotFoundException::new);
  }

  private static RuntimeException lostDecisionForStatus(DeviceAuthorizationStatus status) {
    return switch (status) {
      case PENDING ->
          new IllegalStateException(
              "Conditional device decision updated no rows, but the code is still pending.");
      case APPROVED, DENIED, CONSUMED -> new DeviceCodeNotPendingException();
    };
  }

  /**
   * The cap is enforced inside the insert, not before it: counting here and inserting there is the
   * check-then-act race that lets every concurrent caller past a cap none of them has filled yet.
   */
  private String saveWithUniqueUserCode(
      String deviceCode, String rawDeviceName, String esn, int interval, Instant now) {
    UserCodeCollisionException lastCollision = null;
    for (var attempt = 0; attempt < USER_CODE_ATTEMPTS; attempt++) {
      var candidate = userCodeGenerator.generate();
      try {
        var result =
            authorizationRepository.tryInsertWithinCap(
                DeviceAuthorizationInsertCommand.builder()
                    .deviceCodeDigest(digestOf(deviceCode))
                    .userCode(candidate)
                    .deviceName(DeviceName.sanitize(rawDeviceName))
                    .esn(esn)
                    .expiresAt(now.plus(properties.codeTtl()))
                    // RFC 8628 §3.2: the interval is the wait between polls. Nothing precedes the
                    // first one, so the gate opens at issuance and governs from the second poll on.
                    .nextPollAt(now)
                    .pollIntervalSeconds(interval)
                    .maxOutstanding(properties.maxOutstandingCodes())
                    .now(now)
                    .build());
        if (!result.inserted()) {
          throw refusedForCapacity(result.outstanding(), now);
        }

        warnAsCapacityNears(result.outstanding());
        return candidate;
      } catch (UserCodeCollisionException e) {
        // A collision with an outstanding code. In a 20^8 space against a capped number of live
        // codes this is vanishingly rare; retrying is cheaper than reasoning about it.
        lastCollision = e;
        log.warn("User code collided with an outstanding pairing code; retrying.");
      }
    }

    throw new IllegalStateException(
        "Could not mint a unique pairing code in %d attempts.".formatted(USER_CODE_ATTEMPTS),
        lastCollision);
  }

  private TooManyDeviceAttemptsException refusedForCapacity(int outstanding, Instant now) {
    log.warn(
        "Device pairing issuance refused: {} outstanding codes at the configured cap of {}",
        outstanding,
        properties.maxOutstandingCodes());
    return new TooManyDeviceAttemptsException(waitUntilCapacityFrees(now));
  }

  /** Approaching the cap is the attack signal operators can alert on. */
  private void warnAsCapacityNears(int outstanding) {
    var warningThreshold = (properties.maxOutstandingCodes() + 1) / 2;
    if (outstanding != warningThreshold && outstanding != properties.maxOutstandingCodes()) {
      return;
    }

    log.warn(
        "Device pairing issuance at {} of {} outstanding codes",
        outstanding,
        properties.maxOutstandingCodes());
  }

  /**
   * A row-count cap has no window to measure, so the honest answer is when the oldest outstanding
   * code expires — the moment capacity provably frees.
   */
  private Duration waitUntilCapacityFrees(Instant now) {
    return authorizationRepository
        .findOldestOutstandingExpiry(now)
        .map(expiry -> Duration.between(now, expiry))
        .filter(wait -> wait.compareTo(Duration.ofSeconds(1)) > 0)
        .orElse(Duration.ofSeconds(1));
  }

  /** Validated before hashing, so a malformed code costs nothing and reveals nothing. */
  private static boolean isCanonicalDeviceCode(String deviceCode) {
    if (deviceCode == null || deviceCode.length() != DEVICE_CODE_CHARACTERS) {
      return false;
    }

    try {
      var decoded = Base64.getUrlDecoder().decode(deviceCode);
      return decoded.length == DEVICE_CODE_BYTES
          && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(deviceCode);
    } catch (IllegalArgumentException _) {
      return false;
    }
  }

  private static String digestOf(String rawValue) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(digest.digest(rawValue.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required but unavailable.", e);
    }
  }

  /** The grant the ceremony authorizes; never the code, never poll credentials. */
  public record ResolvedGrant(UUID grantId, String esn, String deviceName) {}
}
