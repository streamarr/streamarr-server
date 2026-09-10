package com.streamarr.server.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Reads the container's client address after configured trusted proxy handling. */
@Component
@RequiredArgsConstructor
public class ClientIpAddressResolver {

  private final HttpServletRequest request;
  private final ClientIpAddressNormalizer normalizer;

  public String resolve() {
    return normalizer.normalize(request.getRemoteAddr());
  }
}
