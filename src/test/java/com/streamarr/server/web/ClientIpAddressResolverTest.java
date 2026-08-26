package com.streamarr.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.streamarr.server.support.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("UnitTest")
@DisplayName("Client IP Address Resolver Tests")
class ClientIpAddressResolverTest {

  @Test
  @DisplayName("Should return the remote address when it is an IPv4 literal")
  void shouldReturnRemoteAddressWhenItIsAnIpv4Literal() {
    assertThat(resolve("192.0.2.30")).isEqualTo("192.0.2.30");
  }

  @Test
  @DisplayName("Should drop the zone identifier when the remote address is link-local IPv6")
  void shouldDropZoneIdentifierWhenRemoteAddressIsLinkLocalIpv6() {
    assertThat(resolve("fe80::1%en0")).isEqualTo("fe80:0:0:0:0:0:0:1");
  }

  @Test
  @DisplayName("Should unwrap the IPv4 address when the remote address is IPv4-mapped IPv6")
  void shouldUnwrapIpv4WhenRemoteAddressIsIpv4MappedIpv6() {
    assertThat(resolve("::ffff:192.0.2.30")).isEqualTo("192.0.2.30");
  }

  @Test
  @DisplayName(
      "Should journal the unspecified address and warn when the remote address is not an IP")
  void shouldJournalUnspecifiedAddressAndWarnWhenRemoteAddressIsNotAnIp() {
    try (var logs = LogCapture.forClass(ClientIpAddressResolver.class)) {
      assertThat(resolve("unknown")).isEqualTo("0.0.0.0");

      assertThat(logs.events())
          .anyMatch(
              event ->
                  event.getLevel() == Level.WARN
                      && event.getFormattedMessage().contains("unknown"));
    }
  }

  @Test
  @DisplayName("Should journal the unspecified address when the request has no remote address")
  void shouldJournalUnspecifiedAddressWhenRequestHasNoRemoteAddress() {
    assertThat(resolve(null)).isEqualTo("0.0.0.0");
  }

  private static String resolve(String remoteAddress) {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr(remoteAddress);
    return new ClientIpAddressResolver(request).resolve();
  }
}
