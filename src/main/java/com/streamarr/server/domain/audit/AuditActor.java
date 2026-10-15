package com.streamarr.server.domain.audit;

import java.util.UUID;
import lombok.NonNull;

/**
 * Who a transaction's writes name in their audit columns. The database fills the columns from the
 * actor that the transaction names and rejects a write when the transaction names no actor.
 */
public sealed interface AuditActor {

  /** A signed-in account. */
  record Account(@NonNull UUID accountId) implements AuditActor {}

  /** Work that no user requested. */
  record System() implements AuditActor {}

  /** A person's request made without a signed-in account. Its writes leave the actor empty. */
  record RequestWithoutAccount() implements AuditActor {}
}
