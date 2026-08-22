package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.Value;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The entities one decision is evaluated against: the principal with its facts, and the resource.
 */
final class EntitySlice {

  private final EntityUID principal;
  private final EntityUID resource;
  private final Map<String, Value> principalAttributes = new LinkedHashMap<>();

  EntitySlice(EntityUID principal, EntityUID resource) {
    this.principal = principal;
    this.resource = resource;
  }

  void principalAttribute(String name, Value value) {
    principalAttributes.put(name, value);
  }

  EntityUID principal() {
    return principal;
  }

  List<Entity> entities() {
    return List.of(
        new Entity(principal, Map.copyOf(principalAttributes), Set.of()), new Entity(resource));
  }
}
