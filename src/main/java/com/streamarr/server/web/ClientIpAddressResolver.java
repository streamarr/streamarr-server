package com.streamarr.server.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the client address for credential journaling (ADR 0028). IPv6 zone suffixes are removed
 * before parsing; missing or invalid addresses become {@code 0.0.0.0}. With trusted proxy handling
 * enabled, the servlet request supplies the forwarded address through {@link
 * HttpServletRequest#getRemoteAddr()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClientIpAddressResolver {

  static final String UNSPECIFIED_ADDRESS = "0.0.0.0";

  private final HttpServletRequest request;

  public String resolve() {
    var remoteAddress = request.getRemoteAddr();
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
