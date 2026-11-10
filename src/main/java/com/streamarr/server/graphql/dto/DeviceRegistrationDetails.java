package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.services.identity.RegistrationAuthorizer;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;

@Builder
public record DeviceRegistrationDetails(
    UUID id,
    String esn,
    String displayName,
    UUID householdId,
    UUID authorizingAccountId,
    DeviceRegistrationStatus status,
    String pairedAt) {

  public static DeviceRegistrationDetails from(DeviceRegistration registration) {
    return DeviceRegistrationDetails.builder()
        .id(registration.getId())
        .esn(registration.getEsn())
        .displayName(registration.getDisplayName())
        .householdId(registration.getHouseholdId())
        .authorizingAccountId(registration.getAuthorizingAccountId())
        .status(registration.getStatus())
        .pairedAt(registration.getCreatedOn().toString())
        .build();
  }

  /** Who linked the Device, in its Household; absent once either is gone. */
  public Optional<RegistrationAuthorizer> authorizer() {
    if (householdId == null || authorizingAccountId == null) {
      return Optional.empty();
    }

    return Optional.of(new RegistrationAuthorizer(householdId, authorizingAccountId));
  }
}
