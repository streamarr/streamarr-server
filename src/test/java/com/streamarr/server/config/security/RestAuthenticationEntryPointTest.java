package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;

@Tag("UnitTest")
@DisplayName("Rest Authentication Entry Point Tests")
class RestAuthenticationEntryPointTest {

  private final RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint();

  @Test
  @DisplayName("Should report expired token when only exception message mentions expiry")
  void shouldReportExpiredTokenWhenOnlyExceptionMessageMentionsExpiry()
      throws UnsupportedEncodingException {
    var response = new MockHttpServletResponse();
    var exception =
        new OAuth2AuthenticationException(
            new OAuth2Error("invalid_token"), "Jwt expired at 2026-01-01T00:00:00Z");

    entryPoint.commence(new MockHttpServletRequest(), response, exception);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
    assertThat(response.getContentAsString())
        .isEqualTo("{\"code\":\"EXPIRED_TOKEN\",\"message\":\"Authentication is required.\"}");
  }

  @Test
  @DisplayName("Should report expired token when nested validation error mentions expiry")
  void shouldReportExpiredTokenWhenNestedValidationErrorMentionsExpiry()
      throws UnsupportedEncodingException {
    var response = new MockHttpServletResponse();
    var validation =
        new JwtValidationException(
            "An error occurred while attempting to decode the Jwt",
            List.of(new OAuth2Error("invalid_token", "Jwt expired at 2026-01-01T00:00:00Z", null)));
    var exception = new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), validation);

    entryPoint.commence(new MockHttpServletRequest(), response, exception);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
    assertThat(response.getContentAsString())
        .isEqualTo("{\"code\":\"EXPIRED_TOKEN\",\"message\":\"Authentication is required.\"}");
  }

  @Test
  @DisplayName("Should report invalid token when token exception does not mention expiry")
  void shouldReportInvalidTokenWhenTokenExceptionDoesNotMentionExpiry()
      throws UnsupportedEncodingException {
    var response = new MockHttpServletResponse();
    var exception =
        new OAuth2AuthenticationException(
            new OAuth2Error("invalid_token", "Jwt signature invalid", null));

    entryPoint.commence(new MockHttpServletRequest(), response, exception);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
    assertThat(response.getContentAsString())
        .isEqualTo("{\"code\":\"INVALID_TOKEN\",\"message\":\"Authentication is required.\"}");
  }

  @Test
  @DisplayName("Should report authentication required when exception not token based")
  void shouldReportAuthenticationRequiredWhenExceptionNotTokenBased()
      throws UnsupportedEncodingException {
    var response = new MockHttpServletResponse();

    entryPoint.commence(
        new MockHttpServletRequest(),
        response,
        new InsufficientAuthenticationException("no credentials"));

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Authentication is required.\"}");
  }

  @Test
  @DisplayName("Should keep status contract when response writer fails")
  void shouldKeepStatusContractWhenResponseWriterFails() {
    var response = new WriterlessResponse();
    var exception = new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));

    entryPoint.commence(new MockHttpServletRequest(), response, exception);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
  }

  @Test
  @DisplayName("Should propagate unexpected response writer runtime failures")
  void shouldPropagateUnexpectedResponseWriterRuntimeFailures() {
    var request = new MockHttpServletRequest();
    var response = new RuntimeFailingResponse();
    var exception = new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));

    assertThatThrownBy(() -> entryPoint.commence(request, response, exception))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("response already committed");
  }

  static class WriterlessResponse extends MockHttpServletResponse {
    @Override
    public PrintWriter getWriter() throws UnsupportedEncodingException {
      throw new UnsupportedEncodingException("client went away");
    }

    @Override
    public ServletOutputStream getOutputStream() {
      throw new IllegalStateException("client went away");
    }
  }

  static class RuntimeFailingResponse extends MockHttpServletResponse {
    @Override
    public PrintWriter getWriter() {
      throw new IllegalStateException("response already committed");
    }
  }
}
