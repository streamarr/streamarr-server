/*
 * This file is generated by jOOQ.
 */
package com.streamarr.server.jooq.generated.tables;


import com.streamarr.server.jooq.generated.Keys;
import com.streamarr.server.jooq.generated.Public;
import com.streamarr.server.jooq.generated.tables.records.BaseCollectableRecord;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Function7;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Records;
import org.jooq.Row7;
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
public class BaseCollectable extends TableImpl<BaseCollectableRecord> {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>public.base_collectable</code>
     */
    public static final BaseCollectable BASE_COLLECTABLE = new BaseCollectable();

    /**
     * The class holding records for this type
     */
    @Override
    public Class<BaseCollectableRecord> getRecordType() {
        return BaseCollectableRecord.class;
    }

    /**
     * The column <code>public.base_collectable.id</code>.
     */
    public final TableField<BaseCollectableRecord, UUID> ID = createField(DSL.name("id"), SQLDataType.UUID.nullable(false).defaultValue(DSL.field("uuid_generate_v4()", SQLDataType.UUID)), this, "");

    /**
     * The column <code>public.base_collectable.created_on</code>.
     */
    public final TableField<BaseCollectableRecord, OffsetDateTime> CREATED_ON = createField(DSL.name("created_on"), SQLDataType.TIMESTAMPWITHTIMEZONE(6).nullable(false).defaultValue(DSL.field("now()", SQLDataType.TIMESTAMPWITHTIMEZONE)), this, "");

    /**
     * The column <code>public.base_collectable.created_by</code>.
     */
    public final TableField<BaseCollectableRecord, UUID> CREATED_BY = createField(DSL.name("created_by"), SQLDataType.UUID.nullable(false), this, "");

    /**
     * The column <code>public.base_collectable.last_modified_on</code>.
     */
    public final TableField<BaseCollectableRecord, OffsetDateTime> LAST_MODIFIED_ON = createField(DSL.name("last_modified_on"), SQLDataType.TIMESTAMPWITHTIMEZONE(6).nullable(false).defaultValue(DSL.field("now()", SQLDataType.TIMESTAMPWITHTIMEZONE)), this, "");

    /**
     * The column <code>public.base_collectable.last_modified_by</code>.
     */
    public final TableField<BaseCollectableRecord, UUID> LAST_MODIFIED_BY = createField(DSL.name("last_modified_by"), SQLDataType.UUID, this, "");

    /**
     * The column <code>public.base_collectable.title</code>.
     */
    public final TableField<BaseCollectableRecord, String> TITLE = createField(DSL.name("title"), SQLDataType.CLOB, this, "");

    /**
     * The column <code>public.base_collectable.library_id</code>.
     */
    public final TableField<BaseCollectableRecord, UUID> LIBRARY_ID = createField(DSL.name("library_id"), SQLDataType.UUID.nullable(false), this, "");

    private BaseCollectable(Name alias, Table<BaseCollectableRecord> aliased) {
        this(alias, aliased, null);
    }

    private BaseCollectable(Name alias, Table<BaseCollectableRecord> aliased, Field<?>[] parameters) {
        super(alias, null, aliased, parameters, DSL.comment(""), TableOptions.table());
    }

    /**
     * Create an aliased <code>public.base_collectable</code> table reference
     */
    public BaseCollectable(String alias) {
        this(DSL.name(alias), BASE_COLLECTABLE);
    }

    /**
     * Create an aliased <code>public.base_collectable</code> table reference
     */
    public BaseCollectable(Name alias) {
        this(alias, BASE_COLLECTABLE);
    }

    /**
     * Create a <code>public.base_collectable</code> table reference
     */
    public BaseCollectable() {
        this(DSL.name("base_collectable"), null);
    }

    public <O extends Record> BaseCollectable(Table<O> child, ForeignKey<O, BaseCollectableRecord> key) {
        super(child, key, BASE_COLLECTABLE);
    }

    @Override
    public Schema getSchema() {
        return aliased() ? null : Public.PUBLIC;
    }

    @Override
    public UniqueKey<BaseCollectableRecord> getPrimaryKey() {
        return Keys.BASE_COLLECTABLE_PKEY;
    }

    @Override
    public List<ForeignKey<BaseCollectableRecord, ?>> getReferences() {
        return Arrays.asList(Keys.BASE_COLLECTABLE__FK_LIBRARY);
    }

    private transient Library _library;

    /**
     * Get the implicit join path to the <code>public.library</code> table.
     */
    public Library library() {
        if (_library == null)
            _library = new Library(this, Keys.BASE_COLLECTABLE__FK_LIBRARY);

        return _library;
    }

    @Override
    public BaseCollectable as(String alias) {
        return new BaseCollectable(DSL.name(alias), this);
    }

    @Override
    public BaseCollectable as(Name alias) {
        return new BaseCollectable(alias, this);
    }

    @Override
    public BaseCollectable as(Table<?> alias) {
        return new BaseCollectable(alias.getQualifiedName(), this);
    }

    /**
     * Rename this table
     */
    @Override
    public BaseCollectable rename(String name) {
        return new BaseCollectable(DSL.name(name), null);
    }

    /**
     * Rename this table
     */
    @Override
    public BaseCollectable rename(Name name) {
        return new BaseCollectable(name, null);
    }

    /**
     * Rename this table
     */
    @Override
    public BaseCollectable rename(Table<?> name) {
        return new BaseCollectable(name.getQualifiedName(), null);
    }

    // -------------------------------------------------------------------------
    // Row7 type methods
    // -------------------------------------------------------------------------

    @Override
    public Row7<UUID, OffsetDateTime, UUID, OffsetDateTime, UUID, String, UUID> fieldsRow() {
        return (Row7) super.fieldsRow();
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Function)}.
     */
    public <U> SelectField<U> mapping(Function7<? super UUID, ? super OffsetDateTime, ? super UUID, ? super OffsetDateTime, ? super UUID, ? super String, ? super UUID, ? extends U> from) {
        return convertFrom(Records.mapping(from));
    }

    /**
     * Convenience mapping calling {@link SelectField#convertFrom(Class,
     * Function)}.
     */
    public <U> SelectField<U> mapping(Class<U> toType, Function7<? super UUID, ? super OffsetDateTime, ? super UUID, ? super OffsetDateTime, ? super UUID, ? super String, ? super UUID, ? extends U> from) {
        return convertFrom(toType, Records.mapping(from));
    }
}