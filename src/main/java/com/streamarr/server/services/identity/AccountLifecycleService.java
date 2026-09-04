package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccountLifecycleService {

  private static final String CHK_RETAINS_ADMIN = "chk_household_retains_admin";
  private static final String CHK_RETAINS_ACCOUNT = "chk_household_retains_account";
  private static final String CHK_SERVER_ADMIN_REMAINS = "chk_enabled_server_admin_remains";
  private static final String CHK_ELIGIBLE_MANAGER = "chk_profile_home_anchor";
  private static final String CHK_NAMES_UNIQUE = "chk_household_profile_names_unique";
  private static final String CHK_HOSTING_ADMIN = "chk_hosting_household_retains_eligible_admin";
  private static final String CHK_RESTRICTED_AUTHORITY =
      "chk_restricted_account_holds_no_authority";
  private static final String ACCOUNT_ID = "accountId";

  private final AuthorizationService authorizationService;
  private final AccountRemoval accountRemoval;
  private final UserAccountRepository userAccountRepository;
  private final ProfileRepository profileRepository;
  private final HouseholdRepository householdRepository;
  private final SecurityAuditEventRepository securityAuditEventRepository;
  private final MutationTransactions mutationTransactions;
  private final Clock clock;

  public Outcome<UserAccount, TransferRejections.TransferAccount> transferAccount(
      AuthenticatedIdentity identity, TransferAccountCommand command) {
    Optional<TransferRejections.TransferAccount> refusal =
        AuthorizationRefusal.from(
            authorizationService.decide(identity, new Intent.TransferAccount(command.accountId())),
            new AuthorizationRefusal.Response<>(
                () -> mayViewAccount(identity, command.accountId()),
                TransferRejections.AccountNotFound::new,
                Optional.empty()));
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
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
            throw new MutationRejection(new TransferRejections.FinalAccount());
          }

          var destinationEmpty =
              userAccountRepository.findByHouseholdId(command.destinationHouseholdId()).isEmpty();
          if (!accountRemoval.move(
              command.accountId(),
              sourceHouseholdId,
              profileId,
              command.destinationHouseholdId(),
              destinationEmpty,
              command.sourceHouseholdAccess(),
              now)) {
            throw new MutationRejection(new TransferRejections.AccountNotFound());
          }

          audit(identity, "transferAccount", ACCOUNT_ID, command.accountId(), command.reason());
          // The refusal checks JPA-loaded this row in this transaction; re-read past the
          // first-level cache or the payload would show the pre-transfer state.
          return userAccountRepository
              .findByIdAndReloadFromDatabase(command.accountId())
              .orElseThrow();
        },
        this::transferConstraint);
  }

  public Outcome<UUID, TransferRejections.AdministrativelyDeleteAccount>
      administrativelyDeleteAccount(
          AuthenticatedIdentity identity, AdministrativelyDeleteAccountCommand command) {
    if (isBlank(command.reason())) {
      return Outcome.rejected(new TransferRejections.ReasonRequired());
    }

    Optional<TransferRejections.AdministrativelyDeleteAccount> refusal =
        AuthorizationRefusal.from(
            authorizationService.decide(
                identity, new Intent.AdministrativelyDeleteAccount(command.accountId())),
            new AuthorizationRefusal.Response<>(
                () -> mayViewAccount(identity, command.accountId()),
                TransferRejections.AccountNotFound::new,
                Optional.of(TransferRejections.ReauthenticationRequired::new)));
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
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
          erase(account.get(), command);
          audit(
              identity,
              "administrativelyDeleteAccount",
              ACCOUNT_ID,
              account.get().getId(),
              command.reason());
          return command.accountId();
        },
        this::deletionConstraint);
  }

  public Outcome<UUID, TransferRejections.DeleteMyAccount> deleteMyAccount(
      AuthenticatedIdentity identity, String confirmation) {
    if (!"DELETE".equals(confirmation)) {
      return Outcome.rejected(new TransferRejections.ConfirmationRequired());
    }

    Optional<TransferRejections.DeleteMyAccount> refusal =
        AuthorizationRefusal.from(
            authorizationService.decide(identity, new Intent.DeleteMyAccount()),
            new AuthorizationRefusal.Response<>(
                // The caller always sees their own Account: an authority denial stays FORBIDDEN.
                () -> true,
                () -> {
                  throw new AccessDeniedException("Not allowed.");
                },
                Optional.of(TransferRejections.ReauthenticationRequired::new)));
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    var account = userAccountRepository.findById(identity.accountId()).orElseThrow();
    return mutationTransactions.write(
        () -> {
          erase(
              account,
              AdministrativelyDeleteAccountCommand.builder()
                  .accountId(account.getId())
                  .profileCleanup(ProfileCleanup.ERASE_PROFILE)
                  .reason("self-deletion")
                  .build());
          audit(identity, "deleteMyAccount", ACCOUNT_ID, account.getId(), "self-deletion");
          return account.getId();
        },
        this::selfDeletionConstraint);
  }

  private void erase(UserAccount account, AdministrativelyDeleteAccountCommand command) {
    if (userAccountRepository.findByHouseholdId(account.getHouseholdId()).size() <= 1) {
      throw new MutationRejection(new TransferRejections.FinalAccount());
    }

    accountRemoval.erase(
        account, command.profileCleanup(), command.replacementManagerAccountId(), clock.instant());
  }

  private Optional<TransferRejections.AdministrativelyDeleteAccount> replacementRefusal(
      AdministrativelyDeleteAccountCommand command, UserAccount account) {
    if (command.profileCleanup() != ProfileCleanup.PRESERVE_PROFILE) {
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
    var eligible =
        replacement
            .filter(candidate -> candidate.getHouseholdId().equals(account.getHouseholdId()))
            .filter(candidate -> !candidate.getId().equals(account.getId()))
            .filter(candidate -> !restricted || candidate.getHouseholdRole() == HouseholdRole.ADMIN)
            .filter(this::isEligible)
            .isPresent();
    if (!eligible) {
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
      case CHK_RETAINS_ACCOUNT -> Optional.of(new TransferRejections.FinalAccount());
      case CHK_RETAINS_ADMIN -> Optional.of(new TransferRejections.LastHouseholdAdmin());
      case CHK_HOSTING_ADMIN -> Optional.of(new TransferRejections.NoEligibleAdmin());
      case CHK_NAMES_UNIQUE -> Optional.of(new TransferRejections.NameConflict());
      case CHK_ELIGIBLE_MANAGER -> Optional.of(new TransferRejections.EligibleManagerRequired());
      case CHK_RESTRICTED_AUTHORITY -> Optional.of(new TransferRejections.RestrictedFirstAccount());
      default -> Optional.empty();
    };
  }

  private Optional<TransferRejections.AdministrativelyDeleteAccount> deletionConstraint(
      String constraint) {
    return switch (constraint) {
      case CHK_RETAINS_ACCOUNT -> Optional.of(new TransferRejections.FinalAccount());
      case CHK_RETAINS_ADMIN -> Optional.of(new TransferRejections.LastHouseholdAdmin());
      case CHK_SERVER_ADMIN_REMAINS -> Optional.of(new TransferRejections.LastServerAdmin());
      case CHK_ELIGIBLE_MANAGER -> Optional.of(new TransferRejections.EligibleManagerRequired());
      case CHK_HOSTING_ADMIN -> Optional.of(new TransferRejections.NoEligibleAdmin());
      default -> Optional.empty();
    };
  }

  private Optional<TransferRejections.DeleteMyAccount> selfDeletionConstraint(String constraint) {
    return switch (constraint) {
      case CHK_RETAINS_ACCOUNT -> Optional.of(new TransferRejections.FinalAccount());
      case CHK_RETAINS_ADMIN -> Optional.of(new TransferRejections.LastHouseholdAdmin());
      case CHK_SERVER_ADMIN_REMAINS -> Optional.of(new TransferRejections.LastServerAdmin());
      case CHK_ELIGIBLE_MANAGER -> Optional.of(new TransferRejections.EligibleManagerRequired());
      case CHK_HOSTING_ADMIN -> Optional.of(new TransferRejections.NoEligibleAdmin());
      default -> Optional.empty();
    };
  }

  private boolean mayViewAccount(AuthenticatedIdentity identity, UUID accountId) {
    return authorizationService.decide(identity, new Intent.ViewAccountAdministration(accountId))
        instanceof Decision.Allowed<AuthorizationUnit>;
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

  /** Access retained in the Account's former Household after transfer. */
  public enum SourceHouseholdAccess {
    END,
    KEEP_AS_VISITOR
  }

  /** Cleanup applied to the Personal Profile when its Account is deleted. */
  public enum ProfileCleanup {
    ERASE_PROFILE,
    PRESERVE_PROFILE
  }

  @Builder
  public record TransferAccountCommand(
      UUID accountId,
      UUID destinationHouseholdId,
      SourceHouseholdAccess sourceHouseholdAccess,
      String reason) {}

  @Builder
  public record AdministrativelyDeleteAccountCommand(
      UUID accountId,
      ProfileCleanup profileCleanup,
      UUID replacementManagerAccountId,
      String reason) {}
}
