/*
 * This file is generated by jOOQ.
 */
package com.streamarr.server.jooq.generated.tables.records;


import com.streamarr.server.jooq.generated.tables.Rating;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record8;
import org.jooq.Row8;
import org.jooq.impl.UpdatableRecordImpl;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class RatingRecord extends UpdatableRecordImpl<RatingRecord> implements Record8<UUID, OffsetDateTime, UUID, OffsetDateTime, UUID, String, String, UUID> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>public.rating.id</code>.
     */
    public void setId(UUID value) {
        set(0, value);
    }

    /**
     * Getter for <code>public.rating.id</code>.
     */
    public UUID getId() {
        return (UUID) get(0);
    }

    /**
     * Setter for <code>public.rating.created_on</code>.
     */
    public void setCreatedOn(OffsetDateTime value) {
        set(1, value);
    }

    /**
     * Getter for <code>public.rating.created_on</code>.
     */
    public OffsetDateTime getCreatedOn() {
        return (OffsetDateTime) get(1);
    }

    /**
     * Setter for <code>public.rating.created_by</code>.
     */
    public void setCreatedBy(UUID value) {
        set(2, value);
    }

    /**
     * Getter for <code>public.rating.created_by</code>.
     */
    public UUID getCreatedBy() {
        return (UUID) get(2);
    }

    /**
     * Setter for <code>public.rating.last_modified_on</code>.
     */
    public void setLastModifiedOn(OffsetDateTime value) {
        set(3, value);
    }

    /**
     * Getter for <code>public.rating.last_modified_on</code>.
     */
    public OffsetDateTime getLastModifiedOn() {
        return (OffsetDateTime) get(3);
    }

    /**
     * Setter for <code>public.rating.last_modified_by</code>.
     */
    public void setLastModifiedBy(UUID value) {
        set(4, value);
    }

    /**
     * Getter for <code>public.rating.last_modified_by</code>.
     */
    public UUID getLastModifiedBy() {
        return (UUID) get(4);
    }

    /**
     * Setter for <code>public.rating.source</code>.
     */
    public void setSource(String value) {
        set(5, value);
    }

    /**
     * Getter for <code>public.rating.source</code>.
     */
    public String getSource() {
        return (String) get(5);
    }

    /**
     * Setter for <code>public.rating.value</code>.
     */
    public void setValue(String value) {
        set(6, value);
    }

    /**
     * Getter for <code>public.rating.value</code>.
     */
    public String getValue() {
        return (String) get(6);
    }

    /**
     * Setter for <code>public.rating.movie_id</code>.
     */
    public void setMovieId(UUID value) {
        set(7, value);
    }

    /**
     * Getter for <code>public.rating.movie_id</code>.
     */
    public UUID getMovieId() {
        return (UUID) get(7);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<UUID> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Record8 type implementation
    // -------------------------------------------------------------------------

    @Override
    public Row8<UUID, OffsetDateTime, UUID, OffsetDateTime, UUID, String, String, UUID> fieldsRow() {
        return (Row8) super.fieldsRow();
    }

    @Override
    public Row8<UUID, OffsetDateTime, UUID, OffsetDateTime, UUID, String, String, UUID> valuesRow() {
        return (Row8) super.valuesRow();
    }

    @Override
    public Field<UUID> field1() {
        return Rating.RATING.ID;
    }

    @Override
    public Field<OffsetDateTime> field2() {
        return Rating.RATING.CREATED_ON;
    }

    @Override
    public Field<UUID> field3() {
        return Rating.RATING.CREATED_BY;
    }

    @Override
    public Field<OffsetDateTime> field4() {
        return Rating.RATING.LAST_MODIFIED_ON;
    }

    @Override
    public Field<UUID> field5() {
        return Rating.RATING.LAST_MODIFIED_BY;
    }

    @Override
    public Field<String> field6() {
        return Rating.RATING.SOURCE;
    }

    @Override
    public Field<String> field7() {
        return Rating.RATING.VALUE;
    }

    @Override
    public Field<UUID> field8() {
        return Rating.RATING.MOVIE_ID;
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
        return getSource();
    }

    @Override
    public String component7() {
        return getValue();
    }

    @Override
    public UUID component8() {
        return getMovieId();
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
        return getSource();
    }

    @Override
    public String value7() {
        return getValue();
    }

    @Override
    public UUID value8() {
        return getMovieId();
    }

    @Override
    public RatingRecord value1(UUID value) {
        setId(value);
        return this;
    }

    @Override
    public RatingRecord value2(OffsetDateTime value) {
        setCreatedOn(value);
        return this;
    }

    @Override
    public RatingRecord value3(UUID value) {
        setCreatedBy(value);
        return this;
    }

    @Override
    public RatingRecord value4(OffsetDateTime value) {
        setLastModifiedOn(value);
        return this;
    }

    @Override
    public RatingRecord value5(UUID value) {
        setLastModifiedBy(value);
        return this;
    }

    @Override
    public RatingRecord value6(String value) {
        setSource(value);
        return this;
    }

    @Override
    public RatingRecord value7(String value) {
        setValue(value);
        return this;
    }

    @Override
    public RatingRecord value8(UUID value) {
        setMovieId(value);
        return this;
    }

    @Override
    public RatingRecord values(UUID value1, OffsetDateTime value2, UUID value3, OffsetDateTime value4, UUID value5, String value6, String value7, UUID value8) {
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
     * Create a detached RatingRecord
     */
    public RatingRecord() {
        super(Rating.RATING);
    }

    /**
     * Create a detached, initialised RatingRecord
     */
    public RatingRecord(UUID id, OffsetDateTime createdOn, UUID createdBy, OffsetDateTime lastModifiedOn, UUID lastModifiedBy, String source, String value, UUID movieId) {
        super(Rating.RATING);

        setId(id);
        setCreatedOn(createdOn);
        setCreatedBy(createdBy);
        setLastModifiedOn(lastModifiedOn);
        setLastModifiedBy(lastModifiedBy);
        setSource(source);
        setValue(value);
        setMovieId(movieId);
    }
}