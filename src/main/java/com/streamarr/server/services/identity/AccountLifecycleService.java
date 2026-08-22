package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.DeviceRegistrationLifecycle;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Account transfers and deletion (ADR 0024 §Transfers, §Account deletion): an Account and its
 * Personal Profile move together, the write is a conditional partial update that can never carry a
 * stale password hash or clobber a concurrent rename, and every lifecycle boundary — final Account,
 * last HouseholdAdmin, last ServerAdmin, anchors, supervision — is judged by the deferred
 * invariants at commit and answered as its typed rejection. The final Account of a Household moves
 * only through teardown.
 */
@Service
@RequiredArgsConstructor
public class AccountLifecycleService {

  private static final String CHK_RETAINS_ADMIN = "chk_household_retains_admin";
  private static final String CHK_RETAINS_ACCOUNT = "chk_household_retains_account";
  private static final String CHK_SERVER_ADMIN_REMAINS = "chk_enabled_server_admin_remains";
  private static final String CHK_HOME_ANCHOR = "chk_profile_home_anchor";
  private static final String CHK_NAMES_UNIQUE = "chk_household_profile_names_unique";
  private static final String CHK_HOSTING_ADMIN = "chk_hosting_household_retains_eligible_admin";
  private static final String CHK_RESTRICTED_AUTHORITY =
      "chk_restricted_account_holds_no_authority";

  private final AuthorizationService authorizationService;
  private final UserAccountRepository userAccountRepository;
  private final ProfileRepository profileRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final HouseholdRepository householdRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileManagerInvitationRepository managerInvitationRepository;
  private final AccountInvitationRepository accountInvitationRepository;
  private final PasswordResetCodeRepository passwordResetCodeRepository;
  private final AuthSessionRepository authSessionRepository;
  private final DeviceRegistrationLifecycle registrationLifecycle;
  private final SecurityAuditEventRepository securityAuditEventRepository;
  private final MutationTransactions mutationTransactions;
  private final Clock clock;

  public Outcome<UserAccount, TransferRejections.TransferAccount> transferAccount(
      AuthenticatedIdentity identity, TransferAccountCommand command) {
    var refusal =
        refusalOf(
            identity,
            new Intent.TransferAccount(command.accountId()),
            () -> mayViewAccount(identity, command.accountId()),
            TransferRejections.AccountNotFound::new,
            null);
    if (refusal.isPresent()) {
      return Outcome.rejected((TransferRejections.TransferAccount) refusal.get());
    }
    var account = userAccountRepository.findById(command.accountId());
    if (account.isEmpty()) {
      return Outcome.rejected(new TransferRejections.AccountNotFound());
    }
    if (householdRepository.findById(command.destinationHouseholdId()).isEmpty()) {
      return Outcome.rejected(new TransferRejections.HouseholdNotFound());
    }
    if (command.destinationHouseholdId().equals(account.get().getHouseholdId())) {
      return Outcome.rejected(new TransferRejections.SameHousehold());
    }
    var sourceHouseholdId = account.get().getHouseholdId();
    var profileId = account.get().getPersonalProfileId();
    var now = clock.instant();
    return mutationTransactions.write(
        () -> {
          if (userAccountRepository.findByHouseholdId(sourceHouseholdId).size() <= 1) {
            // The final Account of a Household is handled only by teardown (ADR 0024).
            throw new MutationRejection(new TransferRejections.FinalAccount());
          }
          var destinationEmpty =
              userAccountRepository.findByHouseholdId(command.destinationHouseholdId()).isEmpty();
          if (!userAccountRepository.tryTransfer(
              command.accountId(),
              sourceHouseholdId,
              command.destinationHouseholdId(),
              destinationEmpty ? HouseholdRole.ADMIN : HouseholdRole.MEMBER)) {
            throw new MutationRejection(new TransferRejections.AccountNotFound());
          }
          if (!profileRepository.tryRehome(
              profileId, sourceHouseholdId, command.destinationHouseholdId())) {
            throw new MutationRejection(new TransferRejections.AccountNotFound());
          }
          moveHomeAvailability(command, sourceHouseholdId, profileId, now);
          shareRepository.upsertStructuralHomeShare(
              profileId, command.destinationHouseholdId(), now);
          audit(identity, "transferAccount", "accountId", command.accountId(), command.reason());
          // The refusal checks JPA-loaded this row in this transaction; re-read past the
          // first-level cache or the payload would show the pre-transfer state.
          return userAccountRepository.findRefreshedById(command.accountId()).orElseThrow();
        },
        this::transferConstraint);
  }

  public Outcome<UUID, TransferRejections.DeleteAccount> deleteAccount(
      AuthenticatedIdentity identity, DeleteAccountCommand command) {
    if (isBlank(command.reason())) {
      return Outcome.rejected(new TransferRejections.ReasonRequired());
    }
    var refusal =
        refusalOf(
            identity,
            new Intent.DeleteAccount(command.accountId()),
            () -> mayViewAccount(identity, command.accountId()),
            TransferRejections.AccountNotFound::new,
            TransferRejections.ReauthenticationRequired::new);
    if (refusal.isPresent()) {
      return Outcome.rejected((TransferRejections.DeleteAccount) refusal.get());
    }
    var account = userAccountRepository.findById(command.accountId());
    if (account.isEmpty()) {
      return Outcome.rejected(new TransferRejections.AccountNotFound());
    }
    var replacementRefusal = replacementRefusal(command, account.get());
    if (replacementRefusal.isPresent()) {
      return Outcome.rejected(replacementRefusal.get());
    }
    return mutationTransactions.write(
        () -> {
          erase(identity, account.get(), command);
          audit(identity, "deleteAccount", "accountId", account.get().getId(), command.reason());
          return command.accountId();
        },
        this::deletionConstraint);
  }

  public Outcome<UUID, TransferRejections.DeleteMyAccount> deleteMyAccount(
      AuthenticatedIdentity identity, String confirmation) {
    if (!"DELETE".equals(confirmation)) {
      return Outcome.rejected(new TransferRejections.ConfirmationRequired());
    }
    var refusal =
        refusalOf(
            identity,
            new Intent.DeleteMyAccount(),
            // The caller always sees their own Account: an authority denial stays FORBIDDEN.
            () -> true,
            () -> null,
            TransferRejections.ReauthenticationRequired::new);
    if (refusal.isPresent()) {
      return Outcome.rejected((TransferRejections.DeleteMyAccount) refusal.get());
    }
    var account = userAccountRepository.findById(identity.accountId()).orElseThrow();
    return mutationTransactions.write(
        () -> {
          erase(
              identity,
              account,
              DeleteAccountCommand.builder()
                  .accountId(account.getId())
                  .profileDisposition(ProfileDisposition.ERASE)
                  .reason("self-deletion")
                  .build());
          audit(identity, "deleteMyAccount", "accountId", account.getId(), "self-deletion");
          return account.getId();
        },
        this::selfDeletionConstraint);
  }

  private void erase(
      AuthenticatedIdentity identity, UserAccount account, DeleteAccountCommand command) {
    var now = clock.instant();
    if (userAccountRepository.findByHouseholdId(account.getHouseholdId()).size() <= 1) {
      throw new MutationRejection(new TransferRejections.FinalAccount());
    }
    registrationLifecycle.revokeAllByAccount(account.getId(), "Account deleted", now);
    authSessionRepository.revokeAllForAccount(
        account.getId(), SessionRevocationReason.ADMIN_REVOCATION, now);
    accountInvitationRepository.invalidateIssuedBy(account.getId(), "issuer deleted", now);
    passwordResetCodeRepository.invalidateIssuedBy(account.getId(), "issuer deleted", now);
    managerInvitationRepository.invalidatePendingForRecipient(
        account.getId(), "recipient deleted", now);
    managerInvitationRepository.invalidatePendingForInviter(
        account.getId(), "inviting manager deleted", now);
    shareRepository.invalidatePendingSharesOfferedByAccount(
        account.getId(), "offering manager deleted", now);

    var profileId = account.getPersonalProfileId();
    if (command.profileDisposition() == ProfileDisposition.KEEP) {
      // The preserved Profile needs its replacement anchor before the person leaves it behind.
      profileManagerRepository.tryGrant(command.replacementManagerAccountId(), profileId);
      shareRepository.tryDemoteStructural(profileId, account.getHouseholdId(), now);
      deleteAccountRow(account);
    } else {
      deleteAccountRow(account);
      accountInvitationRepository.invalidatePendingForProfile(profileId, "Profile deleted", now);
      managerInvitationRepository.invalidatePendingForProfile(profileId, "Profile deleted", now);
      clearSelectionsEverywhere(profileId, now);
      profileRepository.deleteById(profileId);
      profileRepository.flush();
    }
  }

  private void deleteAccountRow(UserAccount account) {
    if (!userAccountRepository.tryDelete(account.getId(), account.getHouseholdId())) {
      throw new MutationRejection(new TransferRejections.AccountNotFound());
    }
  }

  private void moveHomeAvailability(
      TransferAccountCommand command, UUID sourceHouseholdId, UUID profileId, Instant now) {
    if (command.sourceAccess() == SourceAccess.KEEP_AS_VISITOR) {
      shareRepository.tryDemoteStructural(profileId, sourceHouseholdId, now);
      authSessionRepository.clearSelections(profileId, sourceHouseholdId, now);
      return;
    }
    shareRepository
        .findByProfileIdAndHouseholdIdAndStatus(
            profileId, sourceHouseholdId, ProfileShareStatus.ACTIVE)
        .ifPresent(share -> shareRepository.tryEnd(share.getId(), now));
    authSessionRepository.clearSelections(profileId, sourceHouseholdId, now);
    authSessionRepository.resetContextForAccount(command.accountId(), sourceHouseholdId, now);
    registrationLifecycle.revokeAllByAccountAndHousehold(
        command.accountId(), sourceHouseholdId, "old Household access ended", now);
  }

  private void clearSelectionsEverywhere(UUID profileId, Instant now) {
    shareRepository
        .findByProfileIdAndStatus(profileId, ProfileShareStatus.ACTIVE)
        .forEach(
            share -> authSessionRepository.clearSelections(profileId, share.getHouseholdId(), now));
  }

  private Optional<TransferRejections.DeleteAccount> replacementRefusal(
      DeleteAccountCommand command, UserAccount account) {
    if (command.profileDisposition() != ProfileDisposition.KEEP) {
      return Optional.empty();
    }
    if (command.replacementManagerAccountId() == null) {
      return Optional.of(new TransferRejections.ReplacementManagerRequired());
    }
    var replacement = userAccountRepository.findById(command.replacementManagerAccountId());
    if (replacement.isEmpty()) {
      return Optional.of(new TransferRejections.ReplacementManagerNotFound());
    }
    var restricted =
        profileRepository.findById(account.getPersonalProfileId()).orElseThrow().isRestricted();
    // T6: the anchor lives in the Profile's own Household and is themselves unrestricted.
    var anchored =
        replacement
            .filter(anchor -> anchor.getHouseholdId().equals(account.getHouseholdId()))
            .filter(anchor -> !anchor.getId().equals(account.getId()))
            .filter(anchor -> !restricted || anchor.getHouseholdRole() == HouseholdRole.ADMIN)
            .filter(this::isEligible)
            .isPresent();
    if (!anchored) {
      return Optional.of(new TransferRejections.ReplacementManagerNotEligible());
    }
    return Optional.empty();
  }

  private boolean isEligible(UserAccount account) {
    return profileRepository
        .findById(account.getPersonalProfileId())
        .filter(profile -> !profile.isRestricted())
        .isPresent();
  }

  private Optional<TransferRejections.TransferAccount> transferConstraint(String constraint) {
    return switch (constraint) {
      case CHK_RETAINS_ADMIN -> Optional.of(new TransferRejections.LastHouseholdAdmin());
      case CHK_HOSTING_ADMIN -> Optional.of(new TransferRejections.NoEligibleAdmin());
      case CHK_NAMES_UNIQUE -> Optional.of(new TransferRejections.NameConflict());
      case CHK_HOME_ANCHOR -> Optional.of(new TransferRejections.AnchorRequired());
      case CHK_RESTRICTED_AUTHORITY -> Optional.of(new TransferRejections.RestrictedFirstAccount());
      default -> Optional.empty();
    };
  }

  private Optional<TransferRejections.DeleteAccount> deletionConstraint(String constraint) {
    return switch (constraint) {
      case CHK_RETAINS_ACCOUNT -> Optional.of(new TransferRejections.FinalAccount());
      case CHK_RETAINS_ADMIN -> Optional.of(new TransferRejections.LastHouseholdAdmin());
      case CHK_SERVER_ADMIN_REMAINS -> Optional.of(new TransferRejections.LastServerAdmin());
      case CHK_HOME_ANCHOR -> Optional.of(new TransferRejections.AnchorRequired());
      case CHK_HOSTING_ADMIN -> Optional.of(new TransferRejections.NoEligibleAdmin());
      default -> Optional.empty();
    };
  }

  private Optional<TransferRejections.DeleteMyAccount> selfDeletionConstraint(String constraint) {
    return switch (constraint) {
      case CHK_RETAINS_ACCOUNT -> Optional.of(new TransferRejections.FinalAccount());
      case CHK_RETAINS_ADMIN -> Optional.of(new TransferRejections.LastHouseholdAdmin());
      case CHK_SERVER_ADMIN_REMAINS -> Optional.of(new TransferRejections.LastServerAdmin());
      case CHK_HOME_ANCHOR -> Optional.of(new TransferRejections.AnchorRequired());
      case CHK_HOSTING_ADMIN -> Optional.of(new TransferRejections.NoEligibleAdmin());
      default -> Optional.empty();
    };
  }

  private boolean mayViewAccount(AuthenticatedIdentity identity, UUID accountId) {
    return authorizationService.decide(identity, new Intent.ViewAccountAdministration(accountId))
        instanceof Decision.Allowed<?>;
  }

  private void audit(
      AuthenticatedIdentity identity,
      String operation,
      String resourceName,
      UUID resourceId,
      String reason) {
    securityAuditEventRepository.append(
        SecurityAuditEntry.builder()
            .operation(operation)
            .actorAccountId(identity.accountId())
            .reason(reason)
            .resource(resourceName, resourceId)
            .build());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private Optional<Object> refusalOf(
      AuthenticatedIdentity identity,
      Intent<?> intent,
      BooleanSupplier mayView,
      Supplier<Object> denied,
      Supplier<Object> reauthenticationRequired) {
    return switch (authorizationService.decide(identity, intent)) {
      case Decision.Allowed<?> _ -> Optional.empty();
      case Decision.Failed<?> _ -> throw new AuthorizationUnavailableException();
      case Decision.Denied<?>(var reason) ->
          switch (reason) {
            case REAUTHENTICATION_REQUIRED -> Optional.of(reauthenticationRequired.get());
            case POLICY -> {
              if (mayView.getAsBoolean()) {
                throw new AccessDeniedException("Not allowed.");
              }
              yield Optional.of(denied.get());
            }
          };
    };
  }

  /** How the old Household reads after the person moves on (ADR 0024 §Transfers). */
  public enum SourceAccess {
    END,
    KEEP_AS_VISITOR
  }

  /** What happens to the deleted person's Personal Profile (ADR 0024 §Account deletion). */
  public enum ProfileDisposition {
    KEEP,
    ERASE
  }

  @Builder
  public record TransferAccountCommand(
      UUID accountId, UUID destinationHouseholdId, SourceAccess sourceAccess, String reason) {}

  @Builder
  public record DeleteAccountCommand(
      UUID accountId,
      ProfileDisposition profileDisposition,
      UUID replacementManagerAccountId,
      String reason) {}
}
