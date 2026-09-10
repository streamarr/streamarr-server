package com.streamarr.server.web;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ClientIpAddressNormalizer {

  private static final String UNSPECIFIED_ADDRESS = "0.0.0.0";
  private static final Pattern ZONE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_.~-]+");

  /**
   * Parses an IP literal without DNS lookup, removing IPv6 brackets and zone identifiers. Zone
   * identifiers must be nonempty ASCII letters, digits, underscores, dots, tildes, or hyphens; they
   * are not resolved against local interfaces. Missing or invalid addresses become {@code 0.0.0.0}.
   */
  public String normalize(String remoteAddress) {
    if (remoteAddress == null) {
      return UNSPECIFIED_ADDRESS;
    }

    try {
      var address = InetAddress.ofLiteral(withoutZone(remoteAddress));
      if (remoteAddress.indexOf('%') >= 0 && !(address instanceof Inet6Address)) {
        throw new IllegalArgumentException("Only IPv6 addresses can have a zone identifier");
      }

      return address.getHostAddress();
    } catch (IllegalArgumentException _) {
      log.warn("Client address is not an IP literal; journaling {}", UNSPECIFIED_ADDRESS);
      return UNSPECIFIED_ADDRESS;
    }
  }

  private static String withoutZone(String address) {
    var zone = address.indexOf('%');
    if (zone < 0) {
      return address;
    }

    var bracketed = address.startsWith("[");
    if (bracketed != address.endsWith("]")) {
      throw new IllegalArgumentException("Unmatched IPv6 brackets");
    }

    var zoneEnd = bracketed ? address.length() - 1 : address.length();
    if (!ZONE_IDENTIFIER.matcher(address.substring(zone + 1, zoneEnd)).matches()) {
      throw new IllegalArgumentException("Invalid IPv6 zone identifier");
    }

    return address.substring(0, zone) + (bracketed ? "]" : "");
  }
}
