package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeServerSettingsRepository;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.graphql.StreamarrDataFetcherExceptionHandler;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(
    classes = {
      EncodingResolver.class,
      JacksonAutoConfiguration.class,
      StreamarrDataFetcherExceptionHandler.class,
      EncodingResolverTest.FakeServerSettings.class
    })
@DisplayName("Encoding Resolver Tests")
class EncodingResolverTest {

  private static final String SETTING_FIELDS =
      """
      preset
      automatic { h264 hevc av1 }
      """;

  @Autowired private DgsQueryExecutor executor;
  @Autowired private ObjectMapper mapper;
  @Autowired private FakeAuthorizationService authorizationService;
  @Autowired private ServerSettingsService serverSettingsService;

  @TestConfiguration
  static class FakeServerSettings {

    @Bean
    FakeAuthorizationService authorizationService() {
      return new FakeAuthorizationService(
          AuthenticatedIdentityFixture.accountScopedBuilder().build());
    }

    @Bean
    ServerSettingsService serverSettingsService(FakeAuthorizationService authorizationService) {
      return new ServerSettingsService(authorizationService, new FakeServerSettingsRepository());
    }
  }

  @AfterEach
  void restoreAutomatic() {
    authorizationService.allowAll();
    serverSettingsService.changeEncoderPreset(authorizationService.currentIdentity(), "AUTOMATIC");
  }

  @Test
  @DisplayName("Should return Automatic and what it uses per codec when the setting is read")
  void shouldReturnAutomaticAndWhatItUsesPerCodecWhenSettingIsRead() {
    var response = execute("query { encoderPresetSetting { " + SETTING_FIELDS + " } }");

    assertThat(response.has("errors")).isFalse();
    var setting = response.at("/data/encoderPresetSetting");
    assertThat(setting.path("preset").asString()).isEqualTo("AUTOMATIC");
    assertThat(setting.path("automatic"))
        .isEqualTo(
            mapper.valueToTree(Map.of("h264", "FASTER", "hevc", "VERYFAST", "av1", "FASTER")));
  }

  @Test
  @DisplayName("Should save the preset and return it without errors when the change is accepted")
  void shouldSavePresetAndReturnItWithoutErrorsWhenChangeIsAccepted() {
    var payload = change("MEDIUM");

    assertThat(payload.at("/encoderPresetSetting/preset").asString()).isEqualTo("MEDIUM");
    assertThat(payload.at("/encoderPresetSetting/automatic/hevc").asString()).isEqualTo("VERYFAST");
    assertThat(payload.path("userErrors").isArray()).isTrue();
    assertThat(payload.path("userErrors").size()).isZero();
    assertThat(serverSettingsService.resolveEncoderPreset("h264").name()).isEqualTo("MEDIUM");
  }

  @Test
  @DisplayName(
      "Should return the unknown preset error at the preset field when the name is unknown")
  void shouldReturnUnknownPresetErrorAtPresetFieldWhenNameIsUnknown() {
    var payload = change("PLACEBO");

    assertThat(payload.path("encoderPresetSetting").isNull()).isTrue();
    assertThat(payload.path("userErrors").size()).isEqualTo(1);
    var error = payload.at("/userErrors/0");
    assertThat(error.path("__typename").asString()).isEqualTo("UnknownEncoderPresetError");
    assertThat(error.path("message").asString()).isNotBlank();
    assertThat(error.path("inputPath")).isEqualTo(mapper.valueToTree(List.of("preset")));
    assertThat(serverSettingsService.resolveEncoderPreset("h264").name()).isEqualTo("FASTER");
  }

  @Test
  @DisplayName("Should expose a forbidden top-level error when the caller is not a ServerAdmin")
  void shouldExposeForbiddenTopLevelErrorWhenCallerIsNotServerAdmin() {
    authorizationService.denyAll();

    var read = execute("query { encoderPresetSetting { preset } }");
    var change =
        execute(
            """
            mutation { changeEncoderPreset(input: {preset: "MEDIUM"}) {
              encoderPresetSetting { preset } } }
            """);

    assertThat(List.of(read, change))
        .allSatisfy(
            response ->
                assertThat(response.at("/errors/0/extensions/code").asString())
                    .isEqualTo("FORBIDDEN"));
  }

  private JsonNode change(String preset) {
    var response =
        mapper.<JsonNode>valueToTree(
            executor
                .execute(
                    """
                    mutation($input: ChangeEncoderPresetInput!) {
                      changeEncoderPreset(input: $input) {
                        encoderPresetSetting { %s }
                        userErrors {
                          __typename
                          ... on MutationError { message }
                          ... on InputMutationError { inputPath }
                        }
                      }
                    }
                    """
                        .formatted(SETTING_FIELDS),
                    Map.of("input", Map.of("preset", preset)))
                .toSpecification());
    assertThat(response.has("errors")).isFalse();
    return response.at("/data/changeEncoderPreset");
  }

  private JsonNode execute(String document) {
    return mapper.valueToTree(executor.execute(document).toSpecification());
  }
}
