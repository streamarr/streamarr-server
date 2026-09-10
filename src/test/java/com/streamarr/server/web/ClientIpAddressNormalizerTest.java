package com.streamarr.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Client IP Address Normalizer Tests")
class ClientIpAddressNormalizerTest {

  private final ClientIpAddressNormalizer normalizer = new ClientIpAddressNormalizer();

  @Test
  @DisplayName("Should remove brackets and scope when an IPv6 literal has both")
  void shouldRemoveBracketsAndScopeWhenIpv6LiteralHasBoth() {
    assertThat(normalizer.normalize("[fe80::1%3]")).isEqualTo("fe80:0:0:0:0:0:0:1");
  }

  @ParameterizedTest
  @CsvSource({
    "192.0.2.30,192.0.2.30",
    "0.0.0.0,0.0.0.0",
    "255.255.255.255,255.255.255.255",
    "2001:db8::1,2001:db8:0:0:0:0:0:1",
    "2001:0DB8:0000:0000:0000:0000:0000:0001,2001:db8:0:0:0:0:0:1",
    "[2001:db8::1],2001:db8:0:0:0:0:0:1",
    "::,0:0:0:0:0:0:0:0",
    "::1,0:0:0:0:0:0:0:1",
    "2001:db8::,2001:db8:0:0:0:0:0:0",
    "1:2:3:4:5:6:7:8,1:2:3:4:5:6:7:8",
    "FFFF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF:FFFF,ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
    "fd12:3456:789a::1,fd12:3456:789a:0:0:0:0:1",
    "ff02::1,ff02:0:0:0:0:0:0:1",
    "::ffff:192.0.2.30,192.0.2.30",
    "::FFFF:C000:021E,192.0.2.30",
    "0:0:0:0:0:ffff:c000:21e,192.0.2.30",
    "[::ffff:192.0.2.30],192.0.2.30",
    "::192.0.2.30,0:0:0:0:0:0:c000:21e",
    "64:ff9b::192.0.2.30,64:ff9b:0:0:0:0:c000:21e",
    "2001:db8::192.0.2.30,2001:db8:0:0:0:0:c000:21e"
  })
  @DisplayName("Should produce a stable numeric address when the input is an IP literal")
  void shouldProduceStableNumericAddressWhenInputIsIpLiteral(
      String remoteAddress, String expectedAddress) {
    assertThat(normalizer.normalize(remoteAddress)).isEqualTo(expectedAddress);
    assertThat(normalizer.normalize(expectedAddress)).isEqualTo(expectedAddress);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "fe80::1%0",
        "fe80::1%3",
        "fe80::1%2147483647",
        "fe80::1%remote-interface",
        "fe80::1%en0",
        "fe80::1%eth0.100",
        "fe80::1%eth_0~1",
        "[fe80::1%remote-interface]",
        "[FE80:0000:0000:0000:0000:0000:0000:0001%3]"
      })
  @DisplayName("Should discard the zone when a scoped IPv6 literal uses a remote interface")
  void shouldDiscardZoneWhenScopedIpv6LiteralUsesRemoteInterface(String remoteAddress) {
    assertThat(normalizer.normalize(remoteAddress)).isEqualTo("fe80:0:0:0:0:0:0:1");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "192.0.2.30%en0",
        "::ffff:192.0.2.30%3",
        "[::ffff:c000:21e%en0]",
        "fe80::1%",
        "[fe80::1%]",
        "fe80::1%3%extra",
        "[fe80::1%3%extra]",
        "fe80::1%3]:443",
        "fe80::1%3/64",
        "fe80::1%3:443",
        "fe80::1%en 0",
        "[fe80::1]%3",
        "[fe80::1%3",
        "fe80::1%3]",
        "[[fe80::1%3]]",
        "[fe80::1%3]:443",
        "[fe80::1%3]/64",
        "fe80::1%eth0,192.0.2.30",
        "fe80::1%eth0;proto=https",
        "fe80::1%eth0?query",
        "fe80::1%eth0#fragment",
        "fe80::1%eth0\t",
        "fe80::1%eth0\r\n",
        "fe80::1%eth0\0",
        "fe80::1%éth0",
        "fe80::gggg%en0",
        "%en0"
      })
  @DisplayName("Should use the unspecified address when a scope suffix is invalid")
  void shouldUseUnspecifiedAddressWhenScopeSuffixIsInvalid(String remoteAddress) {
    assertThat(normalizer.normalize(remoteAddress)).isEqualTo("0.0.0.0");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "localhost",
        "example.invalid",
        "256.0.2.30",
        "192.0.2.-1",
        "192.0.2.30:443",
        "[192.0.2.30]",
        "2001::db8::1",
        "2001:db8::gggg",
        "2001:db8:1:2:3:4:5",
        "1:2:3:4:5:6:7:8:9",
        "12345::1",
        "::ffff:192.0.2.256",
        "[2001:db8::1",
        "2001:db8::1]",
        "[[2001:db8::1]]",
        "[2001:db8::1]:443",
        "2001:db8::1/64",
        "2001:db8::1,192.0.2.30",
        " 2001:db8::1",
        "2001:db8::1 ",
        "http://[2001:db8::1]",
        "for=\"[2001:db8::1]\""
      })
  @DisplayName("Should use the unspecified address when the input is not an IP literal")
  void shouldUseUnspecifiedAddressWhenInputIsNotIpLiteral(String remoteAddress) {
    assertThat(normalizer.normalize(remoteAddress)).isEqualTo("0.0.0.0");
  }
}
