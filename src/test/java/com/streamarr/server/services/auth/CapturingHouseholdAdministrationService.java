package com.streamarr.server.services.auth;

import java.util.UUID;

public final class CapturingHouseholdAdministrationService extends HouseholdAdministrationService {

  private AccountHouseholdTransferCommand accountTransfer;
  private HouseholdOwnershipTransferCommand ownershipTransfer;

  public CapturingHouseholdAdministrationService() {
    super(null, null, null, null, null, null, null, null);
  }

  @Override
  public PreparedAccountHouseholdTransfer prepare(AccountHouseholdTransferCommand command) {
    accountTransfer = command;
    return new PreparedAccountHouseholdTransfer(
        command.actingAccountId(),
        command.targetAccountId(),
        command.targetHouseholdId(),
        command.targetRole(),
        command.reason(),
        authority(command.actingAccountId()));
  }

  @Override
  public void transferAccount(PreparedAccountHouseholdTransfer command) {}

  @Override
  public PreparedHouseholdOwnershipTransfer prepare(HouseholdOwnershipTransferCommand command) {
    ownershipTransfer = command;
    return new PreparedHouseholdOwnershipTransfer(
        command.actingAccountId(),
        command.householdId(),
        command.targetAccountId(),
        command.reason(),
        authority(command.actingAccountId()));
  }

  @Override
  public void transferOwnership(PreparedHouseholdOwnershipTransfer command) {}

  public AccountHouseholdTransferCommand accountTransfer() {
    return accountTransfer;
  }

  public HouseholdOwnershipTransferCommand ownershipTransfer() {
    return ownershipTransfer;
  }

  private PasswordReauthentication authority(UUID accountId) {
    return new PasswordReauthentication(accountId);
  }
}
