package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ShareCode;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.properties.CredentialCodeProperties;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ShareCodeRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.ShareCodes;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Share codes (ADR 0024 §Share codes): a Household's live HouseholdAdmin, or a ServerAdmin, issues
 * a share code for the Household. Only the answer to issuing a code carries the code; the row keeps
 * its digest. Issuing invites an offer and consents to nothing.
 */
@Service
@RequiredArgsConstructor
public class ShareCodeService {

  static final String FK_SHARE_CODE_HOUSEHOLD = "fk_share_code_household";

  // Eight letters of twenty make 25.6 billion codes, so a draw that matches a stored digest is
  // rare; a few draws keep one such match from failing the request.
  private static final int DRAWS = 3;

  private final AuthorizationService authorizationService;
  private final ShareCodeRepository shareCodeRepository;
  private final HouseholdRepository householdRepository;
  private final UserAccountRepository userAccountRepository;
  private final MutationTransactions mutationTransactions;
  private final ShareCodes shareCodes;
  private final CredentialCodeProperties credentialCodeProperties;
  private final Clock clock;

  public Outcome<NewShareCode, ShareCodeRejections.Issue> issueShareCode(
      AuthenticatedIdentity identity, UUID householdId) {
    return mutationTransactions.write(
        () -> {
          // The issuer's row is locked before the decision reads its role, so an issuer who loses
          // the authority has either lost it before this decision, which then refuses, or loses it
          // after this code commits, and the loss then finds the code and invalidates it.
          userAccountRepository.lockAuthorityFacts(identity.accountId());
          refusalToIssue(identity, householdId)
              .ifPresent(
                  rejection -> {
                    throw new MutationRejection(rejection);
                  });
          if (householdRepository.findById(householdId).isEmpty()) {
            throw new MutationRejection(new ShareCodeRejections.HouseholdNotFound());
          }

          return store(identity, householdId, clock.instant());
        },
        constraint ->
            FK_SHARE_CODE_HOUSEHOLD.equals(constraint)
                ? Optional.of(new ShareCodeRejections.HouseholdNotFound())
                : Optional.empty());
  }

  private NewShareCode store(AuthenticatedIdentity identity, UUID householdId, Instant now) {
    for (var draw = 0; draw < DRAWS; draw++) {
      var issued = shareCodes.issue();
      var stored =
          shareCodeRepository.tryInsert(
              ShareCode.builder()
                  .householdId(householdId)
                  .issuerAccountId(identity.accountId())
                  .codeDigest(issued.digest())
                  .expiresAt(now.plus(credentialCodeProperties.shareCodeTtl()))
                  .build());
      if (stored.isPresent()) {
        var shareCode = shareCodeRepository.findById(stored.get()).orElseThrow();
        return new NewShareCode(
            new ShareCodeWithIssuer(shareCode, displayNameOf(identity.accountId())), issued.code());
      }
    }

    throw new IllegalStateException("Every drawn share code matched a stored one");
  }

  private String displayNameOf(UUID accountId) {
    return userAccountRepository.findById(accountId).map(UserAccount::getDisplayName).orElse(null);
  }

  /** A caller who may not see the Household's share codes learns nothing about the Household. */
  private Optional<ShareCodeRejections.Issue> refusalToIssue(
      AuthenticatedIdentity identity, UUID householdId) {
    return AuthorizationRefusal.from(
        authorizationService.decide(identity, new Intent.IssueShareCode(householdId)),
        new AuthorizationRefusal.Response<>(
            () -> mayViewShareCodes(identity, householdId),
            ShareCodeRejections.HouseholdNotFound::new,
            Optional.empty()));
  }

  private boolean mayViewShareCodes(AuthenticatedIdentity identity, UUID householdId) {
    return authorizationService.decide(identity, new Intent.ViewShareCodes(householdId))
        instanceof Decision.Allowed<AuthorizationUnit>;
  }
}
