package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

@Tag("UnitTest")
@DisplayName("Rest Access Denied Handler Tests")
class RestAccessDeniedHandlerTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final RestAccessDeniedHandler handler = new RestAccessDeniedHandler(meterRegistry);

  @Test
  @DisplayName("Should write forbidden code when access denied")
  void shouldWriteForbiddenCodeWhenAccessDenied() throws UnsupportedEncodingException {
    var response = new MockHttpServletResponse();

    handler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\":\"FORBIDDEN\",\"message\":\"You do not have access to this resource.\"}");
  }

  @Test
  @DisplayName("Should write csrf token required code when csrf denied")
  void shouldWriteCsrfTokenRequiredCodeWhenCsrfDenied() throws UnsupportedEncodingException {
    var response = new MockHttpServletResponse();

    handler.handle(
        new MockHttpServletRequest(), response, new MissingCsrfTokenException("token missing"));

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"code\":\"CSRF_TOKEN_REQUIRED\",\"message\":\"The CSRF token is missing or invalid.\"}");
  }

  @Test
  @DisplayName("Should count csrf denials for operational visibility")
  void shouldCountCsrfDenialsForOperationalVisibility() {
    handler.handle(
        new MockHttpServletRequest(),
        new MockHttpServletResponse(),
        new MissingCsrfTokenException("token missing"));

    assertThat(
            meterRegistry.get("streamarr.security.denials").tag("reason", "csrf").counter().count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Should count access denials when access is denied")
  void shouldCountAccessDenialsWhenAccessIsDenied() {
    handler.handle(
        new MockHttpServletRequest(),
        new MockHttpServletResponse(),
        new AccessDeniedException("denied"));

    assertThat(
            meterRegistry
                .get("streamarr.security.denials")
                .tag("reason", "access")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Should keep status contract when response writer fails")
  void shouldKeepStatusContractWhenResponseWriterFails() {
    var response = new RestAuthenticationEntryPointTest.WriterlessResponse();

    handler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
  }

  @Test
  @DisplayName("Should propagate unexpected response writer runtime failures")
  void shouldPropagateUnexpectedResponseWriterRuntimeFailures() {
    var request = new MockHttpServletRequest();
    var response = new RestAuthenticationEntryPointTest.RuntimeFailingResponse();
    var exception = new AccessDeniedException("denied");

    assertThatThrownBy(() -> handler.handle(request, response, exception))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("response already committed");
  }
}
