package com.streamarr.server.config.persistence;

import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

public class TimestampWithTimezone implements UserType {

    @Override
    public int[] sqlTypes() {
        return new int[]{Types.TIME_WITH_TIMEZONE};
    }

    @Override
    public Class returnedClass() {
        return Instant.class;
    }

    @Override
    public boolean equals(Object x, Object y) throws HibernateException {
        return Objects.equals(x, y);
    }

    @Override
    public int hashCode(Object x) throws HibernateException {
        return Objects.hashCode(x);
    }

    @Override
    public Object nullSafeGet(ResultSet rs, String[] names, SharedSessionContractImplementor session, Object owner) throws HibernateException, SQLException {
        OffsetDateTime offsetDateTime = rs.getObject(names[0], OffsetDateTime.class);
        return offsetDateTime == null || rs.wasNull() ? null : offsetDateTime.toInstant();
    }

    @Override
    public void nullSafeSet(PreparedStatement st, Object value, int index, SharedSessionContractImplementor session)
        throws HibernateException, SQLException {
        if (value == null) {
            st.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            OffsetDateTime offsetDateTime;

            switch (value) {
                case Instant v -> offsetDateTime = v.atOffset(ZoneOffset.UTC);
                case Date v -> offsetDateTime = Instant
                    .ofEpochMilli(v.getTime())
                    .atOffset(ZoneOffset.UTC);
                default -> throw new IllegalStateException("Unexpected value: " + value);
            }

            st.setObject(index, offsetDateTime);
        }
    }

    @Override
    public Object deepCopy(Object value) throws HibernateException {
        return value;
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public Serializable disassemble(Object value) throws HibernateException {
        return (Instant) value;
    }

    @Override
    public Object assemble(Serializable cached, Object owner) throws HibernateException {
        return cached;
    }

    @Override
    public Object replace(Object original, Object target, Object owner) throws HibernateException {
        return original;
    }
}
