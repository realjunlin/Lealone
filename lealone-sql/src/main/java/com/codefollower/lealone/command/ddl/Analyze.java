/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.codefollower.lealone.command.ddl;

import com.codefollower.lealone.command.CommandInterface;
import com.codefollower.lealone.command.Prepared;
import com.codefollower.lealone.dbobject.Right;
import com.codefollower.lealone.dbobject.table.Column;
import com.codefollower.lealone.dbobject.table.Table;
import com.codefollower.lealone.engine.Database;
import com.codefollower.lealone.engine.Session;
import com.codefollower.lealone.result.ResultInterface;
import com.codefollower.lealone.util.StatementBuilder;
import com.codefollower.lealone.value.Value;

/**
 * This class represents the statement
 * ANALYZE
 */
public class Analyze extends DefineCommand {

    /**
     * The sample size.
     */
    private int sampleRows;

    public Analyze(Session session) {
        super(session);
        sampleRows = session.getDatabase().getSettings().analyzeSample;
    }

    public int update() {
        session.commit(true);
        session.getUser().checkAdmin();
        Database db = session.getDatabase();
        for (Table table : db.getAllTablesAndViews(false)) {
            analyzeTable(session, table, sampleRows, true);
        }
        return 0;
    }

    /**
     * Analyze this table.
     *
     * @param session the session
     * @param table the table
     * @param sample the number of sample rows
     * @param manual whether the command was called by the user
     */
    public static void analyzeTable(Session session, Table table, int sample, boolean manual) {
        if (!(table.getTableType().equals(Table.TABLE)) || table.isHidden() || session == null) {
            return;
        }
        if (!manual) {
            if (session.getDatabase().isSysTableLocked()) {
                return;
            }
            if (table.hasSelectTrigger()) {
                return;
            }
        }
        if (table.isTemporary() && !table.isGlobalTemporary()
                && session.findLocalTempTable(table.getName()) == null) {
            return;
        }
        if (table.isLockedExclusively() && !table.isLockedExclusivelyBy(session)) {
            return;
        }
        if (!session.getUser().hasRight(table, Right.SELECT)) {
            return;
        }
        if (session.getCancel() != 0) {
            // if the connection is closed and there is something to undo
            return;
        }
        Database db = session.getDatabase();
        StatementBuilder buff = new StatementBuilder("SELECT ");
        Column[] columns = table.getColumns();
        for (Column col : columns) {
            buff.appendExceptFirst(", ");
            int type = col.getType();
            if (type == Value.BLOB || type == Value.CLOB) {
                // can not index LOB columns, so calculating
                // the selectivity is not required
                buff.append("100");
            } else {
                buff.append("SELECTIVITY(").append(col.getSQL()).append(')');
            }
        }
        buff.append(" FROM ").append(table.getSQL());
        if (sample > 0) {
            buff.append(" LIMIT 1 SAMPLE_SIZE ").append(sample);
        }
        String sql = buff.toString();
        Prepared command = session.prepare(sql);
        ResultInterface result = command.query(0);
        result.next();
        for (int j = 0; j < columns.length; j++) {
            int selectivity = result.currentRow()[j].getInt();
            columns[j].setSelectivity(selectivity);
        }
        if (manual) {
            db.update(session, table);
        } else {
            Session s = db.getSystemSession();
            if (s != session) {
                // if the current session is the system session
                // (which is the case if we are within a trigger)
                // then we can't update the statistics because
                // that would unlock all locked objects
                db.update(s, table);
                s.commit(true);
            }
        }
    }

    public void setTop(int top) {
        this.sampleRows = top;
    }

    public int getType() {
        return CommandInterface.ANALYZE;
    }

}
