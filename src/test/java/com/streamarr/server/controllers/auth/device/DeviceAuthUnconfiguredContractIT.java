package com.streamarr.server.controllers.auth.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@SpringBootTest(properties = "streamarr.base-url=")
@DisplayName("Unconfigured Device Auth Contract Integration Tests")
class DeviceAuthUnconfiguredContractIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName(
      "Should advertise disabled pairing and reject issuance when the base URL is unconfigured")
  void shouldAdvertiseDisabledPairingAndRejectIssuanceWhenBaseUrlUnconfigured() throws Exception {
    var statusBody =
        objectMapper.readTree(
            mockMvc
                .perform(get("/api/auth/status"))
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(statusBody.get("devicePairingEnabled").asBoolean()).isFalse();

    var errorBody =
        objectMapper.readTree(
            mockMvc
                .perform(
                    post("/api/auth/device/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceName\": \"Apple TV\", \"esn\": \"esn-1\"}"))
                .andExpect(status().isServiceUnavailable())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(errorBody.size()).isEqualTo(2);
    assertThat(errorBody.get("code").asString()).isEqualTo("DEVICE_PAIRING_NOT_CONFIGURED");
    assertThat(errorBody.get("message").asString())
        .isEqualTo("Device pairing requires a configured canonical base URL.");
  }
}
