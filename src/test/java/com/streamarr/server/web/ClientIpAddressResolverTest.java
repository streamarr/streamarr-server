package com.streamarr.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.streamarr.server.support.LogCapture;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

  @ParameterizedTest(name = "remoteAddress={0}")
  @MethodSource("remoteAddressesThatAreNotIps")
  @DisplayName(
      "Should journal the unspecified address without logging the remote address when it is not an"
          + " IP")
  void shouldJournalUnspecifiedAddressWithoutLoggingRemoteAddressWhenItIsNotAnIp(
      String remoteAddress, String fragmentThatMustStayOutOfLogs) {
    try (var logs = LogCapture.forClass(ClientIpAddressNormalizer.class)) {
      assertThat(resolve(remoteAddress)).isEqualTo("0.0.0.0");

      assertThat(logs.renderedEvents())
          .isNotEmpty()
          .allSatisfy(event -> assertThat(event).doesNotContain(fragmentThatMustStayOutOfLogs));
    }
  }

  // Each fragment survives sanitizing the control characters out of its address, so a partially
  // cleaned echo of the attacker-controlled input still fails the redaction check.
  private static Stream<Arguments> remoteAddressesThatAreNotIps() {
    return Stream.of(
        arguments("not-an-ip.invalid", "not-an-ip"),
        arguments("fe80::1%en0\r\nforged", "forged"),
        arguments("fe80::1%en0\0", "fe80::1"));
  }

  @Test
  @DisplayName("Should journal the unspecified address when the request has no remote address")
  void shouldJournalUnspecifiedAddressWhenRequestHasNoRemoteAddress() {
    assertThat(resolve(null)).isEqualTo("0.0.0.0");
  }

  private static String resolve(String remoteAddress) {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr(remoteAddress);
    return new ClientIpAddressResolver(request, new ClientIpAddressNormalizer()).resolve();
  }
}
