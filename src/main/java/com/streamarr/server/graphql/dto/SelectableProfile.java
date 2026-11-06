package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.services.identity.IdentityQueryService;
import java.util.UUID;
import lombok.Builder;

@Builder
public record SelectableProfile(
    UUID id,
    String name,
    String picture,
    ProfileKind kind,
    boolean personal,
    boolean pinConfigured,
    boolean locked,
    boolean selected) {

  public static SelectableProfile from(IdentityQueryService.SelectableProfileDetails details) {
    return SelectableProfile.builder()
        .id(details.id())
        .name(details.name())
        .picture(details.picture().orElse(null))
        .kind(details.kind())
        .personal(details.personal())
        .pinConfigured(details.pinConfigured())
        .locked(details.locked())
        .selected(details.selected())
        .build();
  }
}
