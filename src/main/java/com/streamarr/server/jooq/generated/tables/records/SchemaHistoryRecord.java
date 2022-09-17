/*
 * This file is generated by jOOQ.
 */
package com.streamarr.server.jooq.generated.tables.records;


import com.streamarr.server.jooq.generated.tables.SchemaHistory;

import java.time.LocalDateTime;

import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record10;
import org.jooq.Row10;
import org.jooq.impl.UpdatableRecordImpl;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class SchemaHistoryRecord extends UpdatableRecordImpl<SchemaHistoryRecord> implements Record10<Integer, String, String, String, String, Integer, String, LocalDateTime, Integer, Boolean> {

    private static final long serialVersionUID = 1L;

    /**
     * Setter for <code>public.schema_history.installed_rank</code>.
     */
    public void setInstalledRank(Integer value) {
        set(0, value);
    }

    /**
     * Getter for <code>public.schema_history.installed_rank</code>.
     */
    public Integer getInstalledRank() {
        return (Integer) get(0);
    }

    /**
     * Setter for <code>public.schema_history.version</code>.
     */
    public void setVersion(String value) {
        set(1, value);
    }

    /**
     * Getter for <code>public.schema_history.version</code>.
     */
    public String getVersion() {
        return (String) get(1);
    }

    /**
     * Setter for <code>public.schema_history.description</code>.
     */
    public void setDescription(String value) {
        set(2, value);
    }

    /**
     * Getter for <code>public.schema_history.description</code>.
     */
    public String getDescription() {
        return (String) get(2);
    }

    /**
     * Setter for <code>public.schema_history.type</code>.
     */
    public void setType(String value) {
        set(3, value);
    }

    /**
     * Getter for <code>public.schema_history.type</code>.
     */
    public String getType() {
        return (String) get(3);
    }

    /**
     * Setter for <code>public.schema_history.script</code>.
     */
    public void setScript(String value) {
        set(4, value);
    }

    /**
     * Getter for <code>public.schema_history.script</code>.
     */
    public String getScript() {
        return (String) get(4);
    }

    /**
     * Setter for <code>public.schema_history.checksum</code>.
     */
    public void setChecksum(Integer value) {
        set(5, value);
    }

    /**
     * Getter for <code>public.schema_history.checksum</code>.
     */
    public Integer getChecksum() {
        return (Integer) get(5);
    }

    /**
     * Setter for <code>public.schema_history.installed_by</code>.
     */
    public void setInstalledBy(String value) {
        set(6, value);
    }

    /**
     * Getter for <code>public.schema_history.installed_by</code>.
     */
    public String getInstalledBy() {
        return (String) get(6);
    }

    /**
     * Setter for <code>public.schema_history.installed_on</code>.
     */
    public void setInstalledOn(LocalDateTime value) {
        set(7, value);
    }

    /**
     * Getter for <code>public.schema_history.installed_on</code>.
     */
    public LocalDateTime getInstalledOn() {
        return (LocalDateTime) get(7);
    }

    /**
     * Setter for <code>public.schema_history.execution_time</code>.
     */
    public void setExecutionTime(Integer value) {
        set(8, value);
    }

    /**
     * Getter for <code>public.schema_history.execution_time</code>.
     */
    public Integer getExecutionTime() {
        return (Integer) get(8);
    }

    /**
     * Setter for <code>public.schema_history.success</code>.
     */
    public void setSuccess(Boolean value) {
        set(9, value);
    }

    /**
     * Getter for <code>public.schema_history.success</code>.
     */
    public Boolean getSuccess() {
        return (Boolean) get(9);
    }

    // -------------------------------------------------------------------------
    // Primary key information
    // -------------------------------------------------------------------------

    @Override
    public Record1<Integer> key() {
        return (Record1) super.key();
    }

    // -------------------------------------------------------------------------
    // Record10 type implementation
    // -------------------------------------------------------------------------

    @Override
    public Row10<Integer, String, String, String, String, Integer, String, LocalDateTime, Integer, Boolean> fieldsRow() {
        return (Row10) super.fieldsRow();
    }

    @Override
    public Row10<Integer, String, String, String, String, Integer, String, LocalDateTime, Integer, Boolean> valuesRow() {
        return (Row10) super.valuesRow();
    }

    @Override
    public Field<Integer> field1() {
        return SchemaHistory.SCHEMA_HISTORY.INSTALLED_RANK;
    }

    @Override
    public Field<String> field2() {
        return SchemaHistory.SCHEMA_HISTORY.VERSION;
    }

    @Override
    public Field<String> field3() {
        return SchemaHistory.SCHEMA_HISTORY.DESCRIPTION;
    }

    @Override
    public Field<String> field4() {
        return SchemaHistory.SCHEMA_HISTORY.TYPE;
    }

    @Override
    public Field<String> field5() {
        return SchemaHistory.SCHEMA_HISTORY.SCRIPT;
    }

    @Override
    public Field<Integer> field6() {
        return SchemaHistory.SCHEMA_HISTORY.CHECKSUM;
    }

    @Override
    public Field<String> field7() {
        return SchemaHistory.SCHEMA_HISTORY.INSTALLED_BY;
    }

    @Override
    public Field<LocalDateTime> field8() {
        return SchemaHistory.SCHEMA_HISTORY.INSTALLED_ON;
    }

    @Override
    public Field<Integer> field9() {
        return SchemaHistory.SCHEMA_HISTORY.EXECUTION_TIME;
    }

    @Override
    public Field<Boolean> field10() {
        return SchemaHistory.SCHEMA_HISTORY.SUCCESS;
    }

    @Override
    public Integer component1() {
        return getInstalledRank();
    }

    @Override
    public String component2() {
        return getVersion();
    }

    @Override
    public String component3() {
        return getDescription();
    }

    @Override
    public String component4() {
        return getType();
    }

    @Override
    public String component5() {
        return getScript();
    }

    @Override
    public Integer component6() {
        return getChecksum();
    }

    @Override
    public String component7() {
        return getInstalledBy();
    }

    @Override
    public LocalDateTime component8() {
        return getInstalledOn();
    }

    @Override
    public Integer component9() {
        return getExecutionTime();
    }

    @Override
    public Boolean component10() {
        return getSuccess();
    }

    @Override
    public Integer value1() {
        return getInstalledRank();
    }

    @Override
    public String value2() {
        return getVersion();
    }

    @Override
    public String value3() {
        return getDescription();
    }

    @Override
    public String value4() {
        return getType();
    }

    @Override
    public String value5() {
        return getScript();
    }

    @Override
    public Integer value6() {
        return getChecksum();
    }

    @Override
    public String value7() {
        return getInstalledBy();
    }

    @Override
    public LocalDateTime value8() {
        return getInstalledOn();
    }

    @Override
    public Integer value9() {
        return getExecutionTime();
    }

    @Override
    public Boolean value10() {
        return getSuccess();
    }

    @Override
    public SchemaHistoryRecord value1(Integer value) {
        setInstalledRank(value);
        return this;
    }

    @Override
    public SchemaHistoryRecord value2(String value) {
        setVersion(value);
        return this;
    }

    @Override
    public SchemaHistoryRecord value3(String value) {
        setDescription(value);
        return this;
    }

    @Override
    public SchemaHistoryRecord value4(String value) {
        setType(value);
        return this;
    }

    @Override
    public SchemaHistoryRecord value5(String value) {
        setScript(value);
        return this;
    }

    @Override
    public SchemaHistoryRecord value6(Integer value) {
        setChecksum(value);
        return this;
    }

    @Override
    public SchemaHistoryRecord value7(String value) {
        setInstalledBy(value);
        return this;
    }

    @Override
    public SchemaHistoryRecord value8(LocalDateTime value) {
        setInstalledOn(value);
        return this;
    }

    @Override
    public SchemaHistoryRecord value9(Integer value) {
        setExecutionTime(value);
        return this;
    }

    @Override
    public SchemaHistoryRecord value10(Boolean value) {
        setSuccess(value);
        return this;
    }

    @Override
    public SchemaHistoryRecord values(Integer value1, String value2, String value3, String value4, String value5, Integer value6, String value7, LocalDateTime value8, Integer value9, Boolean value10) {
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
        return this;
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Create a detached SchemaHistoryRecord
     */
    public SchemaHistoryRecord() {
        super(SchemaHistory.SCHEMA_HISTORY);
    }

    /**
     * Create a detached, initialised SchemaHistoryRecord
     */
    public SchemaHistoryRecord(Integer installedRank, String version, String description, String type, String script, Integer checksum, String installedBy, LocalDateTime installedOn, Integer executionTime, Boolean success) {
        super(SchemaHistory.SCHEMA_HISTORY);

        setInstalledRank(installedRank);
        setVersion(version);
        setDescription(description);
        setType(type);
        setScript(script);
        setChecksum(checksum);
        setInstalledBy(installedBy);
        setInstalledOn(installedOn);
        setExecutionTime(executionTime);
        setSuccess(success);
    }
}
