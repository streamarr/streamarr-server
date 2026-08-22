package com.streamarr.server.controllers.auth.device;

import static com.streamarr.server.jooq.generated.Tables.SERVER_BOOTSTRAP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.jooq.generated.tables.records.ServerBootstrapRecord;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import org.jooq.DSLContext;
import org.jooq.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * A code issued before the first Account exists could never be approved — the approval screen needs
 * a signed-in approver — so issuance refuses with a code the TV can turn into "finish setup on the
 * web first" guidance. Isolated: it briefly removes the shared database's bootstrap claim and
 * restores the exact rows afterwards.
 */
@Tag("IntegrationTest")
@SpringBootTest
@Isolated
@DisplayName("Setup Guard Device Auth Contract Integration Tests")
class DeviceAuthSetupGuardContractIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private DSLContext dsl;

  @Autowired private DeviceAuthorizationRepository authorizationRepository;

  private Result<ServerBootstrapRecord> claimedRows;

  @BeforeEach
  void unclaimBootstrap() {
    claimedRows = dsl.selectFrom(SERVER_BOOTSTRAP).fetch();
    dsl.deleteFrom(SERVER_BOOTSTRAP).execute();
  }

  @AfterEach
  void restoreBootstrap() {
    dsl.batchInsert(claimedRows).execute();
  }

  @Test
  @DisplayName("Should refuse issuing a pairing code when setup is incomplete")
  void shouldRefuseIssuingPairingCodeWhenSetupIncomplete() throws Exception {
    var authorizationsBefore = authorizationRepository.count();
    var errorBody =
        objectMapper.readTree(
            mockMvc
                .perform(
                    post("/api/auth/device/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceName\": \"Apple TV\", \"esn\": \"esn-1\"}"))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(errorBody.get("code").asString()).isEqualTo("SETUP_INCOMPLETE");
    assertThat(errorBody.get("message").asString())
        .isEqualTo("The server has not completed initial setup.");
    assertThat(authorizationRepository.count()).isEqualTo(authorizationsBefore);
  }
}
