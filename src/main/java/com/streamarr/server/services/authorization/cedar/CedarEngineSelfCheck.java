package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.AuthorizationEngine;
import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.Context;
import com.cedarpolicy.model.ValidationRequest;
import com.cedarpolicy.model.entity.Entities;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.exception.AuthException;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.EntityUID;
import java.util.Optional;
import java.util.Set;

/**
 * Proves the Cedar native engine loads and evaluates on this JVM and CPU architecture: validates a
 * minimal schema and policy set, then asks for one decision that must be allowed and one that must
 * be denied. The build runs it as a unit test on developer machines and CI; the container-image
 * verification runs it inside the amd64 and arm64 Paketo images, where a missing or mismatched
 * native library would otherwise surface only on the first real authorization.
 */
public final class CedarEngineSelfCheck {

  static final String SCHEMA =
      """
      namespace Streamarr {
        entity Account;
        entity Server;
        action selfCheck appliesTo { principal: Account, resource: Server };
      }
      """;

  static final String POLICIES =
      """
      @id("self-check-permit")
      permit (
        principal == Streamarr::Account::"self-check",
        action == Streamarr::Action::"selfCheck",
        resource == Streamarr::Server::"streamarr"
      );
      """;

  private static final EntityUID PERMITTED_ACCOUNT = uid("Streamarr::Account", "self-check");
  private static final EntityUID STRANGER_ACCOUNT = uid("Streamarr::Account", "stranger");
  private static final EntityUID SELF_CHECK_ACTION = uid("Streamarr::Action", "selfCheck");
  private static final EntityUID SERVER = uid("Streamarr::Server", "streamarr");

  private final AuthorizationEngine engine = new BasicAuthorizationEngine();

  /** The two decisions the engine produced; both must hold for the check to pass. */
  public record Result(boolean permittedAccountAllowed, boolean strangerAccountDenied) {

    public boolean passed() {
      return permittedAccountAllowed && strangerAccountDenied;
    }
  }

  public Result run() {
    try {
      var schema = Schema.parse(Schema.JsonOrCedar.Cedar, SCHEMA);
      var policies = PolicySet.parsePolicies(POLICIES);
      var validation = engine.validate(new ValidationRequest(schema, policies));
      if (!validation.validationPassed()) {
        throw new CedarSelfCheckException("Cedar self-check policies failed validation");
      }
      return new Result(
          decide(PERMITTED_ACCOUNT, schema, policies), !decide(STRANGER_ACCOUNT, schema, policies));
    } catch (AuthException e) {
      throw new CedarSelfCheckException("Cedar engine could not evaluate the self-check", e);
    }
  }

  private boolean decide(EntityUID principal, Schema schema, PolicySet policies)
      throws AuthException {
    var entities =
        new Entities(
            Set.of(new Entity(principal), new Entity(SELF_CHECK_ACTION), new Entity(SERVER)));
    var request =
        new AuthorizationRequest(
            principal, SELF_CHECK_ACTION, SERVER, new Context(), Optional.of(schema), true);
    var response = engine.isAuthorized(request, policies, entities);
    var success =
        response.success.orElseThrow(
            () -> new CedarSelfCheckException("Cedar self-check evaluation failed: " + response));
    if (!success.getErrors().isEmpty()) {
      throw new CedarSelfCheckException(
          "Cedar self-check evaluation reported diagnostics: " + success.getErrors());
    }
    return success.isAllowed();
  }

  private static EntityUID uid(String type, String id) {
    return EntityUID.parse(type + "::\"" + id + "\"").orElseThrow();
  }
}
