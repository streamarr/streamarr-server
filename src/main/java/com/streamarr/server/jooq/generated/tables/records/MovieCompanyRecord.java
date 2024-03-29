/*
 * This file is generated by jOOQ.
 */
package com.streamarr.server.jooq.generated.tables.records;


import com.streamarr.server.jooq.generated.tables.MovieCompany;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record4;
import org.jooq.Row4;
import org.jooq.impl.UpdatableRecordImpl;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class MovieCompanyRecord extends UpdatableRecordImpl<MovieCompanyRecord> implements Record4<UUID, OffsetDateTime, UUID, UUID> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>public.movie_company.id</code>.
     */
    public void setId(UUID value) {
        set(0, value);
    }

    /**
     * Getter for <code>public.movie_company.id</code>.
     */
    public UUID getId() {
        return (UUID) get(0);
    }

    /**
     * Setter for <code>public.movie_company.created_on</code>.
     */
    public void setCreatedOn(OffsetDateTime value) {
        set(1, value);
    }

    /**
     * Getter for <code>public.movie_company.created_on</code>.
     */
    public OffsetDateTime getCreatedOn() {
        return (OffsetDateTime) get(1);
    }

    /**
     * Setter for <code>public.movie_company.movie_id</code>.
     */
    public void setMovieId(UUID value) {
        set(2, value);
    }

    /**
     * Getter for <code>public.movie_company.movie_id</code>.
     */
    public UUID getMovieId() {
        return (UUID) get(2);
    }

    /**
     * Setter for <code>public.movie_company.company_id</code>.
     */
    public void setCompanyId(UUID value) {
        set(3, value);
    }

    /**
     * Getter for <code>public.movie_company.company_id</code>.
     */
    public UUID getCompanyId() {
        return (UUID) get(3);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<UUID> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Record4 type implementation
    // -------------------------------------------------------------------------

    @Override
    public Row4<UUID, OffsetDateTime, UUID, UUID> fieldsRow() {
        return (Row4) super.fieldsRow();
    }

    @Override
    public Row4<UUID, OffsetDateTime, UUID, UUID> valuesRow() {
        return (Row4) super.valuesRow();
    }

    @Override
    public Field<UUID> field1() {
        return MovieCompany.MOVIE_COMPANY.ID;
    }

    @Override
    public Field<OffsetDateTime> field2() {
        return MovieCompany.MOVIE_COMPANY.CREATED_ON;
    }

    @Override
    public Field<UUID> field3() {
        return MovieCompany.MOVIE_COMPANY.MOVIE_ID;
    }

    @Override
    public Field<UUID> field4() {
        return MovieCompany.MOVIE_COMPANY.COMPANY_ID;
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
        return getMovieId();
    }

    @Override
    public UUID component4() {
        return getCompanyId();
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
        return getMovieId();
    }

    @Override
    public UUID value4() {
        return getCompanyId();
    }

    @Override
    public MovieCompanyRecord value1(UUID value) {
        setId(value);
        return this;
    }

    @Override
    public MovieCompanyRecord value2(OffsetDateTime value) {
        setCreatedOn(value);
        return this;
    }

    @Override
    public MovieCompanyRecord value3(UUID value) {
        setMovieId(value);
        return this;
    }

    @Override
    public MovieCompanyRecord value4(UUID value) {
        setCompanyId(value);
        return this;
    }

    @Override
    public MovieCompanyRecord values(UUID value1, OffsetDateTime value2, UUID value3, UUID value4) {
        value1(value1);
        value2(value2);
        value3(value3);
        value4(value4);
        return this;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached MovieCompanyRecord
     */
    public MovieCompanyRecord() {
        super(MovieCompany.MOVIE_COMPANY);
    }

    /**
     * Create a detached, initialised MovieCompanyRecord
     */
    public MovieCompanyRecord(UUID id, OffsetDateTime createdOn, UUID movieId, UUID companyId) {
        super(MovieCompany.MOVIE_COMPANY);

        setId(id);
        setCreatedOn(createdOn);
        setMovieId(movieId);
        setCompanyId(companyId);
    }
}
