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
  // Clients replay an unsafe request once on this code. Never reuse it for a denial that can occur
  // after controller side effects; here it is reserved for the CsrfFilter's pre-dispatch failure.
  private static final String CSRF_REJECTION_CODE = "CSRF_TOKEN_REQUIRED";
  private static final String CSRF_DENIAL_BODY =
      "{\"code\":\""
          + CSRF_REJECTION_CODE
          + "\",\"message\":\"The CSRF token is missing or invalid.\"}";
  private static final String ACCESS_DENIAL_BODY =
      "{\"code\":\"FORBIDDEN\",\"message\":\"You do not have access to this resource.\"}";

  private final MeterRegistry meterRegistry;

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) {
    var csrfDenied = exception instanceof CsrfException;
    meterRegistry.counter(DENIAL_METRIC, "reason", csrfDenied ? "csrf" : "access").increment();
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    var body = csrfDenied ? CSRF_DENIAL_BODY : ACCESS_DENIAL_BODY;
    try {
      response.getWriter().write(body);
    } catch (IOException _) {
      // The status code alone still carries the contract.
    }
  }
}
