package com.streamarr.server.repositories.streaming.trust;

import static com.streamarr.server.jooq.generated.tables.TranscodeCaSigningLease.TRANSCODE_CA_SIGNING_LEASE;

import com.streamarr.server.services.streaming.trust.CertificateAuthorityOperation;
import com.streamarr.server.services.streaming.trust.CertificateSigningLease;
import java.time.OffsetDateTime;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

final class CertificateSigningLeaseGuard {

  private CertificateSigningLeaseGuard() {}

  static Condition identity(CertificateSigningLease lease) {
    return TRANSCODE_CA_SIGNING_LEASE
        .SINGLETON
        .isTrue()
        .and(TRANSCODE_CA_SIGNING_LEASE.OPERATION.eq(generated(lease.operation())))
        .and(TRANSCODE_CA_SIGNING_LEASE.OWNER_ID.eq(lease.ownerId()))
        .and(TRANSCODE_CA_SIGNING_LEASE.FENCING_EPOCH.eq(lease.fencingEpoch()));
  }

  static boolean isCurrent(DSLContext transaction, CertificateSigningLease lease) {
    return transaction.fetchExists(
        transaction
            .selectOne()
            .from(TRANSCODE_CA_SIGNING_LEASE)
            .where(identity(lease))
            .and(TRANSCODE_CA_SIGNING_LEASE.LEASE_UNTIL.gt(statementTimestamp())));
  }

  static OffsetDateTime databaseTime(DSLContext transaction) {
    return transaction.select(statementTimestamp()).fetchSingle().value1();
  }

  static Field<OffsetDateTime> statementTimestamp() {
    return DSL.function(DSL.name("statement_timestamp"), SQLDataType.TIMESTAMPWITHTIMEZONE);
  }

  private static com.streamarr.server.jooq.generated.enums.TranscodeCaSigningOperation generated(
      CertificateAuthorityOperation operation) {
    return com.streamarr.server.jooq.generated.enums.TranscodeCaSigningOperation.valueOf(
        operation.name());
  }
}
