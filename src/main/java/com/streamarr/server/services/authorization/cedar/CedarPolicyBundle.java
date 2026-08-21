package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.AuthorizationEngine;
import com.cedarpolicy.model.ValidationRequest;
import com.cedarpolicy.model.exception.AuthException;
import com.cedarpolicy.model.policy.Policy;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The schema and policies shipped with the server, validated once at startup. Every policy must
 * carry a unique {@code @id} annotation; policies are re-keyed to it so diagnostics and tests name
 * policies by intent rather than by file position.
 */
@Component
class CedarPolicyBundle {

  static final String SCHEMA_LOCATION = "classpath:authorization/streamarr.cedarschema";
  static final String POLICY_PATTERN = "classpath*:authorization/policies/*.cedar";
  private static final String ID_ANNOTATION = "id";

  private final Schema schema;
  private final PolicySet policies;

  @Autowired
  CedarPolicyBundle(AuthorizationEngine engine) {
    this(engine, new PathMatchingResourcePatternResolver());
  }

  CedarPolicyBundle(AuthorizationEngine engine, ResourcePatternResolver resources) {
    this(engine, readSchema(resources), readPolicySources(resources));
  }

  CedarPolicyBundle(AuthorizationEngine engine, String schemaText, List<String> policySources) {
    try {
      schema = Schema.parse(Schema.JsonOrCedar.Cedar, schemaText);
      policies = new PolicySet(rekeyedPolicies(policySources));
      var validation = engine.validate(new ValidationRequest(schema, policies));
      if (!validation.validationPassed()) {
        throw new CedarBundleException("Cedar policies failed schema validation: " + validation);
      }
    } catch (AuthException e) {
      throw new CedarBundleException("Cedar bundle could not be parsed", e);
    }
  }

  Schema schema() {
    return schema;
  }

  PolicySet policies() {
    return policies;
  }

  private static Set<Policy> rekeyedPolicies(List<String> policySources) throws AuthException {
    var ids = new HashSet<String>();
    var rekeyed = new HashSet<Policy>();
    for (var source : policySources) {
      for (var policy : PolicySet.parsePolicies(source).policies) {
        var id = policy.getAnnotation(ID_ANNOTATION);
        if (id == null || id.isBlank()) {
          throw new CedarBundleException(
              "Every Cedar policy needs a unique @id annotation; missing on: "
                  + policy.getSource());
        }
        if (!ids.add(id)) {
          throw new CedarBundleException("Duplicate Cedar policy @id: " + id);
        }
        rekeyed.add(new Policy(policy.getSource(), id));
      }
    }
    if (rekeyed.isEmpty()) {
      throw new CedarBundleException("Cedar bundle must contain at least one policy");
    }
    return rekeyed;
  }

  private static String readSchema(ResourcePatternResolver resources) {
    return read(resources.getResource(SCHEMA_LOCATION));
  }

  private static List<String> readPolicySources(ResourcePatternResolver resources) {
    try {
      return Arrays.stream(resources.getResources(POLICY_PATTERN))
          .sorted(Comparator.comparing(Resource::getFilename))
          .map(CedarPolicyBundle::read)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Cedar policies could not be listed", e);
    }
  }

  private static String read(Resource resource) {
    try {
      return resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cedar resource could not be read: " + resource, e);
    }
  }
}
