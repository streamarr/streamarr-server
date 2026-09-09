package com.streamarr.server.web;

import java.net.InetAddress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ClientIpAddressNormalizer {

  private static final String UNSPECIFIED_ADDRESS = "0.0.0.0";

  /**
   * Parses an IP literal without DNS lookup, removing any IPv6 zone suffix. Missing or invalid
   * addresses become {@code 0.0.0.0}.
   */
  public String normalize(String remoteAddress) {
    if (remoteAddress == null) {
      return UNSPECIFIED_ADDRESS;
    }

    try {
      return InetAddress.ofLiteral(withoutZone(remoteAddress)).getHostAddress();
    } catch (IllegalArgumentException _) {
      log.warn(
          "Client address {} is not an IP literal; journaling {}",
          remoteAddress,
          UNSPECIFIED_ADDRESS);
      return UNSPECIFIED_ADDRESS;
    }
  }

  private static String withoutZone(String address) {
    var zone = address.indexOf('%');
    if (zone < 0) {
      return address;
    }

    return address.substring(0, zone);
  }
}
