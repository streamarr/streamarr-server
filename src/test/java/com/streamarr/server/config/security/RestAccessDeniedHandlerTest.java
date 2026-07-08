package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Rest Access Denied Handler Tests")
class RestAccessDeniedHandlerTest {

  private final RestAccessDeniedHandler handler = new RestAccessDeniedHandler();

  @Test
  @DisplayName("Should write forbidden code when access denied")
  void shouldWriteForbiddenCodeWhenAccessDenied() throws UnsupportedEncodingException {
    var response = new MockHttpServletResponse();

    handler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    assertThat(response.getContentAsString()).contains("FORBIDDEN");
  }

  @Test
  @DisplayName("Should keep status contract when response writer fails")
  void shouldKeepStatusContractWhenResponseWriterFails() {
    var response = new RestAuthenticationEntryPointTest.WriterlessResponse();

    handler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
  }
}
