/*
 * This file is generated by jOOQ.
 */
package com.streamarr.server.jooq.generated.tables.records;


import com.streamarr.server.jooq.generated.enums.ExternalSourceType;
import com.streamarr.server.jooq.generated.tables.ExternalIdentifier;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.Record8;
import org.jooq.Row8;
import org.jooq.impl.UpdatableRecordImpl;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class ExternalIdentifierRecord extends UpdatableRecordImpl<ExternalIdentifierRecord> implements Record8<UUID, OffsetDateTime, UUID, OffsetDateTime, UUID, ExternalSourceType, String, UUID> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>public.external_identifier.id</code>.
     */
    public void setId(UUID value) {
        set(0, value);
    }

    /**
     * Getter for <code>public.external_identifier.id</code>.
     */
    public UUID getId() {
        return (UUID) get(0);
    }

    /**
     * Setter for <code>public.external_identifier.created_on</code>.
     */
    public void setCreatedOn(OffsetDateTime value) {
        set(1, value);
    }

    /**
     * Getter for <code>public.external_identifier.created_on</code>.
     */
    public OffsetDateTime getCreatedOn() {
        return (OffsetDateTime) get(1);
    }

    /**
     * Setter for <code>public.external_identifier.created_by</code>.
     */
    public void setCreatedBy(UUID value) {
        set(2, value);
    }

    /**
     * Getter for <code>public.external_identifier.created_by</code>.
     */
    public UUID getCreatedBy() {
        return (UUID) get(2);
    }

    /**
     * Setter for <code>public.external_identifier.last_modified_on</code>.
     */
    public void setLastModifiedOn(OffsetDateTime value) {
        set(3, value);
    }

    /**
     * Getter for <code>public.external_identifier.last_modified_on</code>.
     */
    public OffsetDateTime getLastModifiedOn() {
        return (OffsetDateTime) get(3);
    }

    /**
     * Setter for <code>public.external_identifier.last_modified_by</code>.
     */
    public void setLastModifiedBy(UUID value) {
        set(4, value);
    }

    /**
     * Getter for <code>public.external_identifier.last_modified_by</code>.
     */
    public UUID getLastModifiedBy() {
        return (UUID) get(4);
    }

    /**
     * Setter for <code>public.external_identifier.external_source_type</code>.
     */
    public void setExternalSourceType(ExternalSourceType value) {
        set(5, value);
    }

    /**
     * Getter for <code>public.external_identifier.external_source_type</code>.
     */
    public ExternalSourceType getExternalSourceType() {
        return (ExternalSourceType) get(5);
    }

    /**
     * Setter for <code>public.external_identifier.external_id</code>.
     */
    public void setExternalId(String value) {
        set(6, value);
    }

    /**
     * Getter for <code>public.external_identifier.external_id</code>.
     */
    public String getExternalId() {
        return (String) get(6);
    }

    /**
     * Setter for <code>public.external_identifier.entity_id</code>.
     */
    public void setEntityId(UUID value) {
        set(7, value);
    }

    /**
     * Getter for <code>public.external_identifier.entity_id</code>.
     */
    public UUID getEntityId() {
        return (UUID) get(7);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record2<ExternalSourceType, String> key() {
        return (Record2) super.key();
    }

    // -------------------------------------------------------------------------
    // Record8 type implementation
    // -------------------------------------------------------------------------

    @Override
    public Row8<UUID, OffsetDateTime, UUID, OffsetDateTime, UUID, ExternalSourceType, String, UUID> fieldsRow() {
        return (Row8) super.fieldsRow();
    }

    @Override
    public Row8<UUID, OffsetDateTime, UUID, OffsetDateTime, UUID, ExternalSourceType, String, UUID> valuesRow() {
        return (Row8) super.valuesRow();
    }

    @Override
    public Field<UUID> field1() {
        return ExternalIdentifier.EXTERNAL_IDENTIFIER.ID;
    }

    @Override
    public Field<OffsetDateTime> field2() {
        return ExternalIdentifier.EXTERNAL_IDENTIFIER.CREATED_ON;
    }

    @Override
    public Field<UUID> field3() {
        return ExternalIdentifier.EXTERNAL_IDENTIFIER.CREATED_BY;
    }

    @Override
    public Field<OffsetDateTime> field4() {
        return ExternalIdentifier.EXTERNAL_IDENTIFIER.LAST_MODIFIED_ON;
    }

    @Override
    public Field<UUID> field5() {
        return ExternalIdentifier.EXTERNAL_IDENTIFIER.LAST_MODIFIED_BY;
    }

    @Override
    public Field<ExternalSourceType> field6() {
        return ExternalIdentifier.EXTERNAL_IDENTIFIER.EXTERNAL_SOURCE_TYPE;
    }

    @Override
    public Field<String> field7() {
        return ExternalIdentifier.EXTERNAL_IDENTIFIER.EXTERNAL_ID;
    }

    @Override
    public Field<UUID> field8() {
        return ExternalIdentifier.EXTERNAL_IDENTIFIER.ENTITY_ID;
    }

    @Override
    public UUID component1() {
        return getId();
    }

    @Override
    public OffsetDateTime component2() {
        return getCreatedOn();
    }

    @Override
    public UUID component3() {
        return getCreatedBy();
    }

    @Override
    public OffsetDateTime component4() {
        return getLastModifiedOn();
    }

    @Override
    public UUID component5() {
        return getLastModifiedBy();
    }

    @Override
    public ExternalSourceType component6() {
        return getExternalSourceType();
    }

    @Override
    public String component7() {
        return getExternalId();
    }

    @Override
    public UUID component8() {
        return getEntityId();
    }

    @Override
    public UUID value1() {
        return getId();
    }

    @Override
    public OffsetDateTime value2() {
        return getCreatedOn();
    }

    @Override
    public UUID value3() {
        return getCreatedBy();
    }

    @Override
    public OffsetDateTime value4() {
        return getLastModifiedOn();
    }

    @Override
    public UUID value5() {
        return getLastModifiedBy();
    }

    @Override
    public ExternalSourceType value6() {
        return getExternalSourceType();
    }

    @Override
    public String value7() {
        return getExternalId();
    }

    @Override
    public UUID value8() {
        return getEntityId();
    }

    @Override
    public ExternalIdentifierRecord value1(UUID value) {
        setId(value);
        return this;
    }

    @Override
    public ExternalIdentifierRecord value2(OffsetDateTime value) {
        setCreatedOn(value);
        return this;
    }

    @Override
    public ExternalIdentifierRecord value3(UUID value) {
        setCreatedBy(value);
        return this;
    }

    @Override
    public ExternalIdentifierRecord value4(OffsetDateTime value) {
        setLastModifiedOn(value);
        return this;
    }

    @Override
    public ExternalIdentifierRecord value5(UUID value) {
        setLastModifiedBy(value);
        return this;
    }

    @Override
    public ExternalIdentifierRecord value6(ExternalSourceType value) {
        setExternalSourceType(value);
        return this;
    }

    @Override
    public ExternalIdentifierRecord value7(String value) {
        setExternalId(value);
        return this;
    }

    @Override
    public ExternalIdentifierRecord value8(UUID value) {
        setEntityId(value);
        return this;
    }

    @Override
    public ExternalIdentifierRecord values(UUID value1, OffsetDateTime value2, UUID value3, OffsetDateTime value4, UUID value5, ExternalSourceType value6, String value7, UUID value8) {
        value1(value1);
        value2(value2);
        value3(value3);
        value4(value4);
        value5(value5);
        value6(value6);
        value7(value7);
        value8(value8);
        return this;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached ExternalIdentifierRecord
     */
    public ExternalIdentifierRecord() {
        super(ExternalIdentifier.EXTERNAL_IDENTIFIER);
    }

    /**
     * Create a detached, initialised ExternalIdentifierRecord
     */
    public ExternalIdentifierRecord(UUID id, OffsetDateTime createdOn, UUID createdBy, OffsetDateTime lastModifiedOn, UUID lastModifiedBy, ExternalSourceType externalSourceType, String externalId, UUID entityId) {
        super(ExternalIdentifier.EXTERNAL_IDENTIFIER);

        setId(id);
        setCreatedOn(createdOn);
        setCreatedBy(createdBy);
        setLastModifiedOn(lastModifiedOn);
        setLastModifiedBy(lastModifiedBy);
        setExternalSourceType(externalSourceType);
        setExternalId(externalId);
        setEntityId(entityId);
    }
}