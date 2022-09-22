/*
 * This file is generated by jOOQ.
 */
package com.streamarr.server.jooq.generated.tables.records;


import com.streamarr.server.jooq.generated.enums.LibraryBackend;
import com.streamarr.server.jooq.generated.enums.LibraryStatus;
import com.streamarr.server.jooq.generated.enums.MediaType;
import com.streamarr.server.jooq.generated.tables.Library;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record12;
import org.jooq.Row12;
import org.jooq.impl.UpdatableRecordImpl;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class LibraryRecord extends UpdatableRecordImpl<LibraryRecord> implements Record12<UUID, OffsetDateTime, UUID, OffsetDateTime, UUID, String, String, OffsetDateTime, OffsetDateTime, LibraryStatus, LibraryBackend, MediaType> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>public.library.id</code>.
     */
    public void setId(UUID value) {
        set(0, value);
    }

    /**
     * Getter for <code>public.library.id</code>.
     */
    public UUID getId() {
        return (UUID) get(0);
    }

    /**
     * Setter for <code>public.library.created_on</code>.
     */
    public void setCreatedOn(OffsetDateTime value) {
        set(1, value);
    }

    /**
     * Getter for <code>public.library.created_on</code>.
     */
    public OffsetDateTime getCreatedOn() {
        return (OffsetDateTime) get(1);
    }

    /**
     * Setter for <code>public.library.created_by</code>.
     */
    public void setCreatedBy(UUID value) {
        set(2, value);
    }

    /**
     * Getter for <code>public.library.created_by</code>.
     */
    public UUID getCreatedBy() {
        return (UUID) get(2);
    }

    /**
     * Setter for <code>public.library.last_modified_on</code>.
     */
    public void setLastModifiedOn(OffsetDateTime value) {
        set(3, value);
    }

    /**
     * Getter for <code>public.library.last_modified_on</code>.
     */
    public OffsetDateTime getLastModifiedOn() {
        return (OffsetDateTime) get(3);
    }

    /**
     * Setter for <code>public.library.last_modified_by</code>.
     */
    public void setLastModifiedBy(UUID value) {
        set(4, value);
    }

    /**
     * Getter for <code>public.library.last_modified_by</code>.
     */
    public UUID getLastModifiedBy() {
        return (UUID) get(4);
    }

    /**
     * Setter for <code>public.library.filepath</code>.
     */
    public void setFilepath(String value) {
        set(5, value);
    }

    /**
     * Getter for <code>public.library.filepath</code>.
     */
    public String getFilepath() {
        return (String) get(5);
    }

    /**
     * Setter for <code>public.library.name</code>.
     */
    public void setName(String value) {
        set(6, value);
    }

    /**
     * Getter for <code>public.library.name</code>.
     */
    public String getName() {
        return (String) get(6);
    }

    /**
     * Setter for <code>public.library.refresh_started_on</code>.
     */
    public void setRefreshStartedOn(OffsetDateTime value) {
        set(7, value);
    }

    /**
     * Getter for <code>public.library.refresh_started_on</code>.
     */
    public OffsetDateTime getRefreshStartedOn() {
        return (OffsetDateTime) get(7);
    }

    /**
     * Setter for <code>public.library.refresh_completed_on</code>.
     */
    public void setRefreshCompletedOn(OffsetDateTime value) {
        set(8, value);
    }

    /**
     * Getter for <code>public.library.refresh_completed_on</code>.
     */
    public OffsetDateTime getRefreshCompletedOn() {
        return (OffsetDateTime) get(8);
    }

    /**
     * Setter for <code>public.library.status</code>.
     */
    public void setStatus(LibraryStatus value) {
        set(9, value);
    }

    /**
     * Getter for <code>public.library.status</code>.
     */
    public LibraryStatus getStatus() {
        return (LibraryStatus) get(9);
    }

    /**
     * Setter for <code>public.library.backend</code>.
     */
    public void setBackend(LibraryBackend value) {
        set(10, value);
    }

    /**
     * Getter for <code>public.library.backend</code>.
     */
    public LibraryBackend getBackend() {
        return (LibraryBackend) get(10);
    }

    /**
     * Setter for <code>public.library.type</code>.
     */
    public void setType(MediaType value) {
        set(11, value);
    }

    /**
     * Getter for <code>public.library.type</code>.
     */
    public MediaType getType() {
        return (MediaType) get(11);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<UUID> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Record12 type implementation
    // -------------------------------------------------------------------------

    @Override
    public Row12<UUID, OffsetDateTime, UUID, OffsetDateTime, UUID, String, String, OffsetDateTime, OffsetDateTime, LibraryStatus, LibraryBackend, MediaType> fieldsRow() {
        return (Row12) super.fieldsRow();
    }

    @Override
    public Row12<UUID, OffsetDateTime, UUID, OffsetDateTime, UUID, String, String, OffsetDateTime, OffsetDateTime, LibraryStatus, LibraryBackend, MediaType> valuesRow() {
        return (Row12) super.valuesRow();
    }

    @Override
    public Field<UUID> field1() {
        return Library.LIBRARY.ID;
    }

    @Override
    public Field<OffsetDateTime> field2() {
        return Library.LIBRARY.CREATED_ON;
    }

    @Override
    public Field<UUID> field3() {
        return Library.LIBRARY.CREATED_BY;
    }

    @Override
    public Field<OffsetDateTime> field4() {
        return Library.LIBRARY.LAST_MODIFIED_ON;
    }

    @Override
    public Field<UUID> field5() {
        return Library.LIBRARY.LAST_MODIFIED_BY;
    }

    @Override
    public Field<String> field6() {
        return Library.LIBRARY.FILEPATH;
    }

    @Override
    public Field<String> field7() {
        return Library.LIBRARY.NAME;
    }

    @Override
    public Field<OffsetDateTime> field8() {
        return Library.LIBRARY.REFRESH_STARTED_ON;
    }

    @Override
    public Field<OffsetDateTime> field9() {
        return Library.LIBRARY.REFRESH_COMPLETED_ON;
    }

    @Override
    public Field<LibraryStatus> field10() {
        return Library.LIBRARY.STATUS;
    }

    @Override
    public Field<LibraryBackend> field11() {
        return Library.LIBRARY.BACKEND;
    }

    @Override
    public Field<MediaType> field12() {
        return Library.LIBRARY.TYPE;
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
    public String component6() {
        return getFilepath();
    }

    @Override
    public String component7() {
        return getName();
    }

    @Override
    public OffsetDateTime component8() {
        return getRefreshStartedOn();
    }

    @Override
    public OffsetDateTime component9() {
        return getRefreshCompletedOn();
    }

    @Override
    public LibraryStatus component10() {
        return getStatus();
    }

    @Override
    public LibraryBackend component11() {
        return getBackend();
    }

    @Override
    public MediaType component12() {
        return getType();
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
    public String value6() {
        return getFilepath();
    }

    @Override
    public String value7() {
        return getName();
    }

    @Override
    public OffsetDateTime value8() {
        return getRefreshStartedOn();
    }

    @Override
    public OffsetDateTime value9() {
        return getRefreshCompletedOn();
    }

    @Override
    public LibraryStatus value10() {
        return getStatus();
    }

    @Override
    public LibraryBackend value11() {
        return getBackend();
    }

    @Override
    public MediaType value12() {
        return getType();
    }

    @Override
    public LibraryRecord value1(UUID value) {
        setId(value);
        return this;
    }

    @Override
    public LibraryRecord value2(OffsetDateTime value) {
        setCreatedOn(value);
        return this;
    }

    @Override
    public LibraryRecord value3(UUID value) {
        setCreatedBy(value);
        return this;
    }

    @Override
    public LibraryRecord value4(OffsetDateTime value) {
        setLastModifiedOn(value);
        return this;
    }

    @Override
    public LibraryRecord value5(UUID value) {
        setLastModifiedBy(value);
        return this;
    }

    @Override
    public LibraryRecord value6(String value) {
        setFilepath(value);
        return this;
    }

    @Override
    public LibraryRecord value7(String value) {
        setName(value);
        return this;
    }

    @Override
    public LibraryRecord value8(OffsetDateTime value) {
        setRefreshStartedOn(value);
        return this;
    }

    @Override
    public LibraryRecord value9(OffsetDateTime value) {
        setRefreshCompletedOn(value);
        return this;
    }

    @Override
    public LibraryRecord value10(LibraryStatus value) {
        setStatus(value);
        return this;
    }

    @Override
    public LibraryRecord value11(LibraryBackend value) {
        setBackend(value);
        return this;
    }

    @Override
    public LibraryRecord value12(MediaType value) {
        setType(value);
        return this;
    }

    @Override
    public LibraryRecord values(UUID value1, OffsetDateTime value2, UUID value3, OffsetDateTime value4, UUID value5, String value6, String value7, OffsetDateTime value8, OffsetDateTime value9, LibraryStatus value10, LibraryBackend value11, MediaType value12) {
        value1(value1);
        value2(value2);
        value3(value3);
        value4(value4);
        value5(value5);
        value6(value6);
        value7(value7);
        value8(value8);
        value9(value9);
        value10(value10);
        value11(value11);
        value12(value12);
        return this;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached LibraryRecord
     */
    public LibraryRecord() {
        super(Library.LIBRARY);
    }

    /**
     * Create a detached, initialised LibraryRecord
     */
    public LibraryRecord(UUID id, OffsetDateTime createdOn, UUID createdBy, OffsetDateTime lastModifiedOn, UUID lastModifiedBy, String filepath, String name, OffsetDateTime refreshStartedOn, OffsetDateTime refreshCompletedOn, LibraryStatus status, LibraryBackend backend, MediaType type) {
        super(Library.LIBRARY);

        setId(id);
        setCreatedOn(createdOn);
        setCreatedBy(createdBy);
        setLastModifiedOn(lastModifiedOn);
        setLastModifiedBy(lastModifiedBy);
        setFilepath(filepath);
        setName(name);
        setRefreshStartedOn(refreshStartedOn);
        setRefreshCompletedOn(refreshCompletedOn);
        setStatus(status);
        setBackend(backend);
        setType(type);
    }
}