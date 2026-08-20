package com.streamarr.server.services.auth;

import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Records EXPIRED on stale PENDING codes for reporting. Expiry itself is a predicate at lookup and
 * consumption — a code past its expiry is unredeemable whether or not this has run yet.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CredentialCodeSweeper {

  private final AccountInvitationRepository invitationRepository;
  private final PasswordResetCodeRepository resetCodeRepository;
  private final ProfileManagerInvitationRepository managerInvitationRepository;
  private final Clock clock;

  @Scheduled(fixedDelayString = "${auth.credential-codes.sweep-interval-ms:900000}")
  public void sweep() {
    var now = clock.instant();
    var expired =
        invitationRepository.sweepExpired(now)
            + resetCodeRepository.sweepExpired(now)
            + managerInvitationRepository.sweepExpired(now);
    if (expired > 0) {
      log.debug("Marked {} stale one-time codes as expired.", expired);
    }
  }
}
