package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.HouseholdOwnershipTransferRequiredException;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HouseholdAdministrationService {

  private final UserAccountRepository accountRepository;
  private final HouseholdRepository householdRepository;
  private final AuthSessionRepository sessionRepository;
  private final ServerAdminAuthorizer serverAdminAuthorizer;
  private final KidProfileManagerPolicy kidManagerPolicy;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;
  private final SecurityAuditService auditService;

  /**
   * Transfers an account to a household with the specified household role.
   *
   * @param command the transfer details, including authorization, target household, target account, role, and reason
   */
  @Transactional
  public void transferAccount(AccountHouseholdTransferCommand command) {
    requireReason(command.reason());
    serverAdminAuthorizer.requireFreshAuthority(command.actingAccountId(), command.password());
    householdRepository
        .findById(command.targetHouseholdId())
        .orElseThrow(HouseholdAccessDeniedException::new);
    var account =
        accountRepository
            .findById(command.targetAccountId())
            .orElseThrow(HouseholdAccessDeniedException::new);
    if (account.getHouseholdRole() == HouseholdRole.OWNER) {
      throw new HouseholdOwnershipTransferRequiredException();
    }
    if (command.targetRole() == HouseholdRole.OWNER) {
      throw new HouseholdOwnershipTransferRequiredException();
    }

    kidManagerPolicy.validateAccountDeparture(account.getId(), account.getHomeHouseholdId());
    account.setHomeHouseholdId(command.targetHouseholdId());
    account.setHouseholdRole(command.targetRole());
    accountRepository.saveAndFlush(account);
    sessionRepository.clearAccountSelections(account.getId(), clock.instant());
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(command.actingAccountId())
            .targetAccountId(command.targetAccountId())
            .targetHouseholdId(command.targetHouseholdId())
            .operation(SecurityAuditOperation.ACCOUNT_TRANSFERRED)
            .reason(command.reason())
            .build());
  }

  /**
   * Transfers household ownership to an eligible account in the household.
   *
   * @param command the ownership transfer details, including the household, acting account,
   *                target account, password, and reason
   * @throws IllegalArgumentException if the target account already owns the household
   */
  @Transactional
  public void transferOwnership(HouseholdOwnershipTransferCommand command) {
    requireReason(command.reason());
    householdRepository
        .findById(command.householdId())
        .orElseThrow(HouseholdAccessDeniedException::new);
    var actor =
        accountRepository
            .findById(command.actingAccountId())
            .orElseThrow(HouseholdAccessDeniedException::new);
    requireOwnershipAuthority(actor, command);

    var currentOwner =
        accountRepository
            .findOwnerByHomeHouseholdId(command.householdId())
            .orElseThrow(HouseholdAccessDeniedException::new);
    var nextOwner =
        accountRepository
            .findById(command.targetAccountId())
            .filter(candidate -> candidate.isEnabled())
            .filter(candidate -> command.householdId().equals(candidate.getHomeHouseholdId()))
            .orElseThrow(HouseholdAccessDeniedException::new);
    if (currentOwner.getId().equals(nextOwner.getId())) {
      throw new IllegalArgumentException("The target account already owns the household.");
    }

    currentOwner.setHouseholdRole(HouseholdRole.PARENT);
    accountRepository.saveAndFlush(currentOwner);
    nextOwner.setHouseholdRole(HouseholdRole.OWNER);
    accountRepository.saveAndFlush(nextOwner);
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(command.actingAccountId())
            .targetAccountId(command.targetAccountId())
            .targetHouseholdId(command.householdId())
            .operation(SecurityAuditOperation.HOUSEHOLD_OWNERSHIP_TRANSFERRED)
            .reason(command.reason())
            .build());
  }

  /**
   * Verifies that the actor may transfer ownership of the specified household.
   *
   * @param actor   the account requesting the ownership transfer
   * @param command the transfer request containing the household identifier and password
   * @throws HouseholdAccessDeniedException if the actor is neither the enabled household owner nor an enabled server administrator
   * @throws InvalidCredentialsException     if the supplied password is invalid
   */
  private void requireOwnershipAuthority(
      UserAccount actor, HouseholdOwnershipTransferCommand command) {
    var isCurrentOwner =
        actor.isEnabled()
            && command.householdId().equals(actor.getHomeHouseholdId())
            && actor.getHouseholdRole() == HouseholdRole.OWNER;
    var isServerAdmin = actor.isEnabled() && actor.getAccountRole() == AccountRole.ADMIN;
    if (!isCurrentOwner && !isServerAdmin) {
      throw new HouseholdAccessDeniedException();
    }
    if (!passwordEncoder.matches(command.password(), actor.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }
  }

  /**
   * Ensures that an administration reason is provided.
   *
   * @param reason the administration reason to validate
   * @throws IllegalArgumentException if the reason is null or blank
   */
  private void requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("An administration reason is required.");
    }
  }
}
