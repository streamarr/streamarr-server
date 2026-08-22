package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.EsnBlock;
import java.util.UUID;
import lombok.Builder;

@Builder
public record EsnBlockView(UUID id, String esn, UUID householdId, String reason) {

  public static EsnBlockView from(EsnBlock block) {
    return EsnBlockView.builder()
        .id(block.getId())
        .esn(block.getEsn())
        .householdId(block.getHouseholdId())
        .reason(block.getReason())
        .build();
  }
}
