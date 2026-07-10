package com.streamarr.server.config.security;

import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

final class SecurityRequestMatchers {

  static final PathPatternRequestMatcher STREAM_PATHS =
      PathPatternRequestMatcher.withDefaults().matcher("/api/stream/**");

  private SecurityRequestMatchers() {}
}
