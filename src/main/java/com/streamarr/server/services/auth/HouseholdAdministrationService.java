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
import java.util.UUID;
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

  public PreparedAccountHouseholdTransfer prepare(AccountHouseholdTransferCommand command) {
    requireReason(command.reason());
    return new PreparedAccountHouseholdTransfer(
        command.actingAccountId(),
        command.targetAccountId(),
        command.targetHouseholdId(),
        command.targetRole(),
        command.reason(),
        serverAdminAuthorizer.prepare(command.actingAccountId(), command.password()));
  }

  @Transactional
  public void transferAccount(PreparedAccountHouseholdTransfer command) {
    serverAdminAuthorizer.requireFreshAuthority(command.authority());
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

  public PreparedHouseholdOwnershipTransfer prepare(HouseholdOwnershipTransferCommand command) {
    requireReason(command.reason());
    var actor =
        accountRepository
            .findById(command.actingAccountId())
            .orElseThrow(HouseholdAccessDeniedException::new);
    var authority = prepareOwnershipAuthority(actor, command);
    return new PreparedHouseholdOwnershipTransfer(
        command.actingAccountId(),
        command.householdId(),
        command.targetAccountId(),
        command.reason(),
        authority);
  }

  @Transactional
  public void transferOwnership(PreparedHouseholdOwnershipTransfer command) {
    if (!accountRepository.lockIfHouseholdAuthority(
        command.actingAccountId(), command.householdId())) {
      throw new HouseholdAccessDeniedException();
    }
    householdRepository
        .findById(command.householdId())
        .orElseThrow(HouseholdAccessDeniedException::new);

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

  private PasswordReauthentication prepareOwnershipAuthority(
      UserAccount actor, HouseholdOwnershipTransferCommand command) {
    var isCurrentOwner =
        actor.isEnabled()
            && command.householdId().equals(actor.getHomeHouseholdId())
            && actor.getHouseholdRole() == HouseholdRole.OWNER;
    var isServerAdmin = actor.isEnabled() && actor.getAccountRole() == AccountRole.ADMIN;
    if (!isCurrentOwner && !isServerAdmin) {
      throw new HouseholdAccessDeniedException();
    }
    var expectedPasswordHash = actor.getPasswordHash();
    if (!passwordEncoder.matches(command.password(), expectedPasswordHash)) {
      throw new InvalidCredentialsException();
    }
    return new PasswordReauthentication(actor.getId());
  }

  private void requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("An administration reason is required.");
    }
  }

  record PreparedAccountHouseholdTransfer(
      UUID actingAccountId,
      UUID targetAccountId,
      UUID targetHouseholdId,
      HouseholdRole targetRole,
      String reason,
      PasswordReauthentication authority) {}

  record PreparedHouseholdOwnershipTransfer(
      UUID actingAccountId,
      UUID householdId,
      UUID targetAccountId,
      String reason,
      PasswordReauthentication authority) {}
}
