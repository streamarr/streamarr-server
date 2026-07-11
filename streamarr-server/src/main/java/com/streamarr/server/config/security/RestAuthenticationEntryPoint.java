package com.streamarr.server.config.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * The HTTP layer of the refresh-and-retry contract: EXPIRED_TOKEN tells clients to refresh and
 * replay; INVALID_TOKEN and AUTHENTICATION_REQUIRED route to login.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) {
    var code = codeFor(exception);

    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    try {
      response
          .getWriter()
          .write("{\"code\":\"" + code + "\",\"message\":\"Authentication is required.\"}");
    } catch (IOException _) {
      // The status code alone still carries the contract.
    }
  }

  private static String codeFor(AuthenticationException exception) {
    if (!(exception instanceof OAuth2AuthenticationException oauthException)) {
      return "AUTHENTICATION_REQUIRED";
    }

    return mentionsExpiry(oauthException) ? "EXPIRED_TOKEN" : "INVALID_TOKEN";
  }

  private static boolean mentionsExpiry(OAuth2AuthenticationException exception) {
    if (descriptionMentionsExpiry(exception.getError().getDescription())) {
      return true;
    }

    if (exception.getCause() instanceof JwtValidationException validationException) {
      return validationException.getErrors().stream()
          .anyMatch(error -> descriptionMentionsExpiry(error.getDescription()));
    }

    return descriptionMentionsExpiry(exception.getMessage());
  }

  private static boolean descriptionMentionsExpiry(String description) {
    return description != null && description.toLowerCase(Locale.ROOT).contains("expired");
  }
}
