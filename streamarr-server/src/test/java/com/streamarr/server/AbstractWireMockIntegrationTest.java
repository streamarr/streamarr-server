package com.streamarr.server;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ResourceLock("WireMock")
public abstract class AbstractWireMockIntegrationTest extends AbstractIntegrationTest {

  protected static final WireMockServer wireMock =
      new WireMockServer(wireMockConfig().dynamicPort());

  static {
    wireMock.start();
  }

  @DynamicPropertySource
  static void configureWireMock(DynamicPropertyRegistry registry) {
    registry.add("tmdb.api.base-url", wireMock::baseUrl);
    registry.add("tmdb.image.base-url", wireMock::baseUrl);
    registry.add("tmdb.api.token", () -> "test-api-token");
  }
}
