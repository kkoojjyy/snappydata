/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package org.apache.spark.sql.execution

import scala.util.control.NonFatal

import com.pivotal.gemfirexd.Attribute
import com.pivotal.gemfirexd.internal.engine.Misc
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore
import com.pivotal.gemfirexd.internal.iapi.reference.Property
import com.pivotal.gemfirexd.internal.impl.jdbc.Util
import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState
import io.snappydata.Constant

import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.expressions.{Alias, SortDirection}
import org.apache.spark.sql.catalyst.plans.QueryPlan
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, Project}
import org.apache.spark.sql.catalyst.{SQLBuilder, TableIdentifier}
import org.apache.spark.sql.collection.Utils
import org.apache.spark.sql.execution.columnar.InMemoryTableScanExec
import org.apache.spark.sql.execution.command.{CreateViewCommand, PersistedView, RunnableCommand, ViewType}
import org.apache.spark.sql.hive.{QualifiedTableName, SnappyStoreHiveCatalog}
import org.apache.spark.sql.internal.BypassRowLevelSecurity
import org.apache.spark.sql.sources.JdbcExtendedUtils
import org.apache.spark.sql.types.{MetadataBuilder, StructField, StructType}
import org.apache.spark.streaming.{Duration, SnappyStreamingContext}


private[sql] case class CreateMetastoreTableUsing(
    tableIdent: TableIdentifier,
    baseTable: Option[TableIdentifier],
    userSpecifiedSchema: Option[StructType],
    schemaDDL: Option[String],
    provider: String,
    allowExisting: Boolean,
    options: Map[String, String],
    isBuiltIn: Boolean) extends RunnableCommand {

  override def run(session: SparkSession): Seq[Row] = {
    val snc = session.asInstanceOf[SnappySession]
    val mode = if (allowExisting) SaveMode.Ignore else SaveMode.ErrorIfExists
    snc.createTable(snc.sessionState.catalog
        .newQualifiedTableName(tableIdent), provider, userSpecifiedSchema,
      schemaDDL, mode, snc.addBaseTableOption(baseTable, options), isBuiltIn)
    Nil
  }
}

private[sql] case class CreateMetastoreTableUsingSelect(
    tableIdent: TableIdentifier,
    baseTable: Option[TableIdentifier],
    userSpecifiedSchema: Option[StructType],
    schemaDDL: Option[String],
    provider: String,
    partitionColumns: Array[String],
    mode: SaveMode,
    options: Map[String, String],
    query: LogicalPlan,
    isBuiltIn: Boolean) extends RunnableCommand {

  override def run(session: SparkSession): Seq[Row] = {
    val snc = session.asInstanceOf[SnappySession]
    val catalog = snc.sessionState.catalog
    snc.createTable(catalog.newQualifiedTableName(tableIdent), provider,
      userSpecifiedSchema, schemaDDL, partitionColumns, mode,
      snc.addBaseTableOption(baseTable, options), query, isBuiltIn)
    Nil
  }
}

private[sql] case class DropTableOrViewCommand(isView: Boolean, ifExists: Boolean,
    tableIdent: TableIdentifier) extends RunnableCommand {

  override def run(session: SparkSession): Seq[Row] = {
    val snc = session.asInstanceOf[SnappySession]
    val catalog = snc.sessionState.catalog
    // check for table/view
    val qualifiedName = catalog.newQualifiedTableName(tableIdent)
    if (isView) {
      if (!catalog.isView(qualifiedName) && !catalog.isLocalTemporaryView(qualifiedName)) {
        throw new AnalysisException(
          s"Cannot drop a table with DROP VIEW. Please use DROP TABLE instead")
      }
    } else if (catalog.isView(qualifiedName)) {
      throw new AnalysisException(
        "Cannot drop a view with DROP TABLE. Please use DROP VIEW instead")
    }
    snc.dropTable(qualifiedName, ifExists, resolveRelation = true)
    Nil
  }
}

private[sql] case class DropPolicyCommand(ifExists: Boolean,
    policyIdentifer: TableIdentifier) extends RunnableCommand {

  override def run(session: SparkSession): Seq[Row] = {
    val snc = session.asInstanceOf[SnappySession]
    val catalog = snc.sessionState.catalog
    // check for table/view
    val qualifiedName = catalog.newQualifiedTableName(policyIdentifer)

    snc.dropPolicy(qualifiedName, ifExists)
    Nil
  }
}

private[sql] case class TruncateManagedTableCommand(ifExists: Boolean,
    tableIdent: TableIdentifier) extends RunnableCommand {

  override def run(session: SparkSession): Seq[Row] = {
    val snc = session.asInstanceOf[SnappySession]
    val catalog = snc.sessionState.catalog
    snc.truncateTable(catalog.newQualifiedTableName(tableIdent),
      ifExists, ignoreIfUnsupported = false)
    Nil
  }
}

private[sql] case class AlterTableAddColumnCommand(tableIdent: TableIdentifier,
    addColumn: StructField) extends RunnableCommand {

  override def run(session: SparkSession): Seq[Row] = {
    val snc = session.asInstanceOf[SnappySession]
    val catalog = snc.sessionState.catalog
    snc.alterTable(catalog.newQualifiedTableName(tableIdent), isAddColumn = true, addColumn)
    Nil
  }
}

private[sql] case class AlterTableToggleRowLevelSecurityCommand(tableIdent: TableIdentifier,
    enableRls: Boolean) extends RunnableCommand {

  override def run(session: SparkSession): Seq[Row] = {
    val snc = session.asInstanceOf[SnappySession]
    val catalog = snc.sessionState.catalog
    snc.alterTableToggleRLS(catalog.newQualifiedTableName(tableIdent), enableRls)
    Nil
  }
}

private[sql] case class AlterTableDropColumnCommand(
    tableIdent: TableIdentifier, column: String) extends RunnableCommand {

  override def run(session: SparkSession): Seq[Row] = {
    val snc = session.asInstanceOf[SnappySession]
    val catalog = snc.sessionState.catalog
    val plan = try {
      snc.sessionCatalog.lookupRelation(tableIdent)
    } catch {
      case tnfe: TableNotFoundException =>
        throw tnfe
    }
    val structField: StructField =
      plan.schema.find(_.name.equalsIgnoreCase(column)) match {
        case None => throw Utils.analysisException(s"$column column" +
            s" doesn't exist in table ${tableIdent.table}")
        case Some(field) => field
      }
    val table = catalog.newQualifiedTableName(tableIdent)
    snc.alterTable(table, isAddColumn = false, structField)
    Nil
  }
}

private[sql] case class CreateIndexCommand(indexName: TableIdentifier,
    baseTable: TableIdentifier,
    indexColumns: Map[String, Option[SortDirection]],
    options: Map[String, String]) extends RunnableCommand {

  override def run(session: SparkSession): Seq[Row] = {
    val snc = session.asInstanceOf[SnappySession]
    val catalog = snc.sessionState.catalog
    val tableIdent = catalog.newQualifiedTableName(baseTable)
    val indexIdent = catalog.newQualifiedTableName(indexName)
    snc.createIndex(indexIdent, tableIdent, indexColumns, options)
    Nil
  }
}

private[sql] case class CreatePolicyCommand(policyIdent: QualifiedTableName,
    tableIdent: QualifiedTableName,
    policyFor: String, applyTo: Seq[String], expandedPolicyApplyTo: Seq[String],
    currentUser: String, filterStr: String,
    filter: BypassRowLevelSecurity) extends RunnableCommand {

  override def run(session: SparkSession): Seq[Row] = {
    if (!Misc.isSecurityEnabled && !GemFireStore.ALLOW_RLS_WITHOUT_SECURITY) {
      throw Util.generateCsSQLException(SQLState.SECURITY_EXCEPTION_ENCOUNTERED,
        null, new IllegalStateException("CREATE POLICY failed: Security (" +
            Attribute.AUTH_PROVIDER + ") not enabled in the system"))
    }
    if (!Misc.getMemStoreBooting.isRLSEnabled) {
      throw Util.generateCsSQLException(SQLState.SECURITY_EXCEPTION_ENCOUNTERED,
        null, new IllegalStateException("CREATE POLICY failed: Row level security (" +
            Property.SNAPPY_ENABLE_RLS + ") not enabled in the system"))
    }
    val snc = session.asInstanceOf[SnappySession]
    SparkSession.setActiveSession(snc)
    snc.createPolicy(policyIdent, tableIdent, policyFor, applyTo, expandedPolicyApplyTo,
      currentUser, filterStr, filter)
    Nil
  }
}

private[sql] case class DropIndexCommand(
    indexName: TableIdentifier,
    ifExists: Boolean) extends RunnableCommand {

  override def run(session: SparkSession): Seq[Row] = {
    val snc = session.asInstanceOf[SnappySession]
    val catalog = snc.sessionState.catalog
    val indexIdent = catalog.newQualifiedTableName(indexName)
    snc.dropIndex(indexIdent, ifExists)
    Nil
  }
}

private[sql] case class SetSchemaCommand(schemaName: String) extends RunnableCommand {

  override def run(sparkSession: SparkSession): Seq[Row] = {
    sparkSession.asInstanceOf[SnappySession].setSchema(schemaName)
    Nil
  }
}

private[sql] case class SnappyStreamingActionsCommand(action: Int,
    batchInterval: Option[Duration]) extends RunnableCommand {

  override def run(session: SparkSession): Seq[Row] = {

    def creatingFunc(): SnappyStreamingContext = {
      // batchInterval will always be defined when action == 0
      new SnappyStreamingContext(session.sparkContext, batchInterval.get)
    }

    action match {
      case 0 =>
        val ssc = SnappyStreamingContext.getInstance()
        ssc match {
          case Some(_) => // TODO .We should create a named Streaming
          // Context and check if the configurations match
          case None => SnappyStreamingContext.getActiveOrCreate(creatingFunc)
        }
      case 1 =>
        val ssc = SnappyStreamingContext.getInstance()
        ssc match {
          case Some(x) => x.start()
          case None => throw Utils.analysisException(
            "Streaming Context has not been initialized")
        }
      case 2 =>
        val ssc = SnappyStreamingContext.getActive
        ssc match {
          case Some(strCtx) => strCtx.stop(stopSparkContext = false,
            stopGracefully = true)
          case None => // throw Utils.analysisException(
          // "There is no running Streaming Context to be stopped")
        }
    }
    Nil
  }
}

case class CreateSnappyViewCommand(name: TableIdentifier,
    userSpecifiedColumns: Seq[(String, Option[String])],
    comment: Option[String],
    properties: Map[String, String],
    originalText: Option[String],
    child: LogicalPlan,
    allowExisting: Boolean,
    replace: Boolean,
    viewType: ViewType)
    extends RunnableCommand {

  override def run(sparkSession: SparkSession): Seq[Row] = {
    if (viewType != PersistedView) {
      return CreateViewCommand(name, userSpecifiedColumns, comment, properties, originalText,
        child, allowExisting, replace, viewType).run(sparkSession)
    }
    // If the plan cannot be analyzed, throw an exception and don't proceed.
    val qe = sparkSession.sessionState.executePlan(child)
    qe.assertAnalyzed()
    val analyzedPlan = qe.analyzed

    if (userSpecifiedColumns.nonEmpty &&
        userSpecifiedColumns.length != analyzedPlan.output.length) {
      throw new AnalysisException(s"The number of columns produced by the SELECT clause " +
          s"(num: `${analyzedPlan.output.length}`) does not match the number of column names " +
          s"specified by CREATE VIEW (num: `${userSpecifiedColumns.length}`).")
    }

    val aliasedPlan = if (userSpecifiedColumns.isEmpty) {
      analyzedPlan
    } else {
      val projectList = analyzedPlan.output.zip(userSpecifiedColumns).map {
        case (attr, (colName, None)) => Alias(attr, colName)()
        case (attr, (colName, Some(colComment))) =>
          val meta = new MetadataBuilder().putString("comment", colComment).build()
          Alias(attr, colName)(explicitMetadata = Some(meta))
      }
      sparkSession.sessionState.executePlan(Project(projectList, analyzedPlan)).analyzed
    }

    val actualSchemaJson = aliasedPlan.schema.json

    val viewSQL: String = new SQLBuilder(aliasedPlan).toSQL

    // Validate the view SQL - make sure we can parse it and analyze it.
    // If we cannot analyze the generated query, there is probably a bug in SQL generation.
    try {
      sparkSession.sql(viewSQL).queryExecution.assertAnalyzed()
    } catch {
      case NonFatal(e) =>
        throw new RuntimeException(s"Failed to analyze the canonicalized SQL: $viewSQL", e)
    }
    var opts = JdbcExtendedUtils.addSplitProperty(viewSQL, Constant.SPLIT_VIEW_TEXT_PROPERTY,
      properties)
    opts = JdbcExtendedUtils.addSplitProperty(originalText.getOrElse(viewSQL),
      Constant.SPLIT_VIEW_ORIGINAL_TEXT_PROPERTY, opts)

    opts = JdbcExtendedUtils.addSplitProperty(actualSchemaJson,
      SnappyStoreHiveCatalog.HIVE_SCHEMA_PROP, opts)

    val dummyText = "select 1"
    val dummyPlan = sparkSession.sessionState.sqlParser.parsePlan(dummyText)
    val cmd = CreateViewCommand(name, Nil, comment, opts.toMap, Some(dummyText),
      dummyPlan, allowExisting, replace, viewType)
    cmd.run(sparkSession)
  }
}

/**
 * Alternative to Spark's CacheTableCommand that shows the plan being cached
 * in the GUI rather than count() plan for InMemoryRelation.
 */
case class SnappyCacheTableCommand(tableIdent: TableIdentifier,
    plan: Option[LogicalPlan], isLazy: Boolean) extends RunnableCommand {

  require(plan.isEmpty || tableIdent.database.isEmpty,
    "Schema name is not allowed in CACHE TABLE AS SELECT")

  override protected def innerChildren: Seq[QueryPlan[_]] = plan match {
    case None => Nil
    case Some(p) => p :: Nil
  }

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val session = sparkSession.asInstanceOf[SnappySession]
    plan match {
      case None => session.catalog.cacheTable(tableIdent.quotedString)
      case Some(lp) =>
        val df = Dataset.ofRows(session, lp)
        if (isLazy) {
          df.createTempView(tableIdent.quotedString)
          df.persist()
        } else {
          session.sessionState.enableExecutionCache = true
          // Get the actual QueryExecution used by InMemoryRelation so that
          // "withNewExecutionId" runs on the same and shows proper metrics in GUI.
          val cachedExecution = try {
            df.createTempView(tableIdent.quotedString)
            df.persist()
            session.sessionState.getExecution(df.logicalPlan)
          } finally {
            session.sessionState.enableExecutionCache = false
            session.sessionState.clearExecutionCache()
          }
          val memoryPlan = df.queryExecution.executedPlan.collectFirst {
            case plan: InMemoryTableScanExec => plan.relation
          }.get
          val cached = new Dataset[Row](session, cachedExecution, df.exprEnc)
          CachedDataFrame.withCallback(sparkSession, cached, "cache")(_.withNewExecutionId {
            val start = System.nanoTime()
            // Dummy op to materialize the cache. This does the minimal job of count on
            // the actual cached data (RDD[CachedBatch]) to force materialization of cache
            // while avoiding creation of any new SparkPlan.
            memoryPlan.cachedColumnBuffers.count()
            (Unit, System.nanoTime() - start)
          })
        }
    }
    Nil
  }
}
