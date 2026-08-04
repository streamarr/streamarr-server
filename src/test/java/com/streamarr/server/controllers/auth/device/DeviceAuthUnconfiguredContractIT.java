package com.streamarr.server.controllers.auth.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@SpringBootTest(properties = "streamarr.base-url=")
@DisplayName("Unconfigured Device Auth Contract Integration Tests")
class DeviceAuthUnconfiguredContractIT extends AbstractIntegrationTest {

  private static final Path FIXTURES = Path.of("docs/contracts/device-pairing/v1");

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName("Should advertise disabled pairing and reject issuance with the pinned body")
  void shouldAdvertiseDisabledPairingAndRejectIssuanceWithPinnedBody() throws Exception {
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
                        .content("{\"deviceName\": \"Apple TV\"}"))
                .andExpect(status().isServiceUnavailable())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(errorBody).isEqualTo(fixture("not-configured-error.json"));
  }

  private JsonNode fixture(String fixtureName) {
    try {
      return objectMapper.readTree(Files.readString(FIXTURES.resolve(fixtureName)));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Missing contract fixture: " + fixtureName, e);
    }
  }
}
