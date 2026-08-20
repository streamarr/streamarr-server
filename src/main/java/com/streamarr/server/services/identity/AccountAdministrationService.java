package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.DeviceRegistrationLifecycle;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Coordinates authorized Account mutations and audits successful authority transitions. */
@Service
@RequiredArgsConstructor
public class AccountAdministrationService {

  private static final String CHK_RESTRICTED = "chk_restricted_account_holds_no_authority";
  private static final String CHK_ENABLED_SERVER_ADMIN = "chk_enabled_server_admin_remains";
  private static final String CHK_HOUSEHOLD_ADMIN = "chk_household_retains_admin";

  private final AuthorizationService authorizationService;
  private final UserAccountRepository userAccountRepository;
  private final AuthSessionRepository authSessionRepository;
  private final DeviceRegistrationLifecycle registrationLifecycle;
  private final SecurityAuditEventRepository securityAuditEventRepository;
  private final AccountInvitationRepository accountInvitationRepository;
  private final PasswordResetCodeRepository passwordResetCodeRepository;
  private final ProfileHouseholdShareRepository profileHouseholdShareRepository;
  private final MutationTransactions mutationTransactions;
  private final Clock clock;

  public Outcome<UserAccount, AdministrationRejections.GrantServerAdmin> grantServerAdmin(
      AuthenticatedIdentity identity, UUID accountId, String reason) {
    if (isBlank(reason)) {
      return Outcome.rejected(new AdministrationRejections.ReasonRequired());
    }

    return transition(
        identity,
        new Intent.GrantServerAdmin(accountId),
        accountId,
        TransitionPlan.<AdministrationRejections.GrantServerAdmin>builder()
            .operation("grantServerAdmin")
            .reason(reason)
            .transition(() -> userAccountRepository.tryGrantServerAdmin(accountId))
            .notFound(AdministrationRejections.AccountNotFound::new)
            .reauthenticationRequired(AdministrationRejections.ReauthenticationRequired::new)
            .constraint(CHK_RESTRICTED, AdministrationRejections.RestrictedAccount::new)
            .build());
  }

  public Outcome<UserAccount, AdministrationRejections.RevokeServerAdmin> revokeServerAdmin(
      AuthenticatedIdentity identity, UUID accountId, String reason) {
    if (isBlank(reason)) {
      return Outcome.rejected(new AdministrationRejections.ReasonRequired());
    }

    return transition(
        identity,
        new Intent.RevokeServerAdmin(accountId),
        accountId,
        TransitionPlan.<AdministrationRejections.RevokeServerAdmin>builder()
            .operation("revokeServerAdmin")
            .reason(reason)
            .transition(() -> userAccountRepository.tryRevokeServerAdmin(accountId))
            .afterTransition(
                () ->
                    invalidateIssuedCredentials(
                        accountId, IssuerAuthorityLoss.SERVER_ADMIN_REVOKED))
            .notFound(AdministrationRejections.AccountNotFound::new)
            .reauthenticationRequired(AdministrationRejections.ReauthenticationRequired::new)
            .constraint(CHK_ENABLED_SERVER_ADMIN, AdministrationRejections.LastServerAdmin::new)
            .build());
  }

  public Outcome<UserAccount, AdministrationRejections.GrantHouseholdAdmin> grantHouseholdAdmin(
      AuthenticatedIdentity identity, UUID accountId) {
    return transition(
        identity,
        new Intent.GrantHouseholdAdmin(accountId),
        accountId,
        TransitionPlan.<AdministrationRejections.GrantHouseholdAdmin>builder()
            .operation("grantHouseholdAdmin")
            .transition(() -> userAccountRepository.tryPromoteToHouseholdAdmin(accountId))
            .notFound(AdministrationRejections.AccountNotFound::new)
            .constraint(CHK_RESTRICTED, AdministrationRejections.RestrictedAccount::new)
            .build());
  }

  public Outcome<UserAccount, AdministrationRejections.RevokeHouseholdAdmin> revokeHouseholdAdmin(
      AuthenticatedIdentity identity, UUID accountId) {
    return transition(
        identity,
        new Intent.RevokeHouseholdAdmin(accountId),
        accountId,
        TransitionPlan.<AdministrationRejections.RevokeHouseholdAdmin>builder()
            .operation("revokeHouseholdAdmin")
            .transition(() -> userAccountRepository.tryDemoteToHouseholdMember(accountId))
            .notFound(AdministrationRejections.AccountNotFound::new)
            .constraint(CHK_HOUSEHOLD_ADMIN, AdministrationRejections.LastHouseholdAdmin::new)
            .build());
  }

  public Outcome<UserAccount, AdministrationRejections.DisableAccount> disableAccount(
      AuthenticatedIdentity identity, UUID accountId) {
    return transition(
        identity,
        new Intent.DisableAccount(accountId),
        accountId,
        TransitionPlan.<AdministrationRejections.DisableAccount>builder()
            .operation("disableAccount")
            .transition(() -> userAccountRepository.tryDisable(accountId))
            .afterTransition(
                () -> {
                  authSessionRepository.revokeAllForAccount(
                      accountId, SessionRevocationReason.ADMIN_REVOCATION, clock.instant());
                  // T9: a disabled Account supports no registration; revoke before commit.
                  registrationLifecycle.revokeAllByAccount(
                      accountId, "authorizing Account disabled", clock.instant());
                  invalidateIssuedCredentials(accountId, IssuerAuthorityLoss.ACCOUNT_DISABLED);
                })
            .notFound(AdministrationRejections.AccountNotFound::new)
            .constraint(CHK_ENABLED_SERVER_ADMIN, AdministrationRejections.LastServerAdmin::new)
            .build());
  }

  public Outcome<UserAccount, AdministrationRejections.EnableAccount> enableAccount(
      AuthenticatedIdentity identity, UUID accountId) {
    return transition(
        identity,
        new Intent.EnableAccount(accountId),
        accountId,
        TransitionPlan.<AdministrationRejections.EnableAccount>builder()
            .operation("enableAccount")
            .transition(() -> userAccountRepository.tryEnable(accountId))
            .notFound(AdministrationRejections.AccountNotFound::new)
            .build());
  }

  public Outcome<UserAccount, AdministrationRejections.RenameAccount> renameAccount(
      AuthenticatedIdentity identity, UUID accountId, String displayName) {
    if (isBlank(displayName)) {
      return Outcome.rejected(new AdministrationRejections.DisplayNameRequired());
    }

    return transition(
        identity,
        new Intent.RenameAccount(accountId),
        accountId,
        TransitionPlan.<AdministrationRejections.RenameAccount>builder()
            .operation("renameAccount")
            .audited(false)
            .transition(() -> userAccountRepository.tryRename(accountId, displayName.strip()))
            .notFound(AdministrationRejections.AccountNotFound::new)
            .build());
  }

  private <R> Outcome<UserAccount, R> transition(
      AuthenticatedIdentity identity,
      Intent.UnitIntent intent,
      UUID accountId,
      TransitionPlan<R> plan) {
    var transactional =
        mutationTransactions.write(
            () -> transitionInsideTransaction(identity, intent, accountId, plan),
            plan::rejectionForConstraint);
    return transactional.fold(outcome -> outcome, Outcome::rejected);
  }

  private <R> Outcome<UserAccount, R> transitionInsideTransaction(
      AuthenticatedIdentity identity,
      Intent.UnitIntent intent,
      UUID accountId,
      TransitionPlan<R> plan) {
    var refusal = refusalOf(identity, intent, accountId, plan);
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    var target = userAccountRepository.findById(accountId);
    if (target.isEmpty()) {
      return Outcome.rejected(plan.notFound().get());
    }

    var targetAccount = target.orElseThrow();
    if (plan.transition().getAsBoolean()) {
      applySuccessfulTransition(identity, plan, targetAccount);
    }

    return Outcome.accepted(targetAccount);
  }

  private <R> void applySuccessfulTransition(
      AuthenticatedIdentity identity, TransitionPlan<R> plan, UserAccount target) {
    plan.runAfterTransition();
    if (plan.isAudited()) {
      securityAuditEventRepository.append(auditEntry(identity, plan, target));
    }

    userAccountRepository.refresh(target);
  }

  /** Converts policy denial to forbidden only when the Account is visible; otherwise not-found. */
  private <R> Optional<R> refusalOf(
      AuthenticatedIdentity identity,
      Intent.UnitIntent intent,
      UUID accountId,
      TransitionPlan<R> plan) {
    return switch (authorizationService.decide(identity, intent)) {
      case Decision.Allowed<AuthorizationUnit> _ -> Optional.empty();
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
      case Decision.Denied<AuthorizationUnit>(var reason) ->
          switch (reason) {
            case REAUTHENTICATION_REQUIRED -> Optional.of(plan.reauthenticationRequired().get());
            case POLICY -> {
              if (mayViewAccount(identity, accountId)) {
                throw new AccessDeniedException("Not allowed.");
              }

              yield Optional.of(plan.notFound().get());
            }
          };
    };
  }

  /**
   * A disabled issuer leaves no unexpired codes or pending share offers behind. A ServerAdmin
   * demotion invalidates codes and offers for which the issuer has no remaining authority (ADR 0024
   * §Invitations: the system is the acting party). HouseholdAdmin demotion touches neither — that
   * role issues no codes and offers no Profiles. A deleted issuer is handled by the V058 triggers
   * in the same statement as the SET NULL.
   */
  private int invalidateIssuedCredentials(UUID issuerAccountId, IssuerAuthorityLoss authorityLoss) {
    var now = clock.instant();
    var reason = authorityLoss.reason();
    var invalidated =
        accountInvitationRepository.invalidatePendingInvitationsIssuedBy(
            issuerAccountId, reason, now);
    invalidated +=
        passwordResetCodeRepository.invalidatePendingPasswordResetCodesIssuedBy(
            issuerAccountId, reason, now);
    return invalidated
        + switch (authorityLoss) {
          case ACCOUNT_DISABLED ->
              profileHouseholdShareRepository.invalidatePendingOfferedBy(
                  issuerAccountId, reason, now);
          case SERVER_ADMIN_REVOKED ->
              invalidateUnauthorizedProfileShareOffers(issuerAccountId, reason, now);
        };
  }

  private int invalidateUnauthorizedProfileShareOffers(
      UUID issuerAccountId, String reason, Instant now) {
    var invalidated = 0;
    var pendingOffers =
        profileHouseholdShareRepository.findByOfferedByAccountIdAndStatus(
            issuerAccountId, ProfileShareStatus.PENDING);
    for (var offer : pendingOffers) {
      if (offer.statusAt(now) != ProfileShareStatus.PENDING
          || mayStillOfferProfile(issuerAccountId, offer.getProfileId())) {
        continue;
      }

      if (profileHouseholdShareRepository.tryInvalidatePending(offer.getId(), reason, now)) {
        invalidated++;
      }
    }

    return invalidated;
  }

  private boolean mayStillOfferProfile(UUID accountId, UUID profileId) {
    return switch (authorizationService.decideForAccount(
        accountId, new Intent.OfferProfileShare(profileId))) {
      case Decision.Allowed<AuthorizationUnit> _ -> true;
      case Decision.Denied<AuthorizationUnit> _ -> false;
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
    };
  }

  private boolean mayViewAccount(AuthenticatedIdentity identity, UUID accountId) {
    return authorizationService.isAllowed(
        identity, new Intent.ViewAccountAdministration(accountId));
  }

  private SecurityAuditEntry auditEntry(
      AuthenticatedIdentity identity, TransitionPlan<?> plan, UserAccount target) {
    return SecurityAuditEntry.builder()
        .operation(plan.operation())
        .actorAccountId(identity.accountId())
        .reason(plan.reason())
        .resource("accountId", target.getId())
        .resource("householdId", target.getHouseholdId())
        .build();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private enum IssuerAuthorityLoss {
    SERVER_ADMIN_REVOKED("issuer lost ServerAdmin"),
    ACCOUNT_DISABLED("issuer disabled");

    private final String reason;

    IssuerAuthorityLoss(String reason) {
      this.reason = reason;
    }

    String reason() {
      return reason;
    }
  }

  @lombok.Builder
  private record TransitionPlan<R>(
      String operation,
      String reason,
      BooleanSupplier transition,
      Runnable afterTransition,
      Supplier<R> notFound,
      Supplier<R> reauthenticationRequired,
      String constraintName,
      Supplier<R> constraintRejection,
      Boolean audited) {

    boolean isAudited() {
      return audited == null || audited;
    }

    void runAfterTransition() {
      if (afterTransition != null) {
        afterTransition.run();
      }
    }

    Optional<R> rejectionForConstraint(String violated) {
      if (constraintName != null && constraintName.equals(violated)) {
        return Optional.of(constraintRejection.get());
      }

      return Optional.empty();
    }

    static class TransitionPlanBuilder<R> {
      TransitionPlanBuilder<R> constraint(String name, Supplier<R> rejection) {
        return constraintName(name).constraintRejection(rejection);
      }
    }
  }
}
