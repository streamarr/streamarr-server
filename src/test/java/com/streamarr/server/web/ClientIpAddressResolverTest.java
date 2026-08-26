package com.streamarr.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("UnitTest")
@DisplayName("Client IP Address Resolver Tests")
class ClientIpAddressResolverTest {

  @Test
  @DisplayName("Should resolve the address exposed by the trusted-proxy-aware request")
  void shouldResolveAddressExposedByTrustedProxyAwareRequest() {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("192.0.2.30");

    assertThat(new ClientIpAddressResolver(request).resolve()).isEqualTo("192.0.2.30");
  }
}
