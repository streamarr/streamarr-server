package com.streamarr.server.config.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlaybackTokenQueryRedactionFilter extends OncePerRequestFilter {

  private static final PathPatternRequestMatcher STREAM_PATHS =
      PathPatternRequestMatcher.withDefaults().matcher("/api/stream/**");

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !STREAM_PATHS.matches(request) || request.getQueryString() == null;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    filterChain.doFilter(new RedactedQueryRequest(request), response);
  }

  private static String redact(String queryString) {
    var redacted =
        Arrays.stream(queryString.split("&", -1))
            .filter(parameter -> !isPlaybackToken(parameter))
            .collect(Collectors.joining("&"));
    return redacted.isEmpty() ? null : redacted;
  }

  private static boolean isPlaybackToken(String parameter) {
    var equals = parameter.indexOf('=');
    var encodedName = equals >= 0 ? parameter.substring(0, equals) : parameter;

    try {
      return "t".equals(URLDecoder.decode(encodedName, StandardCharsets.UTF_8));
    } catch (IllegalArgumentException _) {
      return false;
    }
  }

  private static final class RedactedQueryRequest extends HttpServletRequestWrapper {

    private RedactedQueryRequest(HttpServletRequest request) {
      super(request);
    }

    @Override
    public String getQueryString() {
      return redact(super.getQueryString());
    }
  }
}
