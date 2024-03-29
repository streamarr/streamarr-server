/*
 * This file is generated by jOOQ.
 */
package com.streamarr.server.jooq.generated.tables;


import com.streamarr.server.jooq.generated.Keys;
import com.streamarr.server.jooq.generated.Public;
import com.streamarr.server.jooq.generated.tables.records.MovieCompanyRecord;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Function4;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row4;
import org.jooq.Schema;
import org.jooq.SelectField;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableOptions;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class MovieCompany extends TableImpl<MovieCompanyRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>public.movie_company</code>
     */
    public static final MovieCompany MOVIE_COMPANY = new MovieCompany();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<MovieCompanyRecord> getRecordType() {
        return MovieCompanyRecord.class;
    }

    /**
     * The column <code>public.movie_company.id</code>.
     */
    public final TableField<MovieCompanyRecord, UUID> ID = createField(DSL.name("id"), SQLDataType.UUID.nullable(false).defaultValue(DSL.field("uuid_generate_v4()", SQLDataType.UUID)), this, "");

    /**
     * The column <code>public.movie_company.created_on</code>.
     */
    public final TableField<MovieCompanyRecord, OffsetDateTime> CREATED_ON = createField(DSL.name("created_on"), SQLDataType.TIMESTAMPWITHTIMEZONE(6).nullable(false).defaultValue(DSL.field("now()", SQLDataType.TIMESTAMPWITHTIMEZONE)), this, "");

    /**
     * The column <code>public.movie_company.movie_id</code>.
     */
    public final TableField<MovieCompanyRecord, UUID> MOVIE_ID = createField(DSL.name("movie_id"), SQLDataType.UUID.nullable(false), this, "");

    /**
     * The column <code>public.movie_company.company_id</code>.
     */
    public final TableField<MovieCompanyRecord, UUID> COMPANY_ID = createField(DSL.name("company_id"), SQLDataType.UUID.nullable(false), this, "");

    private MovieCompany(Name alias, Table<MovieCompanyRecord> aliased) {
        this(alias, aliased, null);
    }

    private MovieCompany(Name alias, Table<MovieCompanyRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>public.movie_company</code> table reference
     */
    public MovieCompany(String alias) {
        this(DSL.name(alias), MOVIE_COMPANY);
    }

    /**
     * Create an aliased <code>public.movie_company</code> table reference
     */
    public MovieCompany(Name alias) {
        this(alias, MOVIE_COMPANY);
    }

    /**
     * Create a <code>public.movie_company</code> table reference
     */
    public MovieCompany() {
        this(DSL.name("movie_company"), null);
    }

    public <O extends Record> MovieCompany(Table<O> child, ForeignKey<O, MovieCompanyRecord> key) {
        super(child, key, MOVIE_COMPANY);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Public.PUBLIC;
    }

    @Override
    public UniqueKey<MovieCompanyRecord> getPrimaryKey() {
        return Keys.MOVIE_COMPANY_PKEY;
    }

    @Override
    public List<ForeignKey<MovieCompanyRecord, ?>> getReferences() {
        return Arrays.asList(Keys.MOVIE_COMPANY__MOVIE_COMPANY_MOVIE_ID_FKEY, Keys.MOVIE_COMPANY__MOVIE_COMPANY_COMPANY_ID_FKEY);
    }

    private transient Movie _movie;
    private transient Company _company;

    /**
     * Get the implicit join path to the <code>public.movie</code> table.
     */
    public Movie movie() {
        if (_movie == null)
            _movie = new Movie(this, Keys.MOVIE_COMPANY__MOVIE_COMPANY_MOVIE_ID_FKEY);

        return _movie;
    }

    /**
     * Get the implicit join path to the <code>public.company</code> table.
     */
    public Company company() {
        if (_company == null)
            _company = new Company(this, Keys.MOVIE_COMPANY__MOVIE_COMPANY_COMPANY_ID_FKEY);

        return _company;
    }

    @Override
    public MovieCompany as(String alias) {
        return new MovieCompany(DSL.name(alias), this);
    }

    @Override
    public MovieCompany as(Name alias) {
        return new MovieCompany(alias, this);
    }

    @Override
    public MovieCompany as(Table<?> alias) {
        return new MovieCompany(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public MovieCompany rename(String name) {
        return new MovieCompany(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public MovieCompany rename(Name name) {
        return new MovieCompany(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public MovieCompany rename(Table<?> name) {
        return new MovieCompany(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row4 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row4<UUID, OffsetDateTime, UUID, UUID> fieldsRow() {
        return (Row4) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function4<? super UUID, ? super OffsetDateTime, ? super UUID, ? super UUID, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function4<? super UUID, ? super OffsetDateTime, ? super UUID, ? super UUID, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}
