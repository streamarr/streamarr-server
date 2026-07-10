package com.streamarr.server.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@Tag("UnitTest")
@DisplayName("Playback Token Query Redaction Filter Tests")
class PlaybackTokenQueryRedactionFilterTest {

  private final PlaybackTokenQueryRedactionFilter filter = new PlaybackTokenQueryRedactionFilter();

  @Test
  @DisplayName("Should redact the playback token from a stream request query string")
  void shouldRedactPlaybackTokenFromStreamRequestQueryString() throws Exception {
    var observedQuery = redactedQueryOf("quality=auto&t=secret-playback-token&offset=12");

    assertThat(observedQuery).isEqualTo("quality=auto&offset=12");
  }

  @Test
  @DisplayName("Should drop an undecodable parameter from a stream request query string")
  void shouldDropUndecodableParameterFromStreamRequestQueryString() throws Exception {
    // Fail closed: a parameter name the URL decoder rejects cannot be classified, so it is dropped
    // rather than risk surfacing a credential in access logs or traces.
    var observedQuery = redactedQueryOf("%zz=leaked&quality=auto");

    assertThat(observedQuery).isEqualTo("quality=auto");
  }

  private String redactedQueryOf(String queryString) throws Exception {
    var request =
        new MockHttpServletRequest(
            "GET", "/api/stream/21a268d7-6e6a-48fd-a9cc-93bbc1ee85de/master.m3u8");
    request.setQueryString(queryString);

    var observedQuery = new AtomicReference<String>();
    FilterChain terminal =
        (servletRequest, _) ->
            observedQuery.set(((HttpServletRequest) servletRequest).getQueryString());

    filter.doFilter(request, new MockHttpServletResponse(), terminal);
    return observedQuery.get();
  }
}
