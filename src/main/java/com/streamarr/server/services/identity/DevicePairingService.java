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
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The approval half of pairing (ADR 0024 §Devices): the typed code is resolved to its grant —
 * reserving one credential attempt — before Cedar decides linkDevice, the chosen Household is
 * validated as one the approver may use, and the ESN block is checked for that Household. The
 * winning poll rechecks all of it live; ADR 0021's transport stays in the auth layer untouched.
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

  private void validateBinding(AuthenticatedIdentity identity, UUID householdId, String esn) {
    if (householdId == null) {
      throw new HouseholdRequiredException();
    }

    if (!userAccountRepository.mayUseHousehold(identity.accountId(), householdId)) {
      throw new HouseholdAccessDeniedException();
    }

    if (esn != null
        && (esnBlockRepository.existsByEsnAndHouseholdIdIsNull(esn)
            || esnBlockRepository.existsByEsnAndHouseholdId(esn, householdId))) {
      throw new EsnBlockedException();
    }
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
  }
}
