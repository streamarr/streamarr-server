package com.streamarr.server.config.http;

import java.net.http.HttpClient;

/**
 * Throwaway benchmark integration: an {@link HttpClient} that wraps the {@code tmdb} client and
 * forwards every call to it. Code that needs the underlying Methanol client (its interceptors and
 * timeouts) unwraps through this interface.
 */
public interface PocDelegatingHttpClient {

  HttpClient delegate();

  /** Returns {@code client} with every delegating wrapper removed. */
  static HttpClient unwrap(HttpClient client) {
    var current = client;
    while (current instanceof PocDelegatingHttpClient wrapper) {
      current = wrapper.delegate();
    }

    return current;
  }
}
