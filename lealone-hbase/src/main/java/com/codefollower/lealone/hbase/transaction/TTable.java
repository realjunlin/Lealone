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
package com.codefollower.lealone.hbase.transaction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.ClientScanner;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.TimeRange;

import com.codefollower.lealone.transaction.Transaction;

/**
 * Provides transactional methods for accessing and modifying a given snapshot
 * of data identified by an opaque {@link Transaction} object.
 * 
 */
public class TTable {

    /** We always ask for CACHE_VERSIONS_OVERHEAD extra versions */
    private static final int CACHE_VERSIONS_OVERHEAD = 3;

    /** How fast do we adapt the average */
    private static final double ALPHA = 0.975;

    /** Average number of versions needed to reach the right snapshot */
    private double versionsAvg = 3;
    private final HTable table;

    public TTable(HTable table) throws IOException {
        this.table = table;
    }

    /**
     * Extracts certain cells from a given row.
     * 
     * @param get
     *            The object that specifies what data to fetch and from which
     *            row.
     * @return The data coming from the specified row, if it exists. If the row
     *         specified doesn't exist, the {@link Result} instance returned
     *         won't contain any {@link KeyValue}, as indicated by
     *         {@link Result#isEmpty()}.
     * @throws IOException
     *             if a remote or network exception occurs.
     */
    public Result get(Transaction transaction, final Get get) throws IOException {
        final int requestedVersions = (int) (versionsAvg + CACHE_VERSIONS_OVERHEAD);
        final long readTimestamp = transaction.getStartTimestamp();
        final Get tget = new Get(get.getRow());
        TimeRange timeRange = get.getTimeRange();
        long startTime = timeRange.getMin();
        long endTime = Math.min(timeRange.getMax(), readTimestamp + 1);
        tget.setTimeRange(startTime, endTime).setMaxVersions(requestedVersions);
        Map<byte[], NavigableSet<byte[]>> kvs = get.getFamilyMap();
        for (Map.Entry<byte[], NavigableSet<byte[]>> entry : kvs.entrySet()) {
            byte[] family = entry.getKey();
            NavigableSet<byte[]> qualifiers = entry.getValue();
            if (qualifiers == null || qualifiers.isEmpty()) {
                tget.addFamily(family);
            } else {
                for (byte[] qualifier : qualifiers) {
                    tget.addColumn(family, qualifier);
                }
            }
        }
        // Return the KVs that belong to the transaction snapshot, ask for more
        // versions if needed
        return new Result(filter(transaction, table.get(tget).list(), requestedVersions));
    }

    /**
     * Returns a scanner on the current table as specified by the {@link Scan}
     * object. Note that the passed {@link Scan}'s start row and caching
     * properties maybe changed.
     * 
     * @param scan
     *            A configured {@link Scan} object.
     * @return A scanner.
     * @throws IOException
     *             if a remote or network exception occurs.
     */
    public ResultScanner getScanner(Transaction transaction, Scan scan) throws IOException {
        Scan tscan = new Scan(scan);
        tscan.setMaxVersions((int) (versionsAvg + CACHE_VERSIONS_OVERHEAD));
        tscan.setTimeRange(0, transaction.getStartTimestamp() + 1);
        TransactionalClientScanner scanner = new TransactionalClientScanner(transaction, getConfiguration(), tscan,
                getTableName(), (int) (versionsAvg + CACHE_VERSIONS_OVERHEAD));
        return scanner;
    }

    /**
     * Filters the raw results returned from HBase and returns only those
     * belonging to the current snapshot, as defined by the transaction
     * object. If the raw results don't contain enough information for a
     * particular qualifier, it will request more versions from HBase.
     * 
     * @param transaction
     *            Defines the current snapshot
     * @param kvs
     *            Raw KVs that we are going to filter
     * @param localVersions
     *            Number of versions requested from hbase
     * @return Filtered KVs belonging to the transaction snapshot
     * @throws IOException
     */
    private List<KeyValue> filter(Transaction transaction, List<KeyValue> kvs, int localVersions) throws IOException {
        if (kvs == null) {
            return Collections.emptyList();
        }

        final int requestVersions = localVersions * 2 + CACHE_VERSIONS_OVERHEAD;

        long startTimestamp = transaction.getStartTimestamp();
        // Filtered kvs
        List<KeyValue> filtered = new ArrayList<KeyValue>();
        // Map from column to older uncommitted timestamp
        List<Get> pendingGets = new ArrayList<Get>();
        ColumnWrapper lastColumn = new ColumnWrapper(null, null);
        long oldestUncommittedTS = Long.MAX_VALUE;
        boolean validRead = true;
        // Number of versions needed to reach a committed value
        int versionsProcessed = 0;

        for (KeyValue kv : kvs) {
            ColumnWrapper currentColumn = new ColumnWrapper(kv.getFamily(), kv.getQualifier());
            if (!currentColumn.equals(lastColumn)) {
                // New column, if we didn't read a committed value for last one,
                // add it to pending
                if (!validRead && versionsProcessed == localVersions) {
                    Get get = new Get(kv.getRow());
                    get.addColumn(kv.getFamily(), kv.getQualifier());
                    get.setMaxVersions(requestVersions); // TODO set maxVersions
                                                         // wisely
                    get.setTimeRange(0, oldestUncommittedTS - 1);
                    pendingGets.add(get);
                }
                validRead = false;
                versionsProcessed = 0;
                oldestUncommittedTS = Long.MAX_VALUE;
                lastColumn = currentColumn;
            }
            if (validRead) {
                // If we already have a committed value for this column, skip kv
                continue;
            }
            versionsProcessed++;
            HRegionLocation location = table.getRegionLocation(kv.getRow(), false);
            if (Filter.validRead(location.getHostnamePort(), kv.getTimestamp(), startTimestamp)) {
                // Valid read, add it to result unless it's a delete
                if (kv.getValueLength() > 0) {
                    filtered.add(kv);
                }
                validRead = true;
                // Update versionsAvg: increase it quickly, decrease it slowly
                versionsAvg = versionsProcessed > versionsAvg ? versionsProcessed : ALPHA * versionsAvg + (1 - ALPHA)
                        * versionsProcessed;
            } else {
                // Uncomitted, keep track of oldest uncommitted timestamp
                oldestUncommittedTS = Math.min(oldestUncommittedTS, kv.getTimestamp());
            }
        }

        // If we have pending columns, request (and filter recursively) them
        if (!pendingGets.isEmpty()) {
            Result[] results = table.get(pendingGets);
            for (Result r : results) {
                filtered.addAll(filter(transaction, r.list(), requestVersions));
            }
        }
        Collections.sort(filtered, KeyValue.COMPARATOR);
        return filtered;
    }

    protected class TransactionalClientScanner extends ClientScanner {
        private Transaction transaction;
        private int maxVersions;

        TransactionalClientScanner(Transaction transaction, Configuration conf, Scan scan, byte[] table, int maxVersions)
                throws IOException {
            super(conf, scan, table);
            this.transaction = transaction;
            this.maxVersions = maxVersions;
        }

        @Override
        public Result next() throws IOException {
            List<KeyValue> filteredResult = Collections.emptyList();
            while (filteredResult.isEmpty()) {
                Result result = super.next();
                if (result == null) {
                    return null;
                }
                filteredResult = filter(transaction, result.list(), maxVersions);
            }
            return new Result(filteredResult);
        }

        // In principle no need to override, copied from super.next(int) to make
        // sure it works even if super.next(int)
        // changes its implementation
        @Override
        public Result[] next(int nbRows) throws IOException {
            // Collect values to be returned here
            ArrayList<Result> resultSets = new ArrayList<Result>(nbRows);
            for (int i = 0; i < nbRows; i++) {
                Result next = next();
                if (next != null) {
                    resultSets.add(next);
                } else {
                    break;
                }
            }
            return resultSets.toArray(new Result[resultSets.size()]);
        }

    }

    /**
     * Gets the name of this table.
     * 
     * @return the table name.
     */
    public byte[] getTableName() {
        return table.getTableName();
    }

    /**
     * Returns the {@link Configuration} object used by this instance.
     * <p>
     * The reference returned is not a copy, so any change made to it will
     * affect this instance.
     */
    public Configuration getConfiguration() {
        return table.getConfiguration();
    }

    /**
     * Gets the {@link HTableDescriptor table descriptor} for this table.
     * 
     * @throws IOException
     *             if a remote or network exception occurs.
     */
    public HTableDescriptor getTableDescriptor() throws IOException {
        return table.getTableDescriptor();
    }

    /**
     * Test for the existence of columns in the table, as specified in the Get.
     * <p>
     * 
     * This will return true if the Get matches one or more keys, false if not.
     * <p>
     * 
     * This is a server-side call so it prevents any data from being transfered
     * to the client.
     * 
     * @param get
     *            the Get
     * @return true if the specified Get matches one or more keys, false if not
     * @throws IOException
     *             e
     */
    public boolean exists(Transaction transaction, Get get) throws IOException {
        Result result = get(transaction, get);
        return !result.isEmpty();
    }

    /**
     * Extracts certain cells from the given rows, in batch.
     * 
     * @param gets
     *            The objects that specify what data to fetch and from which
     *            rows.
     * 
     * @return The data coming from the specified rows, if it exists. If the row
     *         specified doesn't exist, the {@link Result} instance returned
     *         won't contain any {@link KeyValue}, as indicated by
     *         {@link Result#isEmpty()}. If there are any failures even after
     *         retries, there will be a null in the results array for those
     *         Gets, AND an exception will be thrown.
     * @throws IOException
     *             if a remote or network exception occurs.
     * 
     */
    public Result[] get(Transaction transaction, List<Get> gets) throws IOException {
        Result[] results = new Result[gets.size()];
        int i = 0;
        for (Get get : gets) {
            results[i++] = get(transaction, get);
        }
        return results;
    }

    /**
     * Gets a scanner on the current table for the given family.
     * 
     * @param family
     *            The column family to scan.
     * @return A scanner.
     * @throws IOException
     *             if a remote or network exception occurs.
     */
    public ResultScanner getScanner(Transaction transaction, byte[] family) throws IOException {
        Scan scan = new Scan();
        scan.addFamily(family);
        return getScanner(transaction, scan);
    }

    /**
     * Gets a scanner on the current table for the given family and qualifier.
     * 
     * @param family
     *            The column family to scan.
     * @param qualifier
     *            The column qualifier to scan.
     * @return A scanner.
     * @throws IOException
     *             if a remote or network exception occurs.
     */
    public ResultScanner getScanner(Transaction transaction, byte[] family, byte[] qualifier) throws IOException {
        Scan scan = new Scan();
        scan.addColumn(family, qualifier);
        return getScanner(transaction, scan);
    }

    /**
     * Provides access to the underliying HTable in order to configure it or to
     * perform unsafe (non-transactional) operations. The latter would break the
     * transactional guarantees of the whole system.
     * 
     * @return The underlying HTable object
     */
    public HTableInterface getHTable() {
        return table;
    }

    /**
     * Releases any resources held or pending changes in internal buffers.
     * 
     * @throws IOException
     *             if a remote or network exception occurs.
     */
    public void close() throws IOException {
        table.close();
    }

}
