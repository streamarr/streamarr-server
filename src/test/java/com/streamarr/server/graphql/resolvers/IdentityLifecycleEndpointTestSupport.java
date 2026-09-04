package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.AuthTestSupport;
import java.util.Map;
import lombok.Builder;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

abstract class IdentityLifecycleEndpointTestSupport extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired AuthTestSupport authTestSupport;
  @Autowired DSLContext dsl;

  AuthTestSupport.TestIdentity admin;
  AuthTestSupport.TestIdentity host;

  @BeforeEach
  void setUpIdentityLifecycleFixtures() {
    admin = authTestSupport.createAdminIdentity();
    host = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDownIdentityLifecycleFixtures() {
    dsl.deleteFrom(SERVER_BOOTSTRAP).execute();
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    authTestSupport.deleteIdentity(host);
    authTestSupport.deleteIdentity(admin);
  }

  ResultActions graphql(String bearer, String query) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content(objectMapper.writeValueAsString(Map.of("query", query))));
  }

  static String lifecycleMutation(String invocation, String resource) {
    var resourceSelection =
        switch (resource) {
          case "account", "profile" -> resource + " { id }";
          default -> resource;
        };
    return """
        mutation { %s {
          %s
          userErrors { __typename ... on InputMutationError { message inputPath } }
        } }
        """
        .formatted(invocation, resourceSelection);
  }

  @Builder
  record MalformedLifecycleIdCase(
      String operation, String resource, String inputPath, String mutationTemplate) {

    @Override
    public String toString() {
      return operation + "." + inputPath;
    }
  }
}
