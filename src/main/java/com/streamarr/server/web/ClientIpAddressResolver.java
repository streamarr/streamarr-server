package com.streamarr.server.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The client address journaled with every credential attempt (ADR 0028). It is observational only,
 * so a value PostgreSQL cannot store as {@code inet} degrades to the unspecified address instead of
 * refusing the request: link-local IPv6 peers arrive with a zone suffix, and forwarded headers
 * (when {@code server.forward-headers-strategy} is enabled) may carry {@code unknown} or an
 * obfuscated token. Forwarded headers are never parsed here; the container rewrites {@link
 * HttpServletRequest#getRemoteAddr()} under that setting.
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
