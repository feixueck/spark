/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import org.apache.hadoop.fs.{BlockLocation, FileStatus, RawLocalFileSystem}
import org.apache.hadoop.mapreduce.Job

import org.apache.spark.SparkConf
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Expression, ExpressionSet, PredicateHelper}
import org.apache.spark.sql.catalyst.util
import org.apache.spark.sql.execution.DataSourceScanExec
import org.apache.spark.sql.functions._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources._
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.sql.types.{IntegerType, StructType}
import org.apache.spark.util.Utils

class FileSourceStrategySuite extends QueryTest with SharedSQLContext with PredicateHelper {
  import testImplicits._

  protected override val sparkConf = new SparkConf().set("spark.default.parallelism", "1")

  test("unpartitioned table, single partition") {
    val table =
      createTable(
        files = Seq(
          "file1" -> 1,
          "file2" -> 1,
          "file3" -> 1,
          "file4" -> 1,
          "file5" -> 1,
          "file6" -> 1,
          "file7" -> 1,
          "file8" -> 1,
          "file9" -> 1,
          "file10" -> 1))

    checkScan(table.select('c1)) { partitions =>
      // 10 one byte files should fit in a single partition with 10 files.
      assert(partitions.size == 1, "when checking partitions")
      assert(partitions.head.files.size == 10, "when checking partition 1")
      // 1 byte files are too small to split so we should read the whole thing.
      assert(partitions.head.files.head.start == 0)
      assert(partitions.head.files.head.length == 1)
    }

    checkPartitionSchema(StructType(Nil))
    checkDataSchema(StructType(Nil).add("c1", IntegerType))
  }

  test("unpartitioned table, multiple partitions") {
    val table =
      createTable(
        files = Seq(
          "file1" -> 5,
          "file2" -> 5,
          "file3" -> 5))

    withSQLConf(SQLConf.FILES_MAX_PARTITION_BYTES.key -> "11",
      SQLConf.FILES_OPEN_COST_IN_BYTES.key -> "1") {
      checkScan(table.select('c1)) { partitions =>
        // 5 byte files should be laid out [(5, 5), (5)]
        assert(partitions.size == 2, "when checking partitions")
        assert(partitions(0).files.size == 2, "when checking partition 1")
        assert(partitions(1).files.size == 1, "when checking partition 2")

        // 5 byte files are too small to split so we should read the whole thing.
        assert(partitions.head.files.head.start == 0)
        assert(partitions.head.files.head.length == 5)
      }

      checkPartitionSchema(StructType(Nil))
      checkDataSchema(StructType(Nil).add("c1", IntegerType))
    }
  }

  test("Unpartitioned table, large file that gets split") {
    val table =
      createTable(
        files = Seq(
          "file1" -> 15,
          "file2" -> 3))

    withSQLConf(SQLConf.FILES_MAX_PARTITION_BYTES.key -> "10",
      SQLConf.FILES_OPEN_COST_IN_BYTES.key -> "1") {
      checkScan(table.select('c1)) { partitions =>
        // Files should be laid out [(0-10), (10-15, 4)]
        assert(partitions.size == 2, "when checking partitions")
        assert(partitions(0).files.size == 1, "when checking partition 1")
        assert(partitions(1).files.size == 2, "when checking partition 2")

        // Start by reading 10 bytes of the first file
        assert(partitions.head.files.head.start == 0)
        assert(partitions.head.files.head.length == 10)

        // Second partition reads the remaining 5
        assert(partitions(1).files.head.start == 10)
        assert(partitions(1).files.head.length == 5)
      }

      checkPartitionSchema(StructType(Nil))
      checkDataSchema(StructType(Nil).add("c1", IntegerType))
    }
  }

  test("Unpartitioned table, many files that get split") {
    val table =
      createTable(
        files = Seq(
          "file1" -> 2,
          "file2" -> 2,
          "file3" -> 1,
          "file4" -> 1,
          "file5" -> 1,
          "file6" -> 1))

    withSQLConf(SQLConf.FILES_MAX_PARTITION_BYTES.key -> "4",
        SQLConf.FILES_OPEN_COST_IN_BYTES.key -> "1") {
      checkScan(table.select('c1)) { partitions =>
        // Files should be laid out [(file1), (file2, file3), (file4, file5), (file6)]
        assert(partitions.size == 4, "when checking partitions")
        assert(partitions(0).files.size == 1, "when checking partition 1")
        assert(partitions(1).files.size == 2, "when checking partition 2")
        assert(partitions(2).files.size == 2, "when checking partition 3")
        assert(partitions(3).files.size == 1, "when checking partition 4")

        // First partition reads (file1)
        assert(partitions(0).files(0).start == 0)
        assert(partitions(0).files(0).length == 2)

        // Second partition reads (file2, file3)
        assert(partitions(1).files(0).start == 0)
        assert(partitions(1).files(0).length == 2)
        assert(partitions(1).files(1).start == 0)
        assert(partitions(1).files(1).length == 1)

        // Third partition reads (file4, file5)
        assert(partitions(2).files(0).start == 0)
        assert(partitions(2).files(0).length == 1)
        assert(partitions(2).files(1).start == 0)
        assert(partitions(2).files(1).length == 1)

        // Final partition reads (file6)
        assert(partitions(3).files(0).start == 0)
        assert(partitions(3).files(0).length == 1)
      }

      checkPartitionSchema(StructType(Nil))
      checkDataSchema(StructType(Nil).add("c1", IntegerType))
    }
  }

  test("partitioned table") {
    val table =
      createTable(
        files = Seq(
          "p1=1/file1" -> 10,
          "p1=2/file2" -> 10))

    // Only one file should be read.
    checkScan(table.where("p1 = 1")) { partitions =>
      assert(partitions.size == 1, "when checking partitions")
      assert(partitions.head.files.size == 1, "when files in partition 1")
    }
    // We don't need to reevaluate filters that are only on partitions.
    checkDataFilters(Set.empty)

    // Only one file should be read.
    checkScan(table.where("p1 = 1 AND c1 = 1 AND (p1 + c1) = 1")) { partitions =>
      assert(partitions.size == 1, "when checking partitions")
      assert(partitions.head.files.size == 1, "when checking files in partition 1")
      assert(partitions.head.files.head.partitionValues.getInt(0) == 1,
        "when checking partition values")
    }
    // Only the filters that do not contain the partition column should be pushed down
    checkDataFilters(Set(IsNotNull("c1"), EqualTo("c1", 1)))
  }

  test("partitioned table - case insensitive") {
    withSQLConf("spark.sql.caseSensitive" -> "false") {
      val table =
        createTable(
          files = Seq(
            "p1=1/file1" -> 10,
            "p1=2/file2" -> 10))

      // Only one file should be read.
      checkScan(table.where("P1 = 1")) { partitions =>
        assert(partitions.size == 1, "when checking partitions")
        assert(partitions.head.files.size == 1, "when files in partition 1")
      }
      // We don't need to reevaluate filters that are only on partitions.
      checkDataFilters(Set.empty)

      // Only one file should be read.
      checkScan(table.where("P1 = 1 AND C1 = 1 AND (P1 + C1) = 1")) { partitions =>
        assert(partitions.size == 1, "when checking partitions")
        assert(partitions.head.files.size == 1, "when checking files in partition 1")
        assert(partitions.head.files.head.partitionValues.getInt(0) == 1,
          "when checking partition values")
      }
      // Only the filters that do not contain the partition column should be pushed down
      checkDataFilters(Set(IsNotNull("c1"), EqualTo("c1", 1)))
    }
  }

  test("partitioned table - after scan filters") {
    val table =
      createTable(
        files = Seq(
          "p1=1/file1" -> 10,
          "p1=2/file2" -> 10))

    val df = table.where("p1 = 1 AND (p1 + c1) = 2 AND c1 = 1")
    // Filter on data only are advisory so we have to reevaluate.
    assert(getPhysicalFilters(df) contains resolve(df, "c1 = 1"))
    // Need to evalaute filters that are not pushed down.
    assert(getPhysicalFilters(df) contains resolve(df, "(p1 + c1) = 2"))
    // Don't reevaluate partition only filters.
    assert(!(getPhysicalFilters(df) contains resolve(df, "p1 = 1")))
  }

  test("bucketed table") {
    val table =
      createTable(
        files = Seq(
          "p1=1/file1_0000" -> 1,
          "p1=1/file2_0000" -> 1,
          "p1=1/file3_0002" -> 1,
          "p1=2/file4_0002" -> 1,
          "p1=2/file5_0000" -> 1,
          "p1=2/file6_0000" -> 1,
          "p1=2/file7_0000" -> 1),
        buckets = 3)

    // No partition pruning
    checkScan(table) { partitions =>
      assert(partitions.size == 3)
      assert(partitions(0).files.size == 5)
      assert(partitions(1).files.size == 0)
      assert(partitions(2).files.size == 2)
    }

    // With partition pruning
    checkScan(table.where("p1=2")) { partitions =>
      assert(partitions.size == 3)
      assert(partitions(0).files.size == 3)
      assert(partitions(1).files.size == 0)
      assert(partitions(2).files.size == 1)
    }
  }

  test("Locality support for FileScanRDD") {
    val partition = FilePartition(0, Seq(
      PartitionedFile(InternalRow.empty, "fakePath0", 0, 10, Array("host0", "host1")),
      PartitionedFile(InternalRow.empty, "fakePath0", 10, 20, Array("host1", "host2")),
      PartitionedFile(InternalRow.empty, "fakePath1", 0, 5, Array("host3")),
      PartitionedFile(InternalRow.empty, "fakePath2", 0, 5, Array("host4"))
    ))

    val fakeRDD = new FileScanRDD(
      sqlContext,
      (file: PartitionedFile) => Iterator.empty,
      Seq(partition)
    )

    assertResult(Set("host0", "host1", "host2")) {
      fakeRDD.preferredLocations(partition).toSet
    }
  }

  test("Locality support for FileScanRDD - one file per partition") {
    withHadoopConf(
      "fs.file.impl" -> classOf[LocalityTestFileSystem].getName,
      "fs.file.impl.disable.cache" -> "true"
    ) {
      withSQLConf(SQLConf.FILES_MAX_PARTITION_BYTES.key -> "10") {
        val table =
          createTable(files = Seq(
            "file1" -> 10,
            "file2" -> 10
          ))

        checkScan(table) { partitions =>
          val Seq(p1, p2) = partitions
          assert(p1.files.length == 1)
          assert(p1.files.flatMap(_.locations).length == 1)
          assert(p2.files.length == 1)
          assert(p2.files.flatMap(_.locations).length == 1)

          val fileScanRDD = getFileScanRDD(table)
          assert(partitions.flatMap(fileScanRDD.preferredLocations).length == 2)
        }
      }
    }
  }

  test("Locality support for FileScanRDD - large file") {
    withHadoopConf(
      "fs.file.impl" -> classOf[LocalityTestFileSystem].getName,
      "fs.file.impl.disable.cache" -> "true"
    ) {
      withSQLConf(
        SQLConf.FILES_MAX_PARTITION_BYTES.key -> "10",
        SQLConf.FILES_OPEN_COST_IN_BYTES.key -> "0"
      ) {
        val table =
          createTable(files = Seq(
            "file1" -> 15,
            "file2" -> 5
          ))

        checkScan(table) { partitions =>
          val Seq(p1, p2) = partitions
          assert(p1.files.length == 1)
          assert(p1.files.flatMap(_.locations).length == 1)
          assert(p2.files.length == 2)
          assert(p2.files.flatMap(_.locations).length == 2)

          val fileScanRDD = getFileScanRDD(table)
          assert(partitions.flatMap(fileScanRDD.preferredLocations).length == 3)
        }
      }
    }
  }

  // Helpers for checking the arguments passed to the FileFormat.

  protected val checkPartitionSchema =
    checkArgument("partition schema", _.partitionSchema, _: StructType)
  protected val checkDataSchema =
    checkArgument("data schema", _.dataSchema, _: StructType)
  protected val checkDataFilters =
    checkArgument("data filters", _.filters.toSet, _: Set[Filter])

  /** Helper for building checks on the arguments passed to the reader. */
  protected def checkArgument[T](name: String, arg: LastArguments.type => T, expected: T): Unit = {
    if (arg(LastArguments) != expected) {
      fail(
        s"""
           |Wrong $name
           |expected: $expected
           |actual: ${arg(LastArguments)}
         """.stripMargin)
    }
  }

  /** Returns a resolved expression for `str` in the context of `df`. */
  def resolve(df: DataFrame, str: String): Expression = {
    df.select(expr(str)).queryExecution.analyzed.expressions.head.children.head
  }

  /** Returns a set with all the filters present in the physical plan. */
  def getPhysicalFilters(df: DataFrame): ExpressionSet = {
    ExpressionSet(
      df.queryExecution.executedPlan.collect {
        case execution.FilterExec(f, _) => splitConjunctivePredicates(f)
      }.flatten)
  }

  /** Plans the query and calls the provided validation function with the planned partitioning. */
  def checkScan(df: DataFrame)(func: Seq[FilePartition] => Unit): Unit = {
    func(getFileScanRDD(df).filePartitions)
  }

  /**
   * Constructs a new table given a list of file names and sizes expressed in bytes. The table
   * is written out in a temporary directory and any nested directories in the files names
   * are automatically created.
   *
   * When `buckets` is > 0 the returned [[DataFrame]] will have metadata specifying that number of
   * buckets.  However, it is the responsibility of the caller to assign files to each bucket
   * by appending the bucket id to the file names.
   */
  def createTable(
      files: Seq[(String, Int)],
      buckets: Int = 0): DataFrame = {
    val tempDir = Utils.createTempDir()
    files.foreach {
      case (name, size) =>
        val file = new File(tempDir, name)
        assert(file.getParentFile.exists() || file.getParentFile.mkdirs())
        util.stringToFile(file, "*" * size)
    }

    val df = sqlContext.read
      .format(classOf[TestFileFormat].getName)
      .load(tempDir.getCanonicalPath)

    if (buckets > 0) {
      val bucketed = df.queryExecution.analyzed transform {
        case l @ LogicalRelation(r: HadoopFsRelation, _, _) =>
          l.copy(relation =
            r.copy(bucketSpec = Some(BucketSpec(numBuckets = buckets, "c1" :: Nil, Nil))))
      }
      Dataset.ofRows(sqlContext, bucketed)
    } else {
      df
    }
  }

  def getFileScanRDD(df: DataFrame): FileScanRDD = {
    df.queryExecution.executedPlan.collect {
      case scan: DataSourceScanExec if scan.rdd.isInstanceOf[FileScanRDD] =>
        scan.rdd.asInstanceOf[FileScanRDD]
    }.headOption.getOrElse {
      fail(s"No FileScan in query\n${df.queryExecution}")
    }
  }
}

/** Holds the last arguments passed to [[TestFileFormat]]. */
object LastArguments {
  var partitionSchema: StructType = _
  var dataSchema: StructType = _
  var filters: Seq[Filter] = _
  var options: Map[String, String] = _
}

/** A test [[FileFormat]] that records the arguments passed to buildReader, and returns nothing. */
class TestFileFormat extends FileFormat {

  override def toString: String = "TestFileFormat"

  /**
   * When possible, this method should return the schema of the given `files`.  When the format
   * does not support inference, or no valid files are given should return None.  In these cases
   * Spark will require that user specify the schema manually.
   */
  override def inferSchema(
      sqlContext: SQLContext,
      options: Map[String, String],
      files: Seq[FileStatus]): Option[StructType] =
    Some(
      StructType(Nil)
          .add("c1", IntegerType)
          .add("c2", IntegerType))

  /**
   * Prepares a write job and returns an [[OutputWriterFactory]].  Client side job preparation can
   * be put here.  For example, user defined output committer can be configured here
   * by setting the output committer class in the conf of spark.sql.sources.outputCommitterClass.
   */
  override def prepareWrite(
      sqlContext: SQLContext,
      job: Job,
      options: Map[String, String],
      dataSchema: StructType): OutputWriterFactory = {
    throw new NotImplementedError("JUST FOR TESTING")
  }

  override def buildReader(
      sqlContext: SQLContext,
      dataSchema: StructType,
      partitionSchema: StructType,
      requiredSchema: StructType,
      filters: Seq[Filter],
      options: Map[String, String]): PartitionedFile => Iterator[InternalRow] = {

    // Record the arguments so they can be checked in the test case.
    LastArguments.partitionSchema = partitionSchema
    LastArguments.dataSchema = requiredSchema
    LastArguments.filters = filters
    LastArguments.options = options

    (file: PartitionedFile) => { Iterator.empty }
  }
}


class LocalityTestFileSystem extends RawLocalFileSystem {
  private val invocations = new AtomicInteger(0)

  override def getFileBlockLocations(
      file: FileStatus, start: Long, len: Long): Array[BlockLocation] = {
    val count = invocations.getAndAdd(1)
    Array(new BlockLocation(Array(s"host$count:50010"), Array(s"host$count"), 0, len))
  }
}
