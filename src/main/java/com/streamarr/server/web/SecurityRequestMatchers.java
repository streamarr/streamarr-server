package com.streamarr.server.web;

import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

public final class SecurityRequestMatchers {

  public static final PathPatternRequestMatcher STREAM_PATHS =
      PathPatternRequestMatcher.withDefaults().matcher("/api/stream/**");

  private SecurityRequestMatchers() {}
}
