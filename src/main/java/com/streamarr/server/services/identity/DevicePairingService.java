package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.exceptions.EsnBlockedException;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.repositories.auth.EsnBlockRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.DeviceAuthorizationDetails;
import com.streamarr.server.services.auth.DeviceAuthorizationService;
import com.streamarr.server.services.auth.DeviceCodePresentation;
import com.streamarr.server.services.auth.DeviceDecision;
import com.streamarr.server.services.auth.DeviceDecisionCommand;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Authorizes pairing decisions after the code check is journaled. Approval requires access to the
 * chosen Household and an unblocked ESN. These permissions are checked again when the device polls
 * for its session (ADR 0024 §Devices).
 */
@Service
@RequiredArgsConstructor
public class DevicePairingService {

  private final AuthorizationService authorizationService;
  private final DeviceAuthorizationService deviceAuthorizationService;
  private final UserAccountRepository userAccountRepository;
  private final HouseholdRepository householdRepository;
  private final EsnBlockRepository esnBlockRepository;

  /** What the approver is shown: the device and the Households they could bind it to. */
  public PairingLookupDetails lookup(AuthenticatedIdentity identity, PairingLookupCommand command) {
    var details =
        deviceAuthorizationService.lookup(
            presentation(identity, command.userCode(), command.ipAddress()));
    return new PairingLookupDetails(details, eligibleHouseholds(identity));
  }

  public DeviceAuthorizationDetails decide(
      AuthenticatedIdentity identity, PairingDecisionCommand command) {
    var grant =
        deviceAuthorizationService.resolveForDecision(
            presentation(identity, command.userCode(), command.ipAddress()));
    authorizationService.requireAllowed(identity, new Intent.LinkDevice(grant.grantId()));
    if (command.decision() == DeviceDecision.APPROVE) {
      validateBinding(identity, command.householdId(), grant.esn());
    }

    return deviceAuthorizationService.decide(
        DeviceDecisionCommand.builder()
            .userCode(command.userCode())
            .decision(command.decision())
            .decidedByAccountId(identity.accountId())
            .chosenHouseholdId(
                command.decision() == DeviceDecision.APPROVE ? command.householdId() : null)
            .build());
  }

  private static DeviceCodePresentation presentation(
      AuthenticatedIdentity identity, String userCode, String ipAddress) {
    return DeviceCodePresentation.builder()
        .userCode(userCode)
        .approverAccountId(identity.accountId())
        .ipAddress(ipAddress)
        .build();
  }

  private void validateBinding(
      AuthenticatedIdentity identity, UUID householdId, Optional<String> esn) {
    if (householdId == null) {
      throw new HouseholdRequiredException();
    }

    if (!userAccountRepository.mayUseHousehold(identity.accountId(), householdId)) {
      throw new HouseholdAccessDeniedException();
    }

    if (esn.filter(value -> isEsnBlocked(value, householdId)).isPresent()) {
      throw new EsnBlockedException();
    }
  }

  private boolean isEsnBlocked(String esn, UUID householdId) {
    return esnBlockRepository.existsByEsnAndHouseholdIdIsNull(esn)
        || esnBlockRepository.existsByEsnAndHouseholdId(esn, householdId);
  }

  private List<EligibleHouseholdDetails> eligibleHouseholds(AuthenticatedIdentity identity) {
    var ids = userAccountRepository.findUsableHouseholdIds(identity.accountId());
    return householdRepository.findAllById(ids).stream()
        .sorted(Comparator.comparing(household -> ids.indexOf(household.getId())))
        .map(EligibleHouseholdDetails::from)
        .toList();
  }

  public record PairingLookupDetails(
      DeviceAuthorizationDetails authorization, List<EligibleHouseholdDetails> households) {}

  public record EligibleHouseholdDetails(UUID id, String name) {

    static EligibleHouseholdDetails from(Household household) {
      return new EligibleHouseholdDetails(household.getId(), household.getName());
    }
  }

  @Builder
  public record PairingLookupCommand(String userCode, @NonNull String ipAddress) {

    @Override
    public String toString() {
      return "PairingLookupCommand[userCode=REDACTED, ipAddress=%s]".formatted(ipAddress);
    }

    public static class PairingLookupCommandBuilder {

      @Override
      public String toString() {
        return "PairingLookupCommandBuilder[REDACTED]";
      }
    }
  }

  @Builder
  public record PairingDecisionCommand(
      String userCode, DeviceDecision decision, UUID householdId, @NonNull String ipAddress) {

    @Override
    public String toString() {
      return "PairingDecisionCommand[userCode=REDACTED, decision=%s, householdId=%s,"
              .formatted(decision, householdId)
          + " ipAddress=%s]".formatted(ipAddress);
    }

    public static class PairingDecisionCommandBuilder {

      @Override
      public String toString() {
        return "PairingDecisionCommandBuilder[REDACTED]";
      }
    }
  }
}
