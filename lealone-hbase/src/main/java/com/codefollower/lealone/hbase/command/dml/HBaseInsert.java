/*
 * Copyright 2011 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.codefollower.lealone.hbase.command.dml;

import java.util.ArrayList;

import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;

import com.codefollower.lealone.command.CommandInterface;
import com.codefollower.lealone.command.dml.Insert;
import com.codefollower.lealone.dbobject.table.Column;
import com.codefollower.lealone.engine.Session;
import com.codefollower.lealone.engine.SessionInterface;
import com.codefollower.lealone.engine.UndoLogRecord;
import com.codefollower.lealone.expression.Expression;
import com.codefollower.lealone.hbase.command.CommandProxy;
import com.codefollower.lealone.hbase.command.HBasePrepared;
import com.codefollower.lealone.hbase.dbobject.table.HBaseTable;
import com.codefollower.lealone.hbase.engine.HBaseSession;
import com.codefollower.lealone.hbase.result.HBaseRow;
import com.codefollower.lealone.hbase.util.HBaseUtils;
import com.codefollower.lealone.message.DbException;
import com.codefollower.lealone.result.Row;
import com.codefollower.lealone.util.New;
import com.codefollower.lealone.util.StatementBuilder;
import com.codefollower.lealone.value.Value;
import com.codefollower.lealone.value.ValueString;
import com.codefollower.lealone.value.ValueUuid;

public class HBaseInsert extends Insert implements HBasePrepared {
    private final HBaseSession session;
    private String regionName;
    private byte[] regionNameAsBytes;

    private StatementBuilder alterTable;
    private ArrayList<Column> alterColumns;
    private boolean isBatch = false;

    public HBaseInsert(Session session) {
        super(session);
        this.session = (HBaseSession) session;
    }

    @Override
    public void setSortedInsertMode(boolean sortedInsertMode) {
        //不使用sortedInsertMode，因为只有在PageStore中才用得到
    }

    @Override
    public void prepare() {
        if (session.getAutoCommit() && (query != null || list.size() > 1)) {
            session.setAutoCommit(false);
            isBatch = true;
        }
    }

    @Override
    public int update() {
        HBaseTable table = ((HBaseTable) this.table);
        //当在Parser中解析insert语句时，如果insert中的一些字段是新的，那么会标注字段列表已修改了，
        //并且新字段的类型是未知的，只有在执行insert时再由字段值的实际类型确定字段的类型。
        if (table.isColumnsModified()) {
            alterTable = new StatementBuilder("ALTER TABLE ");
            //不能使用ALTER TABLE t ADD COLUMN(f1 int, f2 int)这样的语法，因为有可能多个RS都在执行这种语句，在Master那会有冲突
            alterTable.append(table.getSQL()).append(" ADD COLUMN IF NOT EXISTS ");

            alterColumns = New.arrayList();
        }
        try {
            int updateCount = super.update();

            if (table.isColumnsModified()) {
                table.setColumnsModified(false);
                SessionInterface si = CommandProxy
                        .getSessionInterface(session.getOriginalProperties(), HBaseUtils.getMasterURL());
                for (Column c : alterColumns) {
                    CommandInterface ci = si.prepareCommand(alterTable + c.getCreateSQL(true), 1);
                    ci.executeUpdate();
                }
                si.close();
            }
            if (isBatch)
                session.commit(false);
            return updateCount;
        } catch (Exception e) {
            if (isBatch)
                session.rollback();
            throw DbException.convert(e);
        } finally {
            if (isBatch)
                session.setAutoCommit(true);
        }
    }

    @Override
    protected Row createRow(int columnLen, Expression[] expr, int rowId) {
        HBaseRow row = (HBaseRow) table.getTemplateRow();
        ValueString rowKey = ValueString.get(getRowKey(rowId));
        row.setRowKey(rowKey);
        row.setRegionName(regionNameAsBytes);

        Put put;
        if (getCommand().getTransaction() != null)
            put = new Put(HBaseUtils.toBytes(rowKey), getCommand().getTransaction().getTransactionId());
        else
            put = new Put(HBaseUtils.toBytes(rowKey));
        row.setPut(put);
        Column c;
        Value v;
        Expression e;
        for (int i = 0; i < columnLen; i++) {
            c = columns[i];
            if (!((HBaseTable) this.table).isStatic() && c.isRowKeyColumn())
                continue;
            e = expr[i];
            if (e != null) {
                // e can be null (DEFAULT)
                e = e.optimize(session);
                v = e.getValue(session);
                if (c.isTypeUnknown()) {
                    c.setType(v.getType());
                    //alterTable.appendExceptFirst(", ");
                    //alterTable.append(c.getCreateSQL());
                    if (alterColumns != null)
                        alterColumns.add(c);
                }
                try {
                    v = c.convert(e.getValue(session));
                    row.setValue(c.getColumnId(), v);

                    put.add(c.getColumnFamilyNameAsBytes(), c.getNameAsBytes(), HBaseUtils.toBytes(v));
                } catch (DbException ex) {
                    throw setRow(ex, rowId, getSQL(expr));
                }
            }
        }
        return row;
    }

    @Override
    public void addRow(Value[] values) {
        HBaseRow row = (HBaseRow) table.getTemplateRow();
        ValueString rowKey = ValueString.get(getRowKey());
        row.setRowKey(rowKey);
        row.setRegionName(regionNameAsBytes);

        Put put;
        if (getCommand().getTransaction() != null)
            put = new Put(HBaseUtils.toBytes(rowKey), getCommand().getTransaction().getTransactionId());
        else
            put = new Put(HBaseUtils.toBytes(rowKey));
        row.setPut(put);
        Column c;
        Value v;

        setCurrentRowNumber(++rowNumber);
        for (int j = 0, len = columns.length; j < len; j++) {
            c = columns[j];
            int index = c.getColumnId();
            try {
                v = values[j];
                if (c.isTypeUnknown()) {
                    c.setType(v.getType());
                    if (alterColumns != null)
                        alterColumns.add(c);
                }
                v = c.convert(values[j]);
                row.setValue(index, v);

                put.add(c.getColumnFamilyNameAsBytes(), c.getNameAsBytes(), HBaseUtils.toBytes(v));
            } catch (DbException ex) {
                throw setRow(ex, rowNumber, getSQL(values));
            }
        }
        table.validateConvertUpdateSequence(session, row);
        boolean done = table.fireBeforeRow(session, null, row);
        if (!done) {
            table.addRow(session, row);
            session.log(table, UndoLogRecord.INSERT, row);
            table.fireAfterRow(session, null, row, false);
        }
    }

    @Override
    public boolean isDistributedSQL() {
        return true;
    }

    @Override
    public String getTableName() {
        return table.getName();
    }

    @Override
    public byte[] getTableNameAsBytes() {
        return ((HBaseTable) table).getTableNameAsBytes();
    }

    @Override
    public String getRowKey() {
        return getRowKey(0);
    }
    
    public String getRowKey(int rowIndex) {
        if (!list.isEmpty() && list.get(rowIndex).length > 0) {
            int columnIndex = 0;
            for (Column c : columns) {
                if (c.isRowKeyColumn()) {
                    return list.get(rowIndex)[columnIndex].getValue(session).getString();
                }
                columnIndex++;
            }
        }
        if (table.isStatic())
            return ValueUuid.getNewRandom().getString();
        return null;
    }

    @Override
    public Value getStartRowKeyValue() {
        return ValueString.get(getRowKey());
    }

    @Override
    public Value getEndRowKeyValue() {
        return ValueString.get(getRowKey());
    }

    @Override
    public String getRegionName() {
        return regionName;
    }

    @Override
    public void setRegionName(String regionName) {
        this.regionName = regionName;
        this.regionNameAsBytes = Bytes.toBytes(regionName);
    }
}
