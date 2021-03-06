/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.end2end.index;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MetaTableAccessor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.IndexHalfStoreFileReaderGenerator;
import org.apache.hadoop.hbase.regionserver.LocalIndexSplitter;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.coprocessor.BaseScannerRegionObserver;
import org.apache.phoenix.end2end.BaseHBaseManagedTimeIT;
import org.apache.phoenix.end2end.Shadower;
import org.apache.phoenix.hbase.index.IndexRegionSplitPolicy;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.jdbc.PhoenixStatement;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTable.IndexType;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.TableNotFoundException;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.MetaDataUtil;
import org.apache.phoenix.util.QueryUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.StringUtil;
import org.apache.phoenix.util.TestUtil;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.Maps;

@RunWith(Parameterized.class)
public class LocalIndexIT extends BaseHBaseManagedTimeIT {

    private static CountDownLatch latch1 = new CountDownLatch(1);
    private static CountDownLatch latch2 = new CountDownLatch(1);
    private static final int WAIT_TIME_SECONDS = 60;
    private String schemaName="TEST";
    private boolean isNamespaceMapped;
    private String tableName = schemaName + ".T";
    private String indexTableName = schemaName + ".I";
    private String indexName = "I";
    private String indexPhysicalTableName;
    private TableName physicalTableName;

    public LocalIndexIT(boolean isNamespaceMapped) {
        this.isNamespaceMapped = isNamespaceMapped;
        this.physicalTableName = SchemaUtil.getPhysicalTableName(tableName.getBytes(), isNamespaceMapped);
        this.indexPhysicalTableName = Bytes
                .toString(MetaDataUtil.getLocalIndexPhysicalName(physicalTableName.getName()));
    }
    
    @BeforeClass 
    @Shadower(classBeingShadowed = BaseHBaseManagedTimeIT.class)
    public static void doSetup() throws Exception {
        Map<String,String> props = Maps.newHashMapWithExpectedSize(3);
        // Drop the HBase table metadata for this test
        props.put(QueryServices.DROP_METADATA_ATTRIB, Boolean.toString(true));
        // Must update config before starting server
        setUpTestDriver(new ReadOnlyProps(props.entrySet().iterator()));
    }

    private void createBaseTable(String tableName, Integer saltBuckets, String splits) throws SQLException {
        Connection conn = getConnection();
        if (isNamespaceMapped) {
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);
        }
        String ddl = "CREATE TABLE " + tableName + " (t_id VARCHAR NOT NULL,\n" +
                "k1 INTEGER NOT NULL,\n" +
                "k2 INTEGER NOT NULL,\n" +
                "k3 INTEGER,\n" +
                "v1 VARCHAR,\n" +
                "CONSTRAINT pk PRIMARY KEY (t_id, k1, k2))\n"
                        + (saltBuckets != null && splits == null ? (" salt_buckets=" + saltBuckets) : ""
                        + (saltBuckets == null && splits != null ? (" split on " + splits) : ""));
        conn.createStatement().execute(ddl);
        conn.close();
    }

    @Parameters(name = "isNamespaceMapped = {0}")
    public static Collection<Boolean> data() {
        return Arrays.asList(true, false);
    }

    @Test
    public void testLocalIndexRoundTrip() throws Exception {
        createBaseTable(tableName, null, null);
        Connection conn1 = DriverManager.getConnection(getUrl());
        conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)");
        conn1.createStatement().executeQuery("SELECT * FROM " + tableName).next();
        PTable localIndex = conn1.unwrap(PhoenixConnection.class).getTable(new PTableKey(null, indexTableName));
        assertEquals(IndexType.LOCAL, localIndex.getIndexType());
        assertNotNull(localIndex.getViewIndexId());
    }

    @Test
    public void testLocalIndexCreationWithSplitsShouldFail() throws Exception {
        createBaseTable(tableName, null, null);
        Connection conn1 = getConnection();
        Connection conn2 = getConnection();
        try {
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)"+" split on (1,2,3)");
            fail("Local index cannot be pre-split");
        } catch (SQLException e) { }
        try {
            conn2.createStatement().executeQuery("SELECT * FROM " + tableName).next();
            conn2.unwrap(PhoenixConnection.class).getTable(new PTableKey(null,indexName));
            fail("Local index should not be created.");
        } catch (TableNotFoundException e) { }
    }

    @Test
    public void testLocalIndexCreationWithSaltingShouldFail() throws Exception {
        createBaseTable(tableName, null, null);
        Connection conn1 = getConnection();
        Connection conn2 = getConnection();
        try {
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)"+" salt_buckets=16");
            fail("Local index cannot be salted.");
        } catch (SQLException e) { }
        try {
            conn2.createStatement().executeQuery("SELECT * FROM " + tableName).next();
            conn2.unwrap(PhoenixConnection.class).getTable(new PTableKey(null,indexName));
            fail("Local index should not be created.");
        } catch (TableNotFoundException e) { }
    }

    @Test
    public void testLocalIndexTableRegionSplitPolicyAndSplitKeys() throws Exception {
        createBaseTable(tableName, null,"('e','i','o')");
        Connection conn1 = getConnection();
        Connection conn2 = getConnection();
        conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)");
        conn2.createStatement().executeQuery("SELECT * FROM " + tableName).next();
        HBaseAdmin admin = driver.getConnectionQueryServices(getUrl(), TestUtil.TEST_PROPERTIES).getAdmin();
        HTableDescriptor htd = admin
                .getTableDescriptor(Bytes.toBytes(indexPhysicalTableName));
        assertEquals(IndexRegionSplitPolicy.class.getName(), htd.getValue(HTableDescriptor.SPLIT_POLICY));
        try (HTable userTable = new HTable(admin.getConfiguration(),
                SchemaUtil.getPhysicalTableName(tableName.getBytes(), isNamespaceMapped))) {
            try (HTable indexTable = new HTable(admin.getConfiguration(), Bytes.toBytes(indexPhysicalTableName))) {
                assertArrayEquals("Both user table and index table should have same split keys.",
                        userTable.getStartKeys(), indexTable.getStartKeys());
            }
        }
    }
    
    public Connection getConnection() throws SQLException{
        Properties props = new Properties();
        props.put(QueryServices.DROP_METADATA_ATTRIB, Boolean.toString(true));
        props.put(QueryServices.IS_NAMESPACE_MAPPING_ENABLED, Boolean.toString(isNamespaceMapped));
        return DriverManager.getConnection(getUrl(),props);
    }

    @Test
    public void testDropLocalIndexTable() throws Exception {
        createBaseTable(tableName, null, null);
        Connection conn1 = getConnection();
        Connection conn2 = getConnection();
        conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)");
        conn2.createStatement().executeQuery("SELECT * FROM " + tableName).next();
        HBaseAdmin admin = driver.getConnectionQueryServices(getUrl(), TestUtil.TEST_PROPERTIES).getAdmin();
        assertTrue("Local index table should be present.", admin.tableExists(indexPhysicalTableName));
        conn1.createStatement().execute("DROP TABLE "+ tableName);
        admin = driver.getConnectionQueryServices(getUrl(), TestUtil.TEST_PROPERTIES).getAdmin();
        assertFalse("Local index table should be deleted.", admin.tableExists(indexPhysicalTableName));
        ResultSet rs = conn2.createStatement().executeQuery("SELECT "
                + PhoenixDatabaseMetaData.SEQUENCE_SCHEMA + ","
                + PhoenixDatabaseMetaData.SEQUENCE_NAME
                + " FROM " + PhoenixDatabaseMetaData.SYSTEM_SEQUENCE);
        assertFalse("View index sequences should be deleted.", rs.next());
    }
    
    @Test
    public void testPutsToLocalIndexTable() throws Exception {
        createBaseTable(tableName, null, "('e','i','o')");
        Connection conn1 = getConnection();
        conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)");
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('b',1,2,4,'z')");
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('f',1,2,3,'z')");
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('j',2,4,2,'a')");
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('q',3,1,1,'c')");
        conn1.commit();
        ResultSet rs = conn1.createStatement().executeQuery("SELECT COUNT(*) FROM " + indexTableName);
        assertTrue(rs.next());
        assertEquals(4, rs.getInt(1));
        HBaseAdmin admin = driver.getConnectionQueryServices(getUrl(), TestUtil.TEST_PROPERTIES).getAdmin();
        HTable indexTable = new HTable(admin.getConfiguration(), indexPhysicalTableName);
        Pair<byte[][], byte[][]> startEndKeys = indexTable.getStartEndKeys();
        byte[][] startKeys = startEndKeys.getFirst();
        byte[][] endKeys = startEndKeys.getSecond();
        for (int i = 0; i < startKeys.length; i++) {
            Scan s = new Scan();
            s.setStartRow(startKeys[i]);
            s.setStopRow(endKeys[i]);
            ResultScanner scanner = indexTable.getScanner(s);
            int count = 0;
            for(Result r:scanner){
                count++;
            }
            scanner.close();
            assertEquals(1, count);
        }
        indexTable.close();
    }
    
    @Test
    public void testBuildIndexWhenUserTableAlreadyHasData() throws Exception {
        createBaseTable(tableName, null, "('e','i','o')");
        Connection conn1 = DriverManager.getConnection(getUrl());
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('b',1,2,4,'z')");
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('f',1,2,3,'z')");
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('j',2,4,2,'a')");
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('q',3,1,1,'c')");
        conn1.commit();
        conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)");
        ResultSet rs = conn1.createStatement().executeQuery("SELECT COUNT(*) FROM " + indexTableName);
        assertTrue(rs.next());
        assertEquals(4, rs.getInt(1));
        HBaseAdmin admin = driver.getConnectionQueryServices(getUrl(), TestUtil.TEST_PROPERTIES).getAdmin();
        HTable indexTable = new HTable(admin.getConfiguration(),Bytes.toBytes(indexPhysicalTableName));
        Pair<byte[][], byte[][]> startEndKeys = indexTable.getStartEndKeys();
        byte[][] startKeys = startEndKeys.getFirst();
        byte[][] endKeys = startEndKeys.getSecond();
        for (int i = 0; i < startKeys.length; i++) {
            Scan s = new Scan();
            s.setStartRow(startKeys[i]);
            s.setStopRow(endKeys[i]);
            ResultScanner scanner = indexTable.getScanner(s);
            int count = 0;
            for(Result r:scanner){
                count++;
            }
            scanner.close();
            assertEquals(1, count);
        }
        indexTable.close();
    }

    @Test
    public void testLocalIndexScan() throws Exception {
        createBaseTable(tableName, null, "('e','i','o')");
        Connection conn1 = DriverManager.getConnection(getUrl());
        try{
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('a',1,2,5,'y')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('b',1,2,4,'z')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('f',1,2,3,'a')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('e',1,2,3,'b')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('j',2,4,2,'a')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('q',3,1,1,'c')");
            conn1.commit();
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)");
            
            ResultSet rs = conn1.createStatement().executeQuery("SELECT COUNT(*) FROM " + indexTableName);
            assertTrue(rs.next());
            
            HBaseAdmin admin = driver.getConnectionQueryServices(getUrl(), TestUtil.TEST_PROPERTIES).getAdmin();
            int numRegions = admin.getTableRegions(physicalTableName).size();
            
            String query = "SELECT * FROM " + tableName +" where v1 like 'a%'";
            rs = conn1.createStatement().executeQuery("EXPLAIN "+ query);
            
            assertEquals(
                "CLIENT PARALLEL " + numRegions + "-WAY RANGE SCAN OVER "
                        + indexPhysicalTableName + " [-32768,'a'] - [-32768,'b']\n"
                                + "    SERVER FILTER BY FIRST KEY ONLY\n"
                                + "CLIENT MERGE SORT",
                        QueryUtil.getExplainPlan(rs));
            
            rs = conn1.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("f", rs.getString("t_id"));
            assertEquals(1, rs.getInt("k1"));
            assertEquals(2, rs.getInt("k2"));
            assertEquals("a", rs.getString("v1"));
            assertEquals(3, rs.getInt("k3"));
            assertTrue(rs.next());
            assertEquals("j", rs.getString("t_id"));
            assertEquals(2, rs.getInt("k1"));
            assertEquals(4, rs.getInt("k2"));
            assertEquals("a", rs.getString("v1"));
            assertEquals(2, rs.getInt("k3"));
            assertFalse(rs.next());
            query = "SELECT t_id, k1, k2,V1 FROM " + tableName +" where v1='a'";
            rs = conn1.createStatement().executeQuery("EXPLAIN "+ query);
            
            assertEquals(
                "CLIENT PARALLEL " + numRegions + "-WAY RANGE SCAN OVER "
                        + indexPhysicalTableName + " [-32768,'a']\n"
                        + "    SERVER FILTER BY FIRST KEY ONLY\n"
                        + "CLIENT MERGE SORT",
                        QueryUtil.getExplainPlan(rs));
            
            rs = conn1.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("f", rs.getString("t_id"));
            assertEquals(1, rs.getInt("k1"));
            assertEquals(2, rs.getInt("k2"));
            assertTrue(rs.next());
            assertEquals("j", rs.getString("t_id"));
            assertEquals(2, rs.getInt("k1"));
            assertEquals(4, rs.getInt("k2"));
            assertFalse(rs.next());
            query = "SELECT t_id, k1, k2,V1, k3 FROM " + tableName +" where v1<='z' order by k3";
            rs = conn1.createStatement().executeQuery("EXPLAIN "+ query);
            
            assertEquals("CLIENT PARALLEL " + numRegions + "-WAY RANGE SCAN OVER " + indexPhysicalTableName
                    + " [-32768,*] - [-32768,'z']\n" + "    SERVER FILTER BY FIRST KEY ONLY\n"
                    + "    SERVER SORTED BY [\"K3\"]\n" + "CLIENT MERGE SORT", QueryUtil.getExplainPlan(rs));
 
            rs = conn1.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("k3"));
            assertTrue(rs.next());
            assertEquals(2, rs.getInt("k3"));
            assertTrue(rs.next());
            assertEquals(3, rs.getInt("k3"));
            assertTrue(rs.next());
            assertEquals(3, rs.getInt("k3"));
            assertTrue(rs.next());
            assertEquals(4, rs.getInt("k3"));
            assertTrue(rs.next());
            assertEquals(5, rs.getInt("k3"));
            assertFalse(rs.next());
            
            query = "SELECT t_id, k1, k2,v1 from " + tableName + " order by V1,t_id";
            rs = conn1.createStatement().executeQuery("EXPLAIN " + query);
            
            assertEquals(
                "CLIENT PARALLEL " + numRegions + "-WAY RANGE SCAN OVER "
                        + indexPhysicalTableName +" [-32768]\n"
                        + "    SERVER FILTER BY FIRST KEY ONLY\n"
                        + "CLIENT MERGE SORT",
                QueryUtil.getExplainPlan(rs));
            
            rs = conn1.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("f", rs.getString("t_id"));
            assertEquals(1, rs.getInt("k1"));
            assertEquals(2, rs.getInt("k2"));
            assertEquals("a", rs.getString("V1"));
            assertTrue(rs.next());
            assertEquals("j", rs.getString("t_id"));
            assertEquals(2, rs.getInt("k1"));
            assertEquals(4, rs.getInt("k2"));
            assertEquals("a", rs.getString("V1"));
            assertTrue(rs.next());
            assertEquals("e", rs.getString("t_id"));
            assertEquals(1, rs.getInt("k1"));
            assertEquals(2, rs.getInt("k2"));
            assertEquals("b", rs.getString("V1"));
            assertTrue(rs.next());
            assertEquals("q", rs.getString("t_id"));
            assertEquals(3, rs.getInt("k1"));
            assertEquals(1, rs.getInt("k2"));
            assertEquals("c", rs.getString("V1"));
            assertTrue(rs.next());
            assertEquals("a", rs.getString("t_id"));
            assertEquals(1, rs.getInt("k1"));
            assertEquals(2, rs.getInt("k2"));
            assertEquals("y", rs.getString("V1"));
            assertTrue(rs.next());
            assertEquals("b", rs.getString("t_id"));
            assertEquals(1, rs.getInt("k1"));
            assertEquals(2, rs.getInt("k2"));
            assertEquals("z", rs.getString("V1"));
        } finally {
            conn1.close();
        }
    }

    @Test
    public void testLocalIndexScanJoinColumnsFromDataTable() throws Exception {
        createBaseTable(tableName, null, "('e','i','o')");
        Connection conn1 = getConnection();
        try{
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('b',1,2,4,'z')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('f',1,2,3,'a')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('j',2,4,2,'a')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('q',3,1,1,'c')");
            conn1.commit();
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)");
            
            ResultSet rs = conn1.createStatement().executeQuery("SELECT COUNT(*) FROM " + indexTableName);
            assertTrue(rs.next());
            
            HBaseAdmin admin = driver.getConnectionQueryServices(getUrl(), TestUtil.TEST_PROPERTIES).getAdmin();
            int numRegions = admin.getTableRegions(physicalTableName).size();
            
            String query = "SELECT t_id, k1, k2, k3, V1 FROM " + tableName +" where v1='a'";
            rs = conn1.createStatement().executeQuery("EXPLAIN "+ query);
            
            assertEquals(
                "CLIENT PARALLEL " + numRegions + "-WAY RANGE SCAN OVER "
                        + indexPhysicalTableName + " [-32768,'a']\n"
                                + "    SERVER FILTER BY FIRST KEY ONLY\n"
                                + "CLIENT MERGE SORT",
                        QueryUtil.getExplainPlan(rs));
            
            rs = conn1.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("f", rs.getString("t_id"));
            assertEquals(1, rs.getInt("k1"));
            assertEquals(2, rs.getInt("k2"));
            assertEquals(3, rs.getInt("k3"));
            assertTrue(rs.next());
            assertEquals("j", rs.getString("t_id"));
            assertEquals(2, rs.getInt("k1"));
            assertEquals(4, rs.getInt("k2"));
            assertEquals(2, rs.getInt("k3"));
            assertFalse(rs.next());
            
            query = "SELECT t_id, k1, k2, k3, V1 from " + tableName + "  where v1<='z' order by V1,t_id";
            rs = conn1.createStatement().executeQuery("EXPLAIN " + query);
            
            assertEquals(
                "CLIENT PARALLEL " + numRegions + "-WAY RANGE SCAN OVER "
                        + indexPhysicalTableName +" [-32768,*] - [-32768,'z']\n"
                        + "    SERVER FILTER BY FIRST KEY ONLY\n"
                         + "CLIENT MERGE SORT",
                QueryUtil.getExplainPlan(rs));
            
            rs = conn1.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("f", rs.getString("t_id"));
            assertEquals(1, rs.getInt("k1"));
            assertEquals(2, rs.getInt("k2"));
            assertEquals(3, rs.getInt("k3"));
            assertEquals("a", rs.getString("V1"));
            assertTrue(rs.next());
            assertEquals("j", rs.getString("t_id"));
            assertEquals(2, rs.getInt("k1"));
            assertEquals(4, rs.getInt("k2"));
            assertEquals(2, rs.getInt("k3"));
            assertEquals("a", rs.getString("V1"));
            assertTrue(rs.next());
            assertEquals("q", rs.getString("t_id"));
            assertEquals(3, rs.getInt("k1"));
            assertEquals(1, rs.getInt("k2"));
            assertEquals(1, rs.getInt("k3"));
            assertEquals("c", rs.getString("V1"));
            assertTrue(rs.next());
            assertEquals("b", rs.getString("t_id"));
            assertEquals(1, rs.getInt("k1"));
            assertEquals(2, rs.getInt("k2"));
            assertEquals(4, rs.getInt("k3"));
            assertEquals("z", rs.getString("V1"));
            
            query = "SELECT t_id, V1, k3 from " + tableName + "  where v1 <='z' group by v1,t_id, k3";
            rs = conn1.createStatement().executeQuery("EXPLAIN " + query);
            
            assertEquals(
                "CLIENT PARALLEL " + numRegions + "-WAY RANGE SCAN OVER "
                        + indexPhysicalTableName +" [-32768,*] - [-32768,'z']\n"
                        + "    SERVER FILTER BY FIRST KEY ONLY\n"
                        + "    SERVER AGGREGATE INTO DISTINCT ROWS BY [\"V1\", \"T_ID\", \"K3\"]\nCLIENT MERGE SORT",
                QueryUtil.getExplainPlan(rs));
            
            rs = conn1.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("f", rs.getString("t_id"));
            assertEquals(3, rs.getInt("k3"));
            assertEquals("a", rs.getString("V1"));
            assertTrue(rs.next());
            assertEquals("j", rs.getString("t_id"));
            assertEquals(2, rs.getInt("k3"));
            assertEquals("a", rs.getString("V1"));
            assertTrue(rs.next());
            assertEquals("q", rs.getString("t_id"));
            assertEquals(1, rs.getInt("k3"));
            assertEquals("c", rs.getString("V1"));
            assertTrue(rs.next());
            assertEquals("b", rs.getString("t_id"));
            assertEquals(4, rs.getInt("k3"));
            assertEquals("z", rs.getString("V1"));
            
            query = "SELECT v1,sum(k3) from " + tableName + " where v1 <='z'  group by v1 order by v1";
            
            rs = conn1.createStatement().executeQuery("EXPLAIN " + query);
            assertEquals(
                "CLIENT PARALLEL " + numRegions + "-WAY RANGE SCAN OVER "
                        + indexPhysicalTableName +" [-32768,*] - [-32768,'z']\n"
                        + "    SERVER FILTER BY FIRST KEY ONLY\n"
                        + "    SERVER AGGREGATE INTO ORDERED DISTINCT ROWS BY [\"V1\"]\nCLIENT MERGE SORT",
                QueryUtil.getExplainPlan(rs));
            
            PhoenixStatement stmt = conn1.createStatement().unwrap(PhoenixStatement.class);
            rs = stmt.executeQuery(query);
            QueryPlan plan = stmt.getQueryPlan();
            assertEquals(indexTableName, plan.getContext().getCurrentTable().getTable().getName().getString());
            assertEquals(BaseScannerRegionObserver.KEY_ORDERED_GROUP_BY_EXPRESSIONS, plan.getGroupBy().getScanAttribName());
            assertTrue(rs.next());
            assertEquals("a", rs.getString(1));
            assertEquals(5, rs.getInt(2));
            assertTrue(rs.next());
            assertEquals("c", rs.getString(1));
            assertEquals(1, rs.getInt(2));
            assertTrue(rs.next());
            assertEquals("z", rs.getString(1));
            assertEquals(4, rs.getInt(2));
       } finally {
            conn1.close();
        }
    }

    @Test
    public void testIndexPlanSelectionIfBothGlobalAndLocalIndexesHasSameColumnsAndOrder() throws Exception {
        createBaseTable(tableName, null, "('e','i','o')");
        Connection conn1 = getConnection();
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('b',1,2,4,'z')");
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('f',1,2,3,'a')");
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('j',2,4,3,'a')");
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('q',3,1,1,'c')");
        conn1.commit();
        conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)");
        conn1.createStatement().execute("CREATE INDEX " + indexName + "2" + " ON " + tableName + "(v1)");
        String query = "SELECT t_id, k1, k2,V1 FROM " + tableName +" where v1='a'";
        ResultSet rs1 = conn1.createStatement().executeQuery("EXPLAIN "+ query);
        assertEquals("CLIENT PARALLEL 1-WAY RANGE SCAN OVER "
                + SchemaUtil.getPhysicalTableName(Bytes.toBytes(indexTableName), isNamespaceMapped) + "2" + " ['a']\n"
                + "    SERVER FILTER BY FIRST KEY ONLY", QueryUtil.getExplainPlan(rs1));
        conn1.close();
    }

    @Test
    public void testDropLocalIndexShouldDeleteDataFromLocalIndexTable() throws Exception {
        createBaseTable(tableName, null, "('e','i','o')");
        Connection conn1 = DriverManager.getConnection(getUrl());
        try {
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('b',1,2,4,'z')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('f',1,2,3,'a')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('j',2,4,2,'a')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('q',3,1,1,'c')");
            conn1.commit();
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)");
            conn1.createStatement().execute("DROP INDEX " + indexName + " ON " + tableName);
            HBaseAdmin admin = driver.getConnectionQueryServices(getUrl(), TestUtil.TEST_PROPERTIES).getAdmin();
            HTable indexTable = new HTable(admin.getConfiguration() ,TableName.valueOf(MetaDataUtil.getLocalIndexTableName(tableName)));
            Pair<byte[][], byte[][]> startEndKeys = indexTable.getStartEndKeys();
            byte[][] startKeys = startEndKeys.getFirst();
            byte[][] endKeys = startEndKeys.getSecond();
            // No entry should be present in local index table after drop index.
            for (int i = 0; i < startKeys.length; i++) {
                Scan s = new Scan();
                s.setStartRow(startKeys[i]);
                s.setStopRow(endKeys[i]);
                ResultScanner scanner = indexTable.getScanner(s);
                int count = 0;
                for(Result r:scanner){
                    count++;
                }
                scanner.close();
                assertEquals(0, count);
            }
            indexTable.close();
        } finally {
            conn1.close();
        }
    }

    @Test
    public void testLocalIndexRowsShouldBeDeletedWhenUserTableRowsDeleted() throws Exception {
        createBaseTable(tableName, null, "('e','i','o')");
        Connection conn1 = DriverManager.getConnection(getUrl());
        try {
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('b',1,2,4,'z')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('f',1,2,3,'a')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('j',2,4,2,'a')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('q',3,1,1,'c')");
            conn1.commit();
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)");
            conn1.createStatement().execute("DELETE FROM " + tableName + " where v1='a'");
            conn1.commit();
            conn1 = DriverManager.getConnection(getUrl());
            ResultSet rs = conn1.createStatement().executeQuery("SELECT COUNT(*) FROM " + indexTableName);
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
        } finally {
            conn1.close();
        }
    }
    
    @Test
    public void testScanWhenATableHasMultipleLocalIndexes() throws Exception {
        createBaseTable(tableName, null, "('e','i','o')");
        Connection conn1 = DriverManager.getConnection(getUrl());
        try {
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('b',1,2,4,'z')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('f',1,2,3,'a')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('j',2,4,2,'a')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('q',3,1,1,'c')");
            conn1.commit();
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)");
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + "2 ON " + tableName + "(k3)");
            conn1.commit();
            conn1 = DriverManager.getConnection(getUrl());
            ResultSet rs = conn1.createStatement().executeQuery("SELECT COUNT(*) FROM " + tableName);
            assertTrue(rs.next());
            assertEquals(4, rs.getInt(1));
        } finally {
            conn1.close();
        }
    }

    @Test
    public void testLocalIndexesOnTableWithImmutableRows() throws Exception {
        createBaseTable(tableName, null, "('e','i','o')");
        Connection conn1 = getConnection();
        try {
            conn1.createStatement().execute("ALTER TABLE "+ tableName + " SET IMMUTABLE_ROWS=true");
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)");
            conn1.createStatement().execute("CREATE INDEX " + indexName + "2 ON " + tableName + "(k3)");
            conn1.commit();
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('b',1,2,4,'z')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('f',1,2,3,'a')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('j',2,4,2,'a')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('q',3,1,1,'c')");
            conn1.commit();
            conn1 = DriverManager.getConnection(getUrl());
            ResultSet rs = conn1.createStatement().executeQuery("SELECT COUNT(*) FROM " + tableName);
            assertTrue(rs.next());
            assertEquals(4, rs.getInt(1));
            rs = conn1.createStatement().executeQuery("SELECT v1 FROM " + tableName);
            assertTrue(rs.next());
            assertEquals("a", rs.getString("v1"));
            assertTrue(rs.next());
            assertEquals("a", rs.getString("v1"));
            assertTrue(rs.next());
            assertEquals("c", rs.getString("v1"));
            assertTrue(rs.next());
            assertEquals("z", rs.getString("v1"));
            assertFalse(rs.next());
            rs = conn1.createStatement().executeQuery("SELECT k3 FROM " + tableName);
            assertTrue(rs.next());
            assertEquals(1, rs.getInt("k3"));
            assertTrue(rs.next());
            assertEquals(2, rs.getInt("k3"));
            assertTrue(rs.next());
            assertEquals(3, rs.getInt("k3"));
            assertTrue(rs.next());
            assertEquals(4, rs.getInt("k3"));
            assertFalse(rs.next());
        } finally {
            conn1.close();
        }
    }

    @Test
    public void testLocalIndexScanWithInList() throws Exception {
        createBaseTable(tableName, null, "('e','i','o')");
        Connection conn1 = DriverManager.getConnection(getUrl());
        try{
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('b',1,2,4,'z')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('f',1,2,3,'a')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('j',2,4,2,'a')");
            conn1.createStatement().execute("UPSERT INTO " + tableName + " values('q',3,1,1,'c')");
            conn1.commit();
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1) include (k3)");
            
            ResultSet rs = conn1.createStatement().executeQuery("SELECT COUNT(*) FROM " + indexTableName);
            assertTrue(rs.next());
            
            String query = "SELECT t_id FROM " + tableName +" where (v1,k3) IN (('z',4),('a',2))";
            rs = conn1.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("j", rs.getString("t_id"));
            assertTrue(rs.next());
            assertEquals("b", rs.getString("t_id"));
            assertFalse(rs.next());
       } finally {
            conn1.close();
        }
    }

    @Test
    public void testLocalIndexCreationWithDefaultFamilyOption() throws Exception {
        Connection conn1 = DriverManager.getConnection(getUrl());
        try{
            Statement statement = conn1.createStatement();
            statement.execute("create table example (id integer not null,fn varchar,"
                    + "ln varchar constraint pk primary key(id)) DEFAULT_COLUMN_FAMILY='F'");
            statement.execute("upsert into example values(1,'fn','ln')");
            statement
                    .execute("create local index my_idx on example (fn)");
            statement.execute("upsert into example values(2,'fn1','ln1')");
            ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM my_idx");
            assertTrue(rs.next());
       } finally {
            conn1.close();
        }
    }

    @Test
    public void testLocalIndexScanAfterRegionSplit() throws Exception {
        if (isNamespaceMapped) { return; }
        createBaseTable(tableName, null, "('e','j','o')");
        Connection conn1 = getConnection();
        try{
            String[] strings = {"a","b","c","d","e","f","g","h","i","j","k","l","m","n","o","p","q","r","s","t","u","v","w","x","y","z"};
            for (int i = 0; i < 26; i++) {
                conn1.createStatement().execute(
                    "UPSERT INTO " + tableName + " values('"+strings[i]+"'," + i + ","
                            + (i + 1) + "," + (i + 2) + ",'" + strings[25 - i] + "')");
            }
            conn1.commit();
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)");
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + "_2 ON " + tableName + "(k3)");

            ResultSet rs = conn1.createStatement().executeQuery("SELECT * FROM " + tableName);
            assertTrue(rs.next());
            
            HBaseAdmin admin = conn1.unwrap(PhoenixConnection.class).getQueryServices().getAdmin();
            for (int i = 1; i < 5; i++) {
                admin.split(physicalTableName, ByteUtil.concat(Bytes.toBytes(strings[3*i])));
                List<HRegionInfo> regionsOfUserTable =
                        MetaTableAccessor.getTableRegions(getUtility().getZooKeeperWatcher(), admin.getConnection(),
                                physicalTableName, false);

                while (regionsOfUserTable.size() != (4+i)) {
                    Thread.sleep(100);
                    regionsOfUserTable = MetaTableAccessor.getTableRegions(getUtility().getZooKeeperWatcher(),
                            admin.getConnection(), physicalTableName, false);
                }
                assertEquals(4+i, regionsOfUserTable.size());
                TableName indexTable =
                        TableName.valueOf(indexPhysicalTableName);
                List<HRegionInfo> regionsOfIndexTable =
                        MetaTableAccessor.getTableRegions(getUtility().getZooKeeperWatcher(),
                                admin.getConnection(), indexTable, false);

                while (regionsOfIndexTable.size() != (4 + i)) {
                    Thread.sleep(100);
                    regionsOfIndexTable = MetaTableAccessor.getTableRegions(getUtility().getZooKeeperWatcher(),
                            admin.getConnection(), indexTable, false);
                }
                assertEquals(4 + i, regionsOfIndexTable.size());
                String[] tIdColumnValues = new String[26]; 
                String[] v1ColumnValues = new String[26];
                int[] k1ColumnValue = new int[26];
                String query = "SELECT t_id,k1,v1 FROM " + tableName;
                rs = conn1.createStatement().executeQuery(query);
                Thread.sleep(1000);
                for (int j = 0; j < 26; j++) {
                    assertTrue(rs.next());
                    tIdColumnValues[j] = rs.getString("t_id");
                    k1ColumnValue[j] = rs.getInt("k1");
                    v1ColumnValues[j] = rs.getString("V1");
                }
                Arrays.sort(tIdColumnValues);
                Arrays.sort(v1ColumnValues);
                Arrays.sort(k1ColumnValue);
                assertTrue(Arrays.equals(strings, tIdColumnValues));
                assertTrue(Arrays.equals(strings, v1ColumnValues));
                for(int m=0;m<26;m++) {
                    assertEquals(m, k1ColumnValue[m]);
                }

                rs = conn1.createStatement().executeQuery("EXPLAIN " + query);
                assertEquals(
                        "CLIENT PARALLEL " + (4 + i) + "-WAY RANGE SCAN OVER "
                                + indexPhysicalTableName + " [-32768]\n"
                                        + "    SERVER FILTER BY FIRST KEY ONLY\n"
                                + "CLIENT MERGE SORT", QueryUtil.getExplainPlan(rs));
                
                query = "SELECT t_id,k1,k3 FROM " + tableName;
                rs = conn1.createStatement().executeQuery("EXPLAIN "+query);
                assertEquals(
                    "CLIENT PARALLEL "
                            + ((strings[3 * i].compareTo("j") < 0) ? (4 + i) : (4 + i - 1))
                            + "-WAY RANGE SCAN OVER "
                            + indexPhysicalTableName + " [-32767]\n"
                                    + "    SERVER FILTER BY FIRST KEY ONLY\n"
                            + "CLIENT MERGE SORT", QueryUtil.getExplainPlan(rs));
                rs = conn1.createStatement().executeQuery(query);
                Thread.sleep(1000);
                int[] k3ColumnValue = new int[26];
                for (int j = 0; j < 26; j++) {
                    assertTrue(rs.next());
                    tIdColumnValues[j] = rs.getString("t_id");
                    k1ColumnValue[j] = rs.getInt("k1");
                    k3ColumnValue[j] = rs.getInt("k3");
                }
                Arrays.sort(tIdColumnValues);
                Arrays.sort(k1ColumnValue);
                Arrays.sort(k3ColumnValue);
                assertTrue(Arrays.equals(strings, tIdColumnValues));
                for(int m=0;m<26;m++) {
                    assertEquals(m, k1ColumnValue[m]);
                    assertEquals(m+2, k3ColumnValue[m]);
                }
            }
       } finally {
            conn1.close();
        }
    }

    @Test
    public void testLocalIndexScanWithSmallChunks() throws Exception {
        createBaseTable(tableName, 3, null);
        Properties props = new Properties();
        props.setProperty(QueryServices.SCAN_RESULT_CHUNK_SIZE, "2");
        props.setProperty(QueryServices.IS_NAMESPACE_MAPPING_ENABLED, Boolean.toString(isNamespaceMapped));
        Connection conn1 = DriverManager.getConnection(getUrl(), props);
        try{
            String[] strings = {"a","b","c","d","e","f","g","h","i","j","k","l","m","n","o","p","q","r","s","t","u","v","w","x","y","z"};
            for (int i = 0; i < 26; i++) {
               conn1.createStatement().execute(
                    "UPSERT INTO " + tableName + " values('"+strings[i]+"'," + i + ","
                            + (i + 1) + "," + (i + 2) + ",'" + strings[25 - i] + "')");
            }
            conn1.commit();
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)");
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + "_2 ON " + tableName + "(k3)");

            ResultSet rs = conn1.createStatement().executeQuery("SELECT * FROM " + tableName);
            assertTrue(rs.next());

            String query = "SELECT t_id,k1,v1 FROM " + tableName;
            rs = conn1.createStatement().executeQuery(query);
            for (int j = 0; j < 26; j++) {
                assertTrue(rs.next());
                assertEquals(strings[25 - j], rs.getString("t_id"));
                assertEquals(25 - j, rs.getInt("k1"));
                assertEquals(strings[j], rs.getString("V1"));
            }
            query = "SELECT t_id,k1,k3 FROM " + tableName;
            rs = conn1.createStatement().executeQuery(query);
            Thread.sleep(1000);
            for (int j = 0; j < 26; j++) {
                assertTrue(rs.next());
                assertEquals(strings[j], rs.getString("t_id"));
                assertEquals(j, rs.getInt("k1"));
                assertEquals(j + 2, rs.getInt("k3"));
            }
       } finally {
            conn1.close();
        }
    }

    @Test
    public void testLocalIndexScanAfterRegionsMerge() throws Exception {
        if (isNamespaceMapped) { return; }
        createBaseTable(tableName, null, "('e','j','o')");
        Connection conn1 = getConnection();
        try{
            String[] strings = {"a","b","c","d","e","f","g","h","i","j","k","l","m","n","o","p","q","r","s","t","u","v","w","x","y","z"};
            for (int i = 0; i < 26; i++) {
                conn1.createStatement().execute(
                    "UPSERT INTO " + tableName + " values('"+strings[i]+"'," + i + ","
                            + (i + 1) + "," + (i + 2) + ",'" + strings[25 - i] + "')");
            }
            conn1.commit();
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName + "(v1)");
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + "_2 ON " + tableName + "(k3)");

            ResultSet rs = conn1.createStatement().executeQuery("SELECT * FROM " + tableName);
            assertTrue(rs.next());

            HBaseAdmin admin = conn1.unwrap(PhoenixConnection.class).getQueryServices().getAdmin();
            List<HRegionInfo> regionsOfUserTable =
                    MetaTableAccessor.getTableRegions(getUtility().getZooKeeperWatcher(), admin.getConnection(),
                        physicalTableName, false);
            admin.mergeRegions(regionsOfUserTable.get(0).getEncodedNameAsBytes(),
                regionsOfUserTable.get(1).getEncodedNameAsBytes(), false);
            regionsOfUserTable =
                    MetaTableAccessor.getTableRegions(getUtility().getZooKeeperWatcher(), admin.getConnection(),
                            physicalTableName, false);

            while (regionsOfUserTable.size() != 3) {
                Thread.sleep(100);
                regionsOfUserTable = MetaTableAccessor.getTableRegions(getUtility().getZooKeeperWatcher(),
                        admin.getConnection(), physicalTableName, false);
            }
            assertEquals(3, regionsOfUserTable.size());
            TableName indexTable =
                    TableName.valueOf(indexPhysicalTableName);
            List<HRegionInfo> regionsOfIndexTable =
                    MetaTableAccessor.getTableRegions(getUtility().getZooKeeperWatcher(),
                            admin.getConnection(), indexTable, false);

            while (regionsOfIndexTable.size() != 3) {
                Thread.sleep(100);
                regionsOfIndexTable = MetaTableAccessor.getTableRegions(
                        getUtility().getZooKeeperWatcher(), admin.getConnection(), indexTable, false);
            }
            assertEquals(3, regionsOfIndexTable.size());
            String query = "SELECT t_id,k1,v1 FROM " + tableName;
            rs = conn1.createStatement().executeQuery(query);
            Thread.sleep(1000);
            for (int j = 0; j < 26; j++) {
                assertTrue(rs.next());
                assertEquals(strings[25 - j], rs.getString("t_id"));
                assertEquals(25 - j, rs.getInt("k1"));
                assertEquals(strings[j], rs.getString("V1"));
            }
            rs = conn1.createStatement().executeQuery("EXPLAIN " + query);
            assertEquals(
                "CLIENT PARALLEL " + 3 + "-WAY RANGE SCAN OVER "
                        + indexPhysicalTableName
                        + " [-32768]\n" + "    SERVER FILTER BY FIRST KEY ONLY\n"
                        + "CLIENT MERGE SORT", QueryUtil.getExplainPlan(rs));

            query = "SELECT t_id,k1,k3 FROM " + tableName;
            rs = conn1.createStatement().executeQuery("EXPLAIN " + query);
            assertEquals(
                "CLIENT PARALLEL " + 3 + "-WAY RANGE SCAN OVER "
                        + indexPhysicalTableName
                        + " [-32767]\n" + "    SERVER FILTER BY FIRST KEY ONLY\n"
                        + "CLIENT MERGE SORT", QueryUtil.getExplainPlan(rs));

            rs = conn1.createStatement().executeQuery(query);
            Thread.sleep(1000);
            for (int j = 0; j < 26; j++) {
                assertTrue(rs.next());
                assertEquals(strings[j], rs.getString("t_id"));
                assertEquals(j, rs.getInt("k1"));
                assertEquals(j + 2, rs.getInt("k3"));
            }
       } finally {
            conn1.close();
        }
    }

    @Test
    public void testLocalIndexStateWhenSplittingInProgress() throws Exception {
        if (isNamespaceMapped) { return; }
        createBaseTable(tableName+"2", null, "('e','j','o')");
        Connection conn1 = getConnection();
        try{
            String[] strings = {"a","b","c","d","e","f","g","h","i","j","k","l","m","n","o","p","q","r","s","t","u","v","w","x","y","z"};
            for (int i = 0; i < 26; i++) {
                conn1.createStatement().execute(
                    "UPSERT INTO " + tableName+"2" + " values('"+strings[i]+"'," + i + ","
                            + (i + 1) + "," + (i + 2) + ",'" + strings[25 - i] + "')");
            }
            conn1.commit();
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + " ON " + tableName+"2" + "(v1)");
            conn1.createStatement().execute("CREATE LOCAL INDEX " + indexName + "_2 ON " + tableName+"2" + "(k3)");

            ResultSet rs = conn1.createStatement().executeQuery("SELECT * FROM " + tableName+"2");
            assertTrue(rs.next());
            HBaseAdmin admin = driver.getConnectionQueryServices(getUrl(), TestUtil.TEST_PROPERTIES).getAdmin();
            HTableDescriptor tableDesc = admin.getTableDescriptor(SchemaUtil.getPhysicalTableName((tableName+"2").getBytes(),isNamespaceMapped));
            tableDesc.removeCoprocessor(LocalIndexSplitter.class.getName());
            tableDesc.addCoprocessor(MockedLocalIndexSplitter.class.getName(), null,
                1, null);
            admin.disableTable(tableDesc.getTableName());
            admin.modifyTable(tableDesc.getTableName(), tableDesc);
            admin.enableTable(tableDesc.getTableName());
            TableName indexTable = TableName.valueOf(MetaDataUtil.getLocalIndexTableName(
                    SchemaUtil.getPhysicalTableName((tableName + "2").getBytes(), isNamespaceMapped).getNameAsString()));
            HTableDescriptor indexTableDesc = admin.getTableDescriptor(indexTable);
            indexTableDesc.removeCoprocessor(IndexHalfStoreFileReaderGenerator.class.getName());
            indexTableDesc.addCoprocessor(MockedIndexHalfStoreFileReaderGenerator.class.getName(), null,
                1, null);
            admin.disableTable(indexTable);
            admin.modifyTable(indexTable, indexTableDesc);
            admin.enableTable(indexTable);

            admin.split(SchemaUtil.getPhysicalTableName(Bytes.toBytes(tableName + "2"), isNamespaceMapped),
                    ByteUtil.concat(Bytes.toBytes(strings[3])));
            List<HRegionInfo> regionsOfUserTable =
                    admin.getTableRegions(SchemaUtil.getPhysicalTableName(Bytes.toBytes(tableName + "2"), isNamespaceMapped));

            while (regionsOfUserTable.size() != 5) {
                Thread.sleep(100);
                regionsOfUserTable = admin.getTableRegions(SchemaUtil.getPhysicalTableName(Bytes.toBytes(tableName + "2"), isNamespaceMapped));
            }
            assertEquals(5, regionsOfUserTable.size());

            List<HRegionInfo> regionsOfIndexTable = admin.getTableRegions(indexTable);

            while (regionsOfIndexTable.size() != 5) {
                Thread.sleep(100);
                regionsOfIndexTable = admin.getTableRegions(indexTable);
            }

            assertEquals(5, regionsOfIndexTable.size());
            boolean success = latch1.await(WAIT_TIME_SECONDS, TimeUnit.SECONDS);
            assertTrue("Timed out waiting for MockedLocalIndexSplitter.preSplitAfterPONR to complete", success);
            latch2.countDown();
       } finally {
            conn1.close();
            latch1.countDown();
            latch2.countDown();
        }
    }

    public static class MockedIndexHalfStoreFileReaderGenerator extends IndexHalfStoreFileReaderGenerator {
        @Override
        public void postCompact(ObserverContext<RegionCoprocessorEnvironment> e, Store store,
                StoreFile resultFile) throws IOException {
            try {
                boolean success = latch2.await(WAIT_TIME_SECONDS, TimeUnit.SECONDS);
                assertTrue("Timed out waiting for test to complete", success);
                super.postCompact(e, store, resultFile);
            } catch (InterruptedException e1) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e1);
            }
        }
    }

    public static class MockedLocalIndexSplitter extends LocalIndexSplitter {
        @Override
        public void preSplitAfterPONR(ObserverContext<RegionCoprocessorEnvironment> ctx)
                throws IOException {
            super.preSplitAfterPONR(ctx);
            latch1.countDown();
        }
    }
}
