// Copyright 2013 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.analysis;

import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import com.cloudera.impala.authorization.AuthorizationConfig;
import com.cloudera.impala.catalog.AuthorizationException;
import com.cloudera.impala.catalog.Catalog;
import com.cloudera.impala.catalog.ImpaladCatalog;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.service.Frontend;
import com.cloudera.impala.testutil.ImpaladTestCatalog;
import com.cloudera.impala.testutil.TestUtils;
import com.cloudera.impala.thrift.TAccessEvent;
import com.cloudera.impala.thrift.TCatalogObjectType;
import com.google.common.collect.Sets;

/**
 * Tests that auditing access events are properly captured during analysis for all
 * statement types.
 */
public class AuditingTest extends AnalyzerTest {
  @Test
  public void TestSelect() throws AuthorizationException, AnalysisException {
    // Simple select from a table.
    Set<TAccessEvent> accessEvents =
        AnalyzeAccessEvents("select * from functional.alltypesagg");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("functional.alltypesagg", TCatalogObjectType.TABLE, "SELECT")));

    // Select from a view. Expect to get 3 events back - one for the view and two
    // for the underlying objects that the view accesses.
    accessEvents = AnalyzeAccessEvents("select * from functional.view_view");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("functional.view_view", TCatalogObjectType.VIEW, "SELECT"),
        new TAccessEvent("functional.alltypes_view", TCatalogObjectType.VIEW, "SELECT"),
        new TAccessEvent("functional.alltypes", TCatalogObjectType.TABLE, "SELECT")
        ));

    // Select from an inline-view.
    accessEvents = AnalyzeAccessEvents(
        "select a.* from (select * from functional.alltypesagg) a");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("functional.alltypesagg", TCatalogObjectType.TABLE, "SELECT")));

    // Select from collection table references.
    accessEvents = AnalyzeAccessEvents(
        "select item from functional.allcomplextypes.int_array_col");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("functional.allcomplextypes",
            TCatalogObjectType.TABLE, "SELECT")));
    accessEvents = AnalyzeAccessEvents(
        "select item from functional.allcomplextypes a, a.int_array_col");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("functional.allcomplextypes",
            TCatalogObjectType.TABLE, "SELECT")));
  }

  @Test
  public void TestUnion() throws AuthorizationException, AnalysisException {
    Set<TAccessEvent> accessEvents = AnalyzeAccessEvents(
        "select * from functional.alltypes union all " +
        "select * from functional_rc.alltypes");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("functional.alltypes", TCatalogObjectType.TABLE, "SELECT"),
        new TAccessEvent("functional_rc.alltypes", TCatalogObjectType.TABLE, "SELECT")));
  }

  @Test
  public void TestInsert() throws AuthorizationException, AnalysisException {
    Set<TAccessEvent> accessEvents = AnalyzeAccessEvents(
        "insert into functional.alltypes " +
        "partition(month,year) select * from functional_rc.alltypes");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("functional_rc.alltypes", TCatalogObjectType.TABLE, "SELECT"),
        new TAccessEvent("functional.alltypes", TCatalogObjectType.TABLE, "INSERT")));

    // Insert + inline-view.
    accessEvents =  AnalyzeAccessEvents(
        "insert into functional.alltypes partition(month,year) " +
        "select b.* from functional.alltypesagg a join (select * from " +
        "functional_rc.alltypes) b on (a.int_col = b.int_col)");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("functional.alltypesagg", TCatalogObjectType.TABLE, "SELECT"),
        new TAccessEvent("functional_rc.alltypes", TCatalogObjectType.TABLE, "SELECT"),
        new TAccessEvent("functional.alltypes", TCatalogObjectType.TABLE, "INSERT")));
  }

  @Test
  public void TestWithClause() throws AuthorizationException, AnalysisException {
    // With clause. No audit event should be recorded for the with-clause view.
    Set<TAccessEvent> accessEvents = AnalyzeAccessEvents(
        "with t as (select * from functional.alltypesagg) select * from t");
    Assert.assertEquals(accessEvents, Sets.newHashSet(new TAccessEvent(
        "functional.alltypesagg", TCatalogObjectType.TABLE, "SELECT")));

    // With clause view referencing a catalog view.
    accessEvents = AnalyzeAccessEvents(
        "with t as (select * from functional.alltypes_view) select * from t");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent(
            "functional.alltypes_view", TCatalogObjectType.VIEW, "SELECT"),
        new TAccessEvent("functional.alltypes", TCatalogObjectType.TABLE, "SELECT")));

    accessEvents =
        AnalyzeAccessEvents("with t as (select 1 + 2) select * from t");
    Assert.assertEquals(0, accessEvents.size());

    // Even though the with-clause view isn't selected an access event is generated.
    accessEvents = AnalyzeAccessEvents(
        "with t as (select * from functional.alltypes) select 'abc'");
    Assert.assertEquals(accessEvents, Sets.newHashSet(new TAccessEvent(
        "functional.alltypes", TCatalogObjectType.TABLE, "SELECT")));
    accessEvents = AnalyzeAccessEvents("with t as (select * from functional.alltypes) " +
        "select * from functional_seq.alltypes");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("functional.alltypes", TCatalogObjectType.TABLE, "SELECT"),
        new TAccessEvent(
            "functional_seq.alltypes", TCatalogObjectType.TABLE, "SELECT")));
  }

  @Test
  public void TestExplainEvents() throws AuthorizationException, AnalysisException {
    Set<TAccessEvent> accessEvents =
        AnalyzeAccessEvents("explain select * from functional.alltypesagg");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("functional.alltypesagg", TCatalogObjectType.TABLE, "SELECT")));
  }

  @Test
  public void TestUseDb() throws AnalysisException, AuthorizationException {
    Set<TAccessEvent> accessEvents = AnalyzeAccessEvents("use functional");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("functional", TCatalogObjectType.DATABASE, "ANY")));
  }

  @Test
  public void TestResetMetadataEvents() throws AnalysisException,
      AuthorizationException {
    Set<TAccessEvent> accessEvents =
        AnalyzeAccessEvents("invalidate metadata functional.alltypesagg");
    // The user didn't actually access the table, no reason to set an access event.
    Assert.assertEquals(0, accessEvents.size());
    accessEvents =  AnalyzeAccessEvents("refresh functional.alltypesagg");
    Assert.assertEquals(0, accessEvents.size());
  }

  @Test
  public void TestCreateTable() throws AnalysisException, AuthorizationException {
    Set<TAccessEvent> accessEvents =
        AnalyzeAccessEvents("create table tpch.new_table (i int)");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("tpch.new_table", TCatalogObjectType.TABLE, "CREATE")));

    accessEvents =
        AnalyzeAccessEvents("create table tpch.new_lineitem like tpch.lineitem");

    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("tpch.lineitem", TCatalogObjectType.TABLE, "VIEW_METADATA"),
        new TAccessEvent("tpch.new_lineitem", TCatalogObjectType.TABLE, "CREATE")));

    accessEvents = AnalyzeAccessEvents("create table tpch.new_table like parquet "
        + "'/test-warehouse/schemas/zipcode_incomes.parquet'");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("tpch.new_table", TCatalogObjectType.TABLE, "CREATE")));
  }

  @Test
  public void TestCreateView() throws AuthorizationException, AnalysisException {
    Set<TAccessEvent> accessEvents = AnalyzeAccessEvents(
        "create view tpch.new_view as select * from functional.alltypesagg");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("functional.alltypesagg", TCatalogObjectType.TABLE, "SELECT"),
        new TAccessEvent("tpch.new_view", TCatalogObjectType.VIEW, "CREATE")));
  }

  @Test
  public void TestCreateDatabase() throws AnalysisException, AuthorizationException {
    Set<TAccessEvent> accessEvents = AnalyzeAccessEvents("create database newdb");
    Assert.assertEquals(accessEvents, Sets.newHashSet(new TAccessEvent(
        "newdb", TCatalogObjectType.DATABASE, "CREATE")));
  }

  @Test
  public void TestDropDatabase() throws AnalysisException, AuthorizationException {
    Set<TAccessEvent> accessEvents = AnalyzeAccessEvents("drop database tpch");
    Assert.assertEquals(accessEvents, Sets.newHashSet(new TAccessEvent(
        "tpch", TCatalogObjectType.DATABASE, "DROP")));
  }

  @Test
  public void TestDropTable() throws AnalysisException, AuthorizationException {
    Set<TAccessEvent> accessEvents = AnalyzeAccessEvents("drop table tpch.lineitem");
    Assert.assertEquals(accessEvents, Sets.newHashSet(new TAccessEvent(
        "tpch.lineitem", TCatalogObjectType.TABLE, "DROP")));
  }

  @Test
  public void TestDropView() throws AnalysisException, AuthorizationException {
    Set<TAccessEvent> accessEvents =
        AnalyzeAccessEvents("drop view functional_seq_snap.alltypes_view");
    Assert.assertEquals(accessEvents, Sets.newHashSet(new TAccessEvent(
        "functional_seq_snap.alltypes_view", TCatalogObjectType.VIEW, "DROP")));
  }

  @Test
  public void AlterTable() throws AnalysisException, AuthorizationException {
    // User has permissions to modify tables.
    Set<TAccessEvent> accessEvents = AnalyzeAccessEvents(
        "ALTER TABLE functional_seq_snap.alltypes ADD COLUMNS (c1 int)");
    Assert.assertEquals(accessEvents, Sets.newHashSet(new TAccessEvent(
        "functional_seq_snap.alltypes", TCatalogObjectType.TABLE, "ALTER")));

    accessEvents =  AnalyzeAccessEvents(
        "ALTER TABLE functional_seq_snap.alltypes RENAME TO functional_seq_snap.t1");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent(
            "functional_seq_snap.alltypes", TCatalogObjectType.TABLE, "ALTER"),
        new TAccessEvent("functional_seq_snap.t1", TCatalogObjectType.TABLE, "CREATE")));
  }

  @Test
  public void TestAlterView() throws AnalysisException, AuthorizationException {
    Set<TAccessEvent> accessEvents = AnalyzeAccessEvents(
        "ALTER VIEW functional_seq_snap.alltypes_view " +
        "rename to functional_seq_snap.v1");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent(
            "functional_seq_snap.alltypes_view", TCatalogObjectType.VIEW, "ALTER"),
        new TAccessEvent("functional_seq_snap.v1", TCatalogObjectType.VIEW, "CREATE")));
  }

  @Test
  public void TestComputeStats() throws AnalysisException, AuthorizationException {
    Set<TAccessEvent> accessEvents = AnalyzeAccessEvents(
        "COMPUTE STATS functional_seq_snap.alltypes");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent(
            "functional_seq_snap.alltypes", TCatalogObjectType.TABLE, "ALTER")));
  }

  @Test
  public void TestDropStats() throws AnalysisException, AuthorizationException {
    Set<TAccessEvent> accessEvents = AnalyzeAccessEvents(
        "DROP STATS functional_seq_snap.alltypes");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent(
            "functional_seq_snap.alltypes", TCatalogObjectType.TABLE, "ALTER")));
  }

  @Test
  public void TestDescribe() throws AuthorizationException, AnalysisException {
    Set<TAccessEvent> accessEvents =
        AnalyzeAccessEvents("describe functional.alltypesagg");
    Assert.assertEquals(accessEvents, Sets.newHashSet(new TAccessEvent(
        "functional.alltypesagg", TCatalogObjectType.TABLE, "ANY")));

    accessEvents = AnalyzeAccessEvents("describe formatted functional.alltypesagg");
    Assert.assertEquals(accessEvents, Sets.newHashSet(new TAccessEvent(
        "functional.alltypesagg", TCatalogObjectType.TABLE, "VIEW_METADATA")));

    accessEvents = AnalyzeAccessEvents("describe functional.complex_view");
    Assert.assertEquals(accessEvents, Sets.newHashSet(new TAccessEvent(
        "functional.complex_view", TCatalogObjectType.VIEW, "ANY")));

    accessEvents = AnalyzeAccessEvents(
        "describe functional.allcomplextypes.int_array_col");
    Assert.assertEquals(accessEvents, Sets.newHashSet(new TAccessEvent(
        "functional.allcomplextypes", TCatalogObjectType.TABLE, "ANY")));
  }

  @Test
  public void TestShow() throws AnalysisException, AuthorizationException{
    String[] statsQuals = new String[]{ "partitions", "table stats", "column stats" };
    for (String qual: statsQuals) {
      Set<TAccessEvent> accessEvents =
          AnalyzeAccessEvents(String.format("show %s functional.alltypes", qual));
      Assert.assertEquals(accessEvents, Sets.newHashSet(new TAccessEvent(
          "functional.alltypes", TCatalogObjectType.TABLE, "VIEW_METADATA")));
    }
  }

  @Test
  public void TestShowCreateTable() throws AuthorizationException, AnalysisException {
    Set<TAccessEvent> accessEvents =
        AnalyzeAccessEvents("show create table functional.alltypesagg");
    Assert.assertEquals(accessEvents, Sets.newHashSet(new TAccessEvent(
        "functional.alltypesagg", TCatalogObjectType.TABLE, "VIEW_METADATA")));
  }

  @Test
  public void TestLoad() throws AuthorizationException, AnalysisException {
    Set<TAccessEvent> accessEvents = AnalyzeAccessEvents("load data inpath " +
        "'hdfs://localhost:20500/test-warehouse/tpch.lineitem' " +
        "into table functional.alltypes partition(month=10, year=2009)");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("functional.alltypes", TCatalogObjectType.TABLE, "INSERT")));
  }

  @Test
  public void TestAccessEventsOnAuthFailure() throws AuthorizationException,
      AnalysisException {
    // The policy file doesn't exist so all operations will result in
    // an AuthorizationError
    AuthorizationConfig config = AuthorizationConfig.createHadoopGroupAuthConfig(
        "server1", "/does/not/exist", "");
    ImpaladCatalog catalog = new ImpaladTestCatalog(config);
    Frontend fe = new Frontend(config, catalog);
    AnalysisContext analysisContext =
        new AnalysisContext(catalog, TestUtils.createQueryContext(), config);
    // We should get an audit event even when an authorization failure occurs.
    try {
      analysisContext.analyze("create table foo_does_not_exist(i int)");
      analysisContext.authorize(fe.getAuthzChecker());
      Assert.fail("Expected AuthorizationException");
    } catch (AuthorizationException e) {
      Assert.assertEquals(1, analysisContext.getAnalyzer().getAccessEvents().size());
    }
  }

  @Test
  public void TestDoubleAccess() throws AuthorizationException, AnalysisException {
    // Test that triggering two identical audit events only emits one de-duplicated event
    Set<TAccessEvent> accessEvents = AnalyzeAccessEvents("select count(1), max(1) from" +
        " functional.alltypesagg a join functional.alltypesagg b on a.id = b.id");
    Assert.assertEquals(accessEvents, Sets.newHashSet(
        new TAccessEvent("_impala_builtins", TCatalogObjectType.DATABASE, "VIEW_METADATA"),
        new TAccessEvent("functional.alltypesagg", TCatalogObjectType.TABLE, "SELECT")));
  }

  /**
   * Analyzes the given statement and returns the set of TAccessEvents
   * that were captured as part of analysis.
   */
  private Set<TAccessEvent> AnalyzeAccessEvents(String stmt)
      throws AuthorizationException, AnalysisException {
    Analyzer analyzer = createAnalyzer(Catalog.DEFAULT_DB);
    AnalyzesOk(stmt, analyzer);
    return analyzer.getAccessEvents();
  }
}
