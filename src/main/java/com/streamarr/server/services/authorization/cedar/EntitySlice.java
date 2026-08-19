package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.Value;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The entities one decision is evaluated against: the principal and the resource with the facts
 * contributors loaded, plus any entities those facts reference.
 */
final class EntitySlice {

  private final EntityUID principal;
  private final EntityUID resource;
  private final Map<String, Value> principalAttributes = new LinkedHashMap<>();
  private final Map<String, Value> resourceAttributes = new LinkedHashMap<>();
  private final Set<EntityUID> referenced = new LinkedHashSet<>();

  EntitySlice(EntityUID principal, EntityUID resource) {
    this.principal = principal;
    this.resource = resource;
  }

  void principalAttribute(String name, Value value) {
    principalAttributes.put(name, value);
  }

  void resourceAttribute(String name, Value value) {
    resourceAttributes.put(name, value);
  }

  /** A referenced entity with no facts of its own (a Household the principal belongs to, …). */
  void reference(EntityUID uid) {
    referenced.add(uid);
  }

  EntityUID principal() {
    return principal;
  }

  List<Entity> entities() {
    var entities = new ArrayList<Entity>();
    var emitted = new HashSet<>(List.of(principal, resource));
    entities.add(new Entity(principal, Map.copyOf(principalAttributes), Set.of()));
    entities.add(new Entity(resource, Map.copyOf(resourceAttributes), Set.of()));
    for (var uid : referenced) {
      if (emitted.add(uid)) {
        entities.add(new Entity(uid));
      }
    }
    return List.copyOf(entities);
  }
}
