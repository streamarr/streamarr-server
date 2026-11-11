package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.CredentialAttemptMetadata;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.ShareCode;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.properties.CredentialCodeProperties;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ShareCodeRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.CredentialAttemptGate;
import com.streamarr.server.services.auth.ShareCodes;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Share codes (ADR 0024 §Share codes): a Household's live HouseholdAdmin, or a ServerAdmin, issues
 * share codes for the Household, lists them, and withdraws any of them, and any signed-in Account
 * may look a code up. Only the answer to issuing a code carries the code; the row keeps its digest.
 * Issuing invites an offer and consents to nothing.
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
  private final CredentialAttemptGate credentialAttempts;
  private final HouseholdShareCodesList householdShareCodesList;
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
          refusal(
                  identity,
                  new Intent.IssueShareCode(householdId),
                  householdId,
                  ShareCodeRejections.HouseholdNotFound::new)
              .ifPresent(ShareCodeService::reject);
          if (householdRepository.findById(householdId).isEmpty()) {
            reject(new ShareCodeRejections.HouseholdNotFound());
          }

          return store(identity, householdId, clock.instant());
        },
        constraint ->
            FK_SHARE_CODE_HOUSEHOLD.equals(constraint)
                ? Optional.of(new ShareCodeRejections.HouseholdNotFound())
                : Optional.empty());
  }

  /** Withdraws a live code of the Household, whoever issued it. */
  public Outcome<ShareCodeWithIssuer, ShareCodeRejections.Withdraw> withdrawShareCode(
      AuthenticatedIdentity identity, UUID shareCodeId) {
    return mutationTransactions.write(
        () -> {
          var shareCode =
              shareCodeRepository
                  .findById(shareCodeId)
                  .orElseThrow(
                      () -> new MutationRejection(new ShareCodeRejections.ShareCodeNotFound()));
          refusal(
                  identity,
                  new Intent.WithdrawShareCode(shareCode.getHouseholdId()),
                  shareCode.getHouseholdId(),
                  ShareCodeRejections.ShareCodeNotFound::new)
              .ifPresent(ShareCodeService::reject);
          if (!shareCodeRepository.tryWithdraw(shareCodeId, clock.instant())) {
            reject(new ShareCodeRejections.ShareCodeNotLive());
          }

          return withIssuer(
              shareCodeRepository.findByIdAndReloadFromDatabase(shareCodeId).orElseThrow());
        },
        _ -> Optional.empty());
  }

  /**
   * The name of the Household a live code names, and nothing else about it. Every other code gets
   * one refusal that names no Household. Each lookup is a credential attempt by the presenting
   * Account, so guessing is throttled (ADR 0024 §Share codes, ADR 0028).
   */
  public Outcome<String, ShareCodeRejections.LookUp> lookUpShareCode(
      AuthenticatedIdentity identity, ShareCodeCommand command) {
    return present(identity, command, this::householdNameOf)
        .<Outcome<String, ShareCodeRejections.LookUp>>map(Outcome::accepted)
        .orElseGet(() -> Outcome.rejected(new ShareCodeRejections.ShareCodeRefused()));
  }

  /**
   * The live code a request presents to name its Household, verified as a credential attempt by the
   * presenting Account; empty for a code that does not work. It leaves the code live.
   */
  Optional<ShareCode> presentedShareCode(AuthenticatedIdentity identity, ShareCodeCommand command) {
    return present(identity, command, Function.identity());
  }

  /**
   * What the presented code admits, verified as a credential attempt by the presenting Account; a
   * code that does not work admits nothing. A session that may not present share codes is refused
   * with FORBIDDEN before any attempt counts.
   */
  private <T> Optional<T> present(
      AuthenticatedIdentity identity, ShareCodeCommand command, Function<ShareCode, T> admitted) {
    authorizationService.requireAllowed(identity, new Intent.ViewShareCodeHousehold());
    try {
      return Optional.of(
          credentialAttempts.attempt(
              presentedBy(identity, command), () -> admitted.apply(liveShareCode(command.code()))));
    } catch (InvalidOneTimeCodeException _) {
      return Optional.empty();
    }
  }

  /**
   * The live code that the typed text names, for a verification run through the credential attempt
   * gate. A malformed, unknown, spent, withdrawn, expired or invalidated code throws the same
   * refusal.
   */
  private ShareCode liveShareCode(String typed) {
    var now = clock.instant();
    return shareCodes
        .digestOf(typed)
        .flatMap(shareCodeRepository::findByCodeDigest)
        .filter(shareCode -> shareCode.isLiveAt(now))
        .orElseThrow(InvalidOneTimeCodeException::new);
  }

  /** A presentation counts against the Account that presents the code, never against the code. */
  private static CredentialAttemptMetadata presentedBy(
      AuthenticatedIdentity identity, ShareCodeCommand command) {
    return CredentialAttemptMetadata.builder()
        .kind(CredentialKind.SHARE_CODE)
        .accountId(identity.accountId())
        .ipAddress(command.ipAddress())
        .build();
  }

  private String householdNameOf(ShareCode shareCode) {
    return householdRepository
        .findById(shareCode.getHouseholdId())
        .map(Household::getName)
        .orElseThrow(InvalidOneTimeCodeException::new);
  }

  /**
   * A page of the Household's share codes in the scope, newest first, never the codes themselves;
   * hidden reads as empty.
   */
  public ListPage<ShareCodeWithIssuer> householdShareCodes(
      AuthenticatedIdentity identity, HouseholdShareCodesScope household, PageRequest request) {
    if (!mayReadShareCodes(identity, household.householdId())) {
      return ListPage.empty();
    }

    var page = Pager.page(householdShareCodesList, household, request);
    var names =
        displayNames(page.rows().stream().map(row -> row.item().getIssuerAccountId()).toList());
    return page.map(
        shareCode -> new ShareCodeWithIssuer(shareCode, names.get(shareCode.getIssuerAccountId())));
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
        return new NewShareCode(
            withIssuer(shareCodeRepository.findById(stored.get()).orElseThrow()), issued.code());
      }
    }

    throw new IllegalStateException("Every drawn share code matched a stored one");
  }

  private ShareCodeWithIssuer withIssuer(ShareCode shareCode) {
    var issuerId = shareCode.getIssuerAccountId();
    var name =
        issuerId == null
            ? null
            : userAccountRepository
                .findById(issuerId)
                .map(UserAccount::getDisplayName)
                .orElse(null);
    return new ShareCodeWithIssuer(shareCode, name);
  }

  /** The display names of the given Accounts, read together; a deleted Account has none. */
  private Map<UUID, String> displayNames(Collection<UUID> accountIds) {
    var present = accountIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
    return userAccountRepository.findAllById(present).stream()
        .collect(Collectors.toMap(UserAccount::getId, UserAccount::getDisplayName));
  }

  /** A caller who may not see the Household's share codes learns nothing about the Household. */
  private <R> Optional<R> refusal(
      AuthenticatedIdentity identity,
      Intent.UnitIntent intent,
      UUID householdId,
      Supplier<? extends R> hidden) {
    return AuthorizationRefusal.from(
        authorizationService.decide(identity, intent),
        new AuthorizationRefusal.Response<>(
            () -> mayViewShareCodes(identity, householdId), hidden, Optional.empty()));
  }

  private boolean mayViewShareCodes(AuthenticatedIdentity identity, UUID householdId) {
    return authorizationService.decide(identity, new Intent.ViewShareCodes(householdId))
        instanceof Decision.Allowed<AuthorizationUnit>;
  }

  private boolean mayReadShareCodes(AuthenticatedIdentity identity, UUID householdId) {
    return switch (authorizationService.decide(identity, new Intent.ViewShareCodes(householdId))) {
      case Decision.Allowed<AuthorizationUnit> _ -> true;
      case Decision.Denied<AuthorizationUnit> _ -> false;
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
    };
  }

  private static void reject(Object rejection) {
    throw new MutationRejection(rejection);
  }
}
