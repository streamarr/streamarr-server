package com.streamarr.server.domain.language;

import java.util.Optional;
import lombok.NonNull;

/** How one change to a language override treats one of the override's fields. */
public sealed interface OverrideFieldChange<T> {

  /** Leaves the stored value as it is. */
  record Keep<T>() implements OverrideFieldChange<T> {}

  /** Stores the value. An empty value makes the field inherit from the next wider level. */
  record Replace<T>(@NonNull Optional<T> value) implements OverrideFieldChange<T> {}

  static <T> OverrideFieldChange<T> keep() {
    return new Keep<>();
  }

  static <T> OverrideFieldChange<T> replaceWith(@NonNull Optional<T> value) {
    return new Replace<>(value);
  }

  /** The field's value after the change, from the value stored before it. */
  default Optional<T> applyTo(@NonNull Optional<T> stored) {
    return switch (this) {
      case Keep<T> _ -> stored;
      case Replace<T>(var value) -> value;
    };
  }
}
