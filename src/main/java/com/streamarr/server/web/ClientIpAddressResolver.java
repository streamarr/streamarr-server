package com.streamarr.server.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
@RequiredArgsConstructor
public class ClientIpAddressResolver {

  private final HttpServletRequest request;

  public String resolve() {
    return request.getRemoteAddr();
  }
}
