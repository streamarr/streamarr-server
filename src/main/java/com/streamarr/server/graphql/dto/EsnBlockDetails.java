package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.EsnBlock;
import java.util.UUID;
import lombok.Builder;

@Builder
public record EsnBlockDetails(UUID id, String esn, UUID householdId, String reason) {

  public static EsnBlockDetails from(EsnBlock block) {
    return EsnBlockDetails.builder()
        .id(block.getId())
        .esn(block.getEsn())
        .householdId(block.getHouseholdId())
        .reason(block.getReason())
        .build();
  }
}
