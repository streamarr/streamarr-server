package com.streamarr.server.config.security;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

  private static final String DENIAL_METRIC = "streamarr.security.denials";

  private final MeterRegistry meterRegistry;

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) {
    meterRegistry
        .counter(DENIAL_METRIC, "reason", exception instanceof CsrfException ? "csrf" : "access")
        .increment();
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    var body =
        exception instanceof CsrfException
            ? "{\"code\":\"CSRF_TOKEN_REQUIRED\",\"message\":\"The CSRF token is missing or invalid.\"}"
            : "{\"code\":\"FORBIDDEN\",\"message\":\"You do not have access to this resource.\"}";
    try {
      response.getWriter().write(body);
    } catch (IOException _) {
      // The status code alone still carries the contract.
    }
  }
}
