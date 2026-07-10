package com.streamarr.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the actuator web allowlist: health stays reachable for probes, while heap dumps — which
 * contain plaintext secrets per ADR 0016's accepted JVM memory lifecycle — never leave the JVM.
 */
@Tag("IntegrationTest")
@DisplayName("Actuator Exposure Integration Tests")
class ActuatorExposureIT extends AbstractIntegrationTest {

  @Autowired private WebApplicationContext webApplicationContext;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  @Test
  @DisplayName("Should serve health when actuator queried")
  void shouldServeHealthWhenActuatorQueried() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("Should not serve heap dump when actuator queried")
  void shouldNotServeHeapDumpWhenActuatorQueried() throws Exception {
    mockMvc.perform(get("/actuator/heapdump")).andExpect(status().isNotFound());
  }
}
