package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import java.util.UUID;
import lombok.Builder;

@Builder
public record DeviceRegistrationView(
    UUID id,
    String esn,
    String displayName,
    UUID householdId,
    UUID authorizingAccountId,
    DeviceRegistrationStatus status,
    String pairedAt) {

  public static DeviceRegistrationView from(DeviceRegistration registration) {
    return DeviceRegistrationView.builder()
        .id(registration.getId())
        .esn(registration.getEsn())
        .displayName(registration.getDisplayName())
        .householdId(registration.getHouseholdId())
        .authorizingAccountId(registration.getAuthorizingAccountId())
        .status(registration.getStatus())
        .pairedAt(registration.getCreatedOn().toString())
        .build();
  }
}
