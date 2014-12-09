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

package org.apache.spark.sql.parquet

import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import parquet.hadoop.ParquetFileWriter
import parquet.hadoop.util.ContextUtil
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.mapreduce.Job

import org.apache.spark.SparkContext
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.{SqlLexical, SqlParser}
import org.apache.spark.sql.catalyst.analysis.{Star, UnresolvedAttribute}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.types.{BooleanType, IntegerType}
import org.apache.spark.sql.catalyst.util.getTempFilePath
import org.apache.spark.sql.test.TestSQLContext
import org.apache.spark.sql.test.TestSQLContext._
import org.apache.spark.util.Utils

case class TestRDDEntry(key: Int, value: String)

case class NullReflectData(
    intField: java.lang.Integer,
    longField: java.lang.Long,
    floatField: java.lang.Float,
    doubleField: java.lang.Double,
    booleanField: java.lang.Boolean)

case class OptionalReflectData(
    intField: Option[Int],
    longField: Option[Long],
    floatField: Option[Float],
    doubleField: Option[Double],
    booleanField: Option[Boolean])

case class Nested(i: Int, s: String)

case class Data(array: Seq[Int], nested: Nested)

case class AllDataTypes(
    stringField: String,
    intField: Int,
    longField: Long,
    floatField: Float,
    doubleField: Double,
    shortField: Short,
    byteField: Byte,
    booleanField: Boolean,
    binaryField: Array[Byte])

case class AllDataTypesWithNonPrimitiveType(
    stringField: String,
    intField: Int,
    longField: Long,
    floatField: Float,
    doubleField: Double,
    shortField: Short,
    byteField: Byte,
    booleanField: Boolean,
    binaryField: Array[Byte],
    array: Seq[Int],
    arrayContainsNull: Seq[Option[Int]],
    map: Map[Int, Long],
    mapValueContainsNull: Map[Int, Option[Long]],
    data: Data)

class ParquetQuerySuite extends QueryTest with FunSuiteLike with BeforeAndAfterAll {
  TestData // Load test data tables.

  var testRDD: SchemaRDD = null

  // TODO: remove this once SqlParser can parse nested select statements
  var nestedParserSqlContext: NestedParserSQLContext = null

  override def beforeAll() {
    nestedParserSqlContext = new NestedParserSQLContext(TestSQLContext.sparkContext)
    ParquetTestData.writeFile()
    ParquetTestData.writeFilterFile()
    ParquetTestData.writeNestedFile1()
    ParquetTestData.writeNestedFile2()
    ParquetTestData.writeNestedFile3()
    ParquetTestData.writeNestedFile4()
    testRDD = parquetFile(ParquetTestData.testDir.toString)
    testRDD.registerTempTable("testsource")
    parquetFile(ParquetTestData.testFilterDir.toString)
      .registerTempTable("testfiltersource")
  }

  override def afterAll() {
    Utils.deleteRecursively(ParquetTestData.testDir)
    Utils.deleteRecursively(ParquetTestData.testFilterDir)
    Utils.deleteRecursively(ParquetTestData.testNestedDir1)
    Utils.deleteRecursively(ParquetTestData.testNestedDir2)
    Utils.deleteRecursively(ParquetTestData.testNestedDir3)
    Utils.deleteRecursively(ParquetTestData.testNestedDir4)
    // here we should also unregister the table??
  }

  test("Read/Write All Types") {
    val tempDir = getTempFilePath("parquetTest").getCanonicalPath
    val range = (0 to 255)
    TestSQLContext.sparkContext.parallelize(range)
      .map(x => AllDataTypes(s"$x", x, x.toLong, x.toFloat, x.toDouble, x.toShort, x.toByte, x % 2 == 0,
        (0 to x).map(_.toByte).toArray))
      .saveAsParquetFile(tempDir)
    val result = parquetFile(tempDir).collect()
    range.foreach {
      i =>
        assert(result(i).getString(0) == s"$i", s"row $i String field did not match, got ${result(i).getString(0)}")
        assert(result(i).getInt(1) === i)
        assert(result(i).getLong(2) === i.toLong)
        assert(result(i).getFloat(3) === i.toFloat)
        assert(result(i).getDouble(4) === i.toDouble)
        assert(result(i).getShort(5) === i.toShort)
        assert(result(i).getByte(6) === i.toByte)
        assert(result(i).getBoolean(7) === (i % 2 == 0))
        assert(result(i)(8) === (0 to i).map(_.toByte).toArray)
    }
  }

  test("Treat binary as string") {
    val oldIsParquetBinaryAsString = TestSQLContext.isParquetBinaryAsString

    // Create the test file.
    val file = getTempFilePath("parquet")
    val path = file.toString
    val range = (0 to 255)
    val rowRDD = TestSQLContext.sparkContext.parallelize(range)
      .map(i => org.apache.spark.sql.Row(i, s"val_$i".getBytes))
    // We need to ask Parquet to store the String column as a Binary column.
    val schema = StructType(
      StructField("c1", IntegerType, false) ::
      StructField("c2", BinaryType, false) :: Nil)
    val schemaRDD1 = applySchema(rowRDD, schema)
    schemaRDD1.saveAsParquetFile(path)
    val resultWithBinary = parquetFile(path).collect
    range.foreach {
      i =>
        assert(resultWithBinary(i).getInt(0) === i)
        assert(resultWithBinary(i)(1) === s"val_$i".getBytes)
    }

    TestSQLContext.setConf(SQLConf.PARQUET_BINARY_AS_STRING, "true")
    // This ParquetRelation always use Parquet types to derive output.
    val parquetRelation = new ParquetRelation(
      path.toString,
      Some(TestSQLContext.sparkContext.hadoopConfiguration),
      TestSQLContext) {
      override val output =
        ParquetTypesConverter.convertToAttributes(
          ParquetTypesConverter.readMetaData(new Path(path), conf).getFileMetaData.getSchema,
          TestSQLContext.isParquetBinaryAsString)
    }
    val schemaRDD = new SchemaRDD(TestSQLContext, parquetRelation)
    val resultWithString = schemaRDD.collect
    range.foreach {
      i =>
        assert(resultWithString(i).getInt(0) === i)
        assert(resultWithString(i)(1) === s"val_$i")
    }

    schemaRDD.registerTempTable("tmp")
    checkAnswer(
      sql("SELECT c1, c2 FROM tmp WHERE c2 = 'val_5' OR c2 = 'val_7'"),
      (5, "val_5") ::
      (7, "val_7") :: Nil)

    // Set it back.
    TestSQLContext.setConf(SQLConf.PARQUET_BINARY_AS_STRING, oldIsParquetBinaryAsString.toString)
  }

  test("Compression options for writing to a Parquetfile") {
    val defaultParquetCompressionCodec = TestSQLContext.parquetCompressionCodec
    import scala.collection.JavaConversions._

    val file = getTempFilePath("parquet")
    val path = file.toString
    val rdd = TestSQLContext.sparkContext.parallelize((1 to 100))
      .map(i => TestRDDEntry(i, s"val_$i"))

    // test default compression codec
    rdd.saveAsParquetFile(path)
    var actualCodec = ParquetTypesConverter.readMetaData(new Path(path), Some(TestSQLContext.sparkContext.hadoopConfiguration))
      .getBlocks.flatMap(block => block.getColumns).map(column => column.getCodec.name()).distinct
    assert(actualCodec === TestSQLContext.parquetCompressionCodec.toUpperCase :: Nil)

    parquetFile(path).registerTempTable("tmp")
    checkAnswer(
      sql("SELECT key, value FROM tmp WHERE value = 'val_5' OR value = 'val_7'"),
      (5, "val_5") ::
      (7, "val_7") :: Nil)

    Utils.deleteRecursively(file)

    // test uncompressed parquet file with property value "UNCOMPRESSED"
    TestSQLContext.setConf(SQLConf.PARQUET_COMPRESSION, "UNCOMPRESSED")

    rdd.saveAsParquetFile(path)
    actualCodec = ParquetTypesConverter.readMetaData(new Path(path), Some(TestSQLContext.sparkContext.hadoopConfiguration))
      .getBlocks.flatMap(block => block.getColumns).map(column => column.getCodec.name()).distinct
    assert(actualCodec === TestSQLContext.parquetCompressionCodec.toUpperCase :: Nil)

    parquetFile(path).registerTempTable("tmp")
    checkAnswer(
      sql("SELECT key, value FROM tmp WHERE value = 'val_5' OR value = 'val_7'"),
      (5, "val_5") ::
      (7, "val_7") :: Nil)

    Utils.deleteRecursively(file)

    // test uncompressed parquet file with property value "none"
    TestSQLContext.setConf(SQLConf.PARQUET_COMPRESSION, "none")

    rdd.saveAsParquetFile(path)
    actualCodec = ParquetTypesConverter.readMetaData(new Path(path), Some(TestSQLContext.sparkContext.hadoopConfiguration))
      .getBlocks.flatMap(block => block.getColumns).map(column => column.getCodec.name()).distinct
    assert(actualCodec === "UNCOMPRESSED" :: Nil)

    parquetFile(path).registerTempTable("tmp")
    checkAnswer(
      sql("SELECT key, value FROM tmp WHERE value = 'val_5' OR value = 'val_7'"),
      (5, "val_5") ::
      (7, "val_7") :: Nil)

    Utils.deleteRecursively(file)

    // test gzip compression codec
    TestSQLContext.setConf(SQLConf.PARQUET_COMPRESSION, "gzip")

    rdd.saveAsParquetFile(path)
    actualCodec = ParquetTypesConverter.readMetaData(new Path(path), Some(TestSQLContext.sparkContext.hadoopConfiguration))
      .getBlocks.flatMap(block => block.getColumns).map(column => column.getCodec.name()).distinct
    assert(actualCodec === TestSQLContext.parquetCompressionCodec.toUpperCase :: Nil)

    parquetFile(path).registerTempTable("tmp")
    checkAnswer(
      sql("SELECT key, value FROM tmp WHERE value = 'val_5' OR value = 'val_7'"),
      (5, "val_5") ::
      (7, "val_7") :: Nil)

    Utils.deleteRecursively(file)

    // test snappy compression codec
    TestSQLContext.setConf(SQLConf.PARQUET_COMPRESSION, "snappy")

    rdd.saveAsParquetFile(path)
    actualCodec = ParquetTypesConverter.readMetaData(new Path(path), Some(TestSQLContext.sparkContext.hadoopConfiguration))
      .getBlocks.flatMap(block => block.getColumns).map(column => column.getCodec.name()).distinct
    assert(actualCodec === TestSQLContext.parquetCompressionCodec.toUpperCase :: Nil)

    parquetFile(path).registerTempTable("tmp")
    checkAnswer(
      sql("SELECT key, value FROM tmp WHERE value = 'val_5' OR value = 'val_7'"),
      (5, "val_5") ::
      (7, "val_7") :: Nil)

    Utils.deleteRecursively(file)

    // TODO: Lzo requires additional external setup steps so leave it out for now
    // ref.: https://github.com/Parquet/parquet-mr/blob/parquet-1.5.0/parquet-hadoop/src/test/java/parquet/hadoop/example/TestInputOutputFormat.java#L169

    // Set it back.
    TestSQLContext.setConf(SQLConf.PARQUET_COMPRESSION, defaultParquetCompressionCodec)
  }

  test("Read/Write All Types with non-primitive type") {
    val tempDir = getTempFilePath("parquetTest").getCanonicalPath
    val range = (0 to 255)
    TestSQLContext.sparkContext.parallelize(range)
      .map(x => AllDataTypesWithNonPrimitiveType(
        s"$x", x, x.toLong, x.toFloat, x.toDouble, x.toShort, x.toByte, x % 2 == 0,
        (0 to x).map(_.toByte).toArray,
        (0 until x),
        (0 until x).map(Option(_).filter(_ % 3 == 0)),
        (0 until x).map(i => i -> i.toLong).toMap,
        (0 until x).map(i => i -> Option(i.toLong)).toMap + (x -> None),
        Data((0 until x), Nested(x, s"$x"))))
      .saveAsParquetFile(tempDir)
    val result = parquetFile(tempDir).collect()
    range.foreach {
      i =>
        assert(result(i).getString(0) == s"$i", s"row $i String field did not match, got ${result(i).getString(0)}")
        assert(result(i).getInt(1) === i)
        assert(result(i).getLong(2) === i.toLong)
        assert(result(i).getFloat(3) === i.toFloat)
        assert(result(i).getDouble(4) === i.toDouble)
        assert(result(i).getShort(5) === i.toShort)
        assert(result(i).getByte(6) === i.toByte)
        assert(result(i).getBoolean(7) === (i % 2 == 0))
        assert(result(i)(8) === (0 to i).map(_.toByte).toArray)
        assert(result(i)(9) === (0 until i))
        assert(result(i)(10) === (0 until i).map(i => if (i % 3 == 0) i else null))
        assert(result(i)(11) === (0 until i).map(i => i -> i.toLong).toMap)
        assert(result(i)(12) === (0 until i).map(i => i -> i.toLong).toMap + (i -> null))
        assert(result(i)(13) === new GenericRow(Array[Any]((0 until i), new GenericRow(Array[Any](i, s"$i")))))
    }
  }

  test("self-join parquet files") {
    val x = ParquetTestData.testData.as('x)
    val y = ParquetTestData.testData.as('y)
    val query = x.join(y).where("x.myint".attr === "y.myint".attr)

    // Check to make sure that the attributes from either side of the join have unique expression
    // ids.
    query.queryExecution.analyzed.output.filter(_.name == "myint") match {
      case Seq(i1, i2) if(i1.exprId == i2.exprId) =>
        fail(s"Duplicate expression IDs found in query plan: $query")
      case Seq(_, _) => // All good
    }

    val result = query.collect()
    assert(result.size === 9, "self-join result has incorrect size")
    assert(result(0).size === 12, "result row has incorrect size")
    result.zipWithIndex.foreach {
      case (row, index) => row.zipWithIndex.foreach {
        case (field, column) => assert(field != null, s"self-join contains null value in row $index field $column")
      }
    }
  }

  test("Import of simple Parquet file") {
    val result = parquetFile(ParquetTestData.testDir.toString).collect()
    assert(result.size === 15)
    result.zipWithIndex.foreach {
      case (row, index) => {
        val checkBoolean =
          if (index % 3 == 0)
            row(0) == true
          else
            row(0) == false
        assert(checkBoolean === true, s"boolean field value in line $index did not match")
        if (index % 5 == 0) assert(row(1) === 5, s"int field value in line $index did not match")
        assert(row(2) === "abc", s"string field value in line $index did not match")
        assert(row(3) === (index.toLong << 33), s"long value in line $index did not match")
        assert(row(4) === 2.5F, s"float field value in line $index did not match")
        assert(row(5) === 4.5D, s"double field value in line $index did not match")
      }
    }
  }

  test("Projection of simple Parquet file") {
    val result = ParquetTestData.testData.select('myboolean, 'mylong).collect()
    result.zipWithIndex.foreach {
      case (row, index) => {
          if (index % 3 == 0)
            assert(row(0) === true, s"boolean field value in line $index did not match (every third row)")
          else
            assert(row(0) === false, s"boolean field value in line $index did not match")
        assert(row(1) === (index.toLong << 33), s"long field value in line $index did not match")
        assert(row.size === 2, s"number of columns in projection in line $index is incorrect")
      }
    }
  }

  test("Writing metadata from scratch for table CREATE") {
    val job = new Job()
    val path = new Path(getTempFilePath("testtable").getCanonicalFile.toURI.toString)
    val fs: FileSystem = FileSystem.getLocal(ContextUtil.getConfiguration(job))
    ParquetTypesConverter.writeMetaData(
      ParquetTestData.testData.output,
      path,
      TestSQLContext.sparkContext.hadoopConfiguration)
    assert(fs.exists(new Path(path, ParquetFileWriter.PARQUET_METADATA_FILE)))
    val metaData = ParquetTypesConverter.readMetaData(path, Some(ContextUtil.getConfiguration(job)))
    assert(metaData != null)
    ParquetTestData
      .testData
      .parquetSchema
      .checkContains(metaData.getFileMetaData.getSchema) // throws exception if incompatible
    metaData
      .getFileMetaData
      .getSchema
      .checkContains(ParquetTestData.testData.parquetSchema) // throws exception if incompatible
    fs.delete(path, true)
  }

  test("Creating case class RDD table") {
    TestSQLContext.sparkContext.parallelize((1 to 100))
      .map(i => TestRDDEntry(i, s"val_$i"))
      .registerTempTable("tmp")
    val rdd = sql("SELECT * FROM tmp").collect().sortBy(_.getInt(0))
    var counter = 1
    rdd.foreach {
      // '===' does not like string comparison?
      row: Row => {
        assert(row.getString(1).equals(s"val_$counter"), s"row $counter value ${row.getString(1)} does not match val_$counter")
        counter = counter + 1
      }
    }
  }

  test("Saving case class RDD table to file and reading it back in") {
    val file = getTempFilePath("parquet")
    val path = file.toString
    val rdd = TestSQLContext.sparkContext.parallelize((1 to 100))
      .map(i => TestRDDEntry(i, s"val_$i"))
    rdd.saveAsParquetFile(path)
    val readFile = parquetFile(path)
    readFile.registerTempTable("tmpx")
    val rdd_copy = sql("SELECT * FROM tmpx").collect()
    val rdd_orig = rdd.collect()
    for(i <- 0 to 99) {
      assert(rdd_copy(i).apply(0) === rdd_orig(i).key,   s"key error in line $i")
      assert(rdd_copy(i).apply(1) === rdd_orig(i).value, s"value error in line $i")
    }
    Utils.deleteRecursively(file)
  }

  test("Read a parquet file instead of a directory") {
    val file = getTempFilePath("parquet")
    val path = file.toString
    val fsPath = new Path(path)
    val fs: FileSystem = fsPath.getFileSystem(TestSQLContext.sparkContext.hadoopConfiguration)
    val rdd = TestSQLContext.sparkContext.parallelize((1 to 100))
      .map(i => TestRDDEntry(i, s"val_$i"))
    rdd.coalesce(1).saveAsParquetFile(path)

    val children = fs.listStatus(fsPath).filter(_.getPath.getName.endsWith(".parquet"))
    assert(children.length > 0)
    val readFile = parquetFile(path + "/" + children(0).getPath.getName)
    readFile.registerTempTable("tmpx")
    val rdd_copy = sql("SELECT * FROM tmpx").collect()
    val rdd_orig = rdd.collect()
    for(i <- 0 to 99) {
      assert(rdd_copy(i).apply(0) === rdd_orig(i).key,   s"key error in line $i")
      assert(rdd_copy(i).apply(1) === rdd_orig(i).value, s"value error in line $i")
    }
    Utils.deleteRecursively(file)
  }

  test("Insert (overwrite) via Scala API") {
    val dirname = Utils.createTempDir()
    val source_rdd = TestSQLContext.sparkContext.parallelize((1 to 100))
      .map(i => TestRDDEntry(i, s"val_$i"))
    source_rdd.registerTempTable("source")
    val dest_rdd = createParquetFile[TestRDDEntry](dirname.toString)
    dest_rdd.registerTempTable("dest")
    sql("INSERT OVERWRITE INTO dest SELECT * FROM source").collect()
    val rdd_copy1 = sql("SELECT * FROM dest").collect()
    assert(rdd_copy1.size === 100)
    assert(rdd_copy1(0).apply(0) === 1)
    assert(rdd_copy1(0).apply(1) === "val_1")
    // TODO: why does collecting break things? It seems InsertIntoParquet::execute() is
    // executed twice otherwise?!
    sql("INSERT INTO dest SELECT * FROM source")
    val rdd_copy2 = sql("SELECT * FROM dest").collect()
    assert(rdd_copy2.size === 200)
    assert(rdd_copy2(0).apply(0) === 1)
    assert(rdd_copy2(0).apply(1) === "val_1")
    assert(rdd_copy2(99).apply(0) === 100)
    assert(rdd_copy2(99).apply(1) === "val_100")
    assert(rdd_copy2(100).apply(0) === 1)
    assert(rdd_copy2(100).apply(1) === "val_1")
    Utils.deleteRecursively(dirname)
  }

  test("Insert (appending) to same table via Scala API") {
    // TODO: why does collecting break things? It seems InsertIntoParquet::execute() is
    // executed twice otherwise?!
    sql("INSERT INTO testsource SELECT * FROM testsource")
    val double_rdd = sql("SELECT * FROM testsource").collect()
    assert(double_rdd != null)
    assert(double_rdd.size === 30)
    for(i <- (0 to 14)) {
      assert(double_rdd(i) === double_rdd(i+15), s"error: lines $i and ${i+15} to not match")
    }
    // let's restore the original test data
    Utils.deleteRecursively(ParquetTestData.testDir)
    ParquetTestData.writeFile()
  }

  test("save and load case class RDD with nulls as parquet") {
    val data = NullReflectData(null, null, null, null, null)
    val rdd = sparkContext.parallelize(data :: Nil)

    val file = getTempFilePath("parquet")
    val path = file.toString
    rdd.saveAsParquetFile(path)
    val readFile = parquetFile(path)

    val rdd_saved = readFile.collect()
    assert(rdd_saved(0) === Seq.fill(5)(null))
    Utils.deleteRecursively(file)
    assert(true)
  }

  test("save and load case class RDD with Nones as parquet") {
    val data = OptionalReflectData(None, None, None, None, None)
    val rdd = sparkContext.parallelize(data :: Nil)

    val file = getTempFilePath("parquet")
    val path = file.toString
    rdd.saveAsParquetFile(path)
    val readFile = parquetFile(path)

    val rdd_saved = readFile.collect()
    assert(rdd_saved(0) === Seq.fill(5)(null))
    Utils.deleteRecursively(file)
    assert(true)
  }

  test("create RecordFilter for simple predicates") {
    val attribute1 = new AttributeReference("first", IntegerType, false)()
    val predicate1 = new EqualTo(attribute1, new Literal(1, IntegerType))
    val filter1 = ParquetFilters.createFilter(predicate1)
    assert(filter1.isDefined)
    assert(filter1.get.predicate == predicate1, "predicates do not match")
    assert(filter1.get.isInstanceOf[ComparisonFilter])
    val cmpFilter1 = filter1.get.asInstanceOf[ComparisonFilter]
    assert(cmpFilter1.columnName == "first", "column name incorrect")

    val predicate2 = new LessThan(attribute1, new Literal(4, IntegerType))
    val filter2 = ParquetFilters.createFilter(predicate2)
    assert(filter2.isDefined)
    assert(filter2.get.predicate == predicate2, "predicates do not match")
    assert(filter2.get.isInstanceOf[ComparisonFilter])
    val cmpFilter2 = filter2.get.asInstanceOf[ComparisonFilter]
    assert(cmpFilter2.columnName == "first", "column name incorrect")

    val predicate3 = new And(predicate1, predicate2)
    val filter3 = ParquetFilters.createFilter(predicate3)
    assert(filter3.isDefined)
    assert(filter3.get.predicate == predicate3, "predicates do not match")
    assert(filter3.get.isInstanceOf[AndFilter])

    val predicate4 = new Or(predicate1, predicate2)
    val filter4 = ParquetFilters.createFilter(predicate4)
    assert(filter4.isDefined)
    assert(filter4.get.predicate == predicate4, "predicates do not match")
    assert(filter4.get.isInstanceOf[OrFilter])

    val attribute2 = new AttributeReference("second", IntegerType, false)()
    val predicate5 = new GreaterThan(attribute1, attribute2)
    val badfilter = ParquetFilters.createFilter(predicate5)
    assert(badfilter.isDefined === false)

    val predicate6 = And(GreaterThan(attribute1, attribute2), GreaterThan(attribute1, attribute2))
    val badfilter2 = ParquetFilters.createFilter(predicate6)
    assert(badfilter2.isDefined === false)
  }

  test("test filter by predicate pushdown") {
    for(myval <- Seq("myint", "mylong", "mydouble", "myfloat")) {
      val query1 = sql(s"SELECT * FROM testfiltersource WHERE $myval < 150 AND $myval >= 100")
      assert(
        query1.queryExecution.executedPlan(0)(0).isInstanceOf[ParquetTableScan],
        "Top operator should be ParquetTableScan after pushdown")
      val result1 = query1.collect()
      assert(result1.size === 50)
      assert(result1(0)(1) === 100)
      assert(result1(49)(1) === 149)
      val query2 = sql(s"SELECT * FROM testfiltersource WHERE $myval > 150 AND $myval <= 200")
      assert(
        query2.queryExecution.executedPlan(0)(0).isInstanceOf[ParquetTableScan],
        "Top operator should be ParquetTableScan after pushdown")
      val result2 = query2.collect()
      assert(result2.size === 50)
      if (myval == "myint" || myval == "mylong") {
        assert(result2(0)(1) === 151)
        assert(result2(49)(1) === 200)
      } else {
        assert(result2(0)(1) === 150)
        assert(result2(49)(1) === 199)
      }
    }
    for(myval <- Seq("myint", "mylong")) {
      val query3 = sql(s"SELECT * FROM testfiltersource WHERE $myval > 190 OR $myval < 10")
      assert(
        query3.queryExecution.executedPlan(0)(0).isInstanceOf[ParquetTableScan],
        "Top operator should be ParquetTableScan after pushdown")
      val result3 = query3.collect()
      assert(result3.size === 20)
      assert(result3(0)(1) === 0)
      assert(result3(9)(1) === 9)
      assert(result3(10)(1) === 191)
      assert(result3(19)(1) === 200)
    }
    for(myval <- Seq("mydouble", "myfloat")) {
      val result4 =
        if (myval == "mydouble") {
          val query4 = sql(s"SELECT * FROM testfiltersource WHERE $myval > 190.5 OR $myval < 10.0")
          assert(
            query4.queryExecution.executedPlan(0)(0).isInstanceOf[ParquetTableScan],
            "Top operator should be ParquetTableScan after pushdown")
          query4.collect()
        } else {
          // CASTs are problematic. Here myfloat will be casted to a double and it seems there is
          // currently no way to specify float constants in SqlParser?
          sql(s"SELECT * FROM testfiltersource WHERE $myval > 190.5 OR $myval < 10").collect()
        }
      assert(result4.size === 20)
      assert(result4(0)(1) === 0)
      assert(result4(9)(1) === 9)
      assert(result4(10)(1) === 191)
      assert(result4(19)(1) === 200)
    }
    val query5 = sql(s"SELECT * FROM testfiltersource WHERE myboolean = true AND myint < 40")
    assert(
      query5.queryExecution.executedPlan(0)(0).isInstanceOf[ParquetTableScan],
      "Top operator should be ParquetTableScan after pushdown")
    val booleanResult = query5.collect()
    assert(booleanResult.size === 10)
    for(i <- 0 until 10) {
      if (!booleanResult(i).getBoolean(0)) {
        fail(s"Boolean value in result row $i not true")
      }
      if (booleanResult(i).getInt(1) != i * 4) {
        fail(s"Int value in result row $i should be ${4*i}")
      }
    }
    val query6 = sql("SELECT * FROM testfiltersource WHERE mystring = \"100\"")
    assert(
      query6.queryExecution.executedPlan(0)(0).isInstanceOf[ParquetTableScan],
      "Top operator should be ParquetTableScan after pushdown")
    val stringResult = query6.collect()
    assert(stringResult.size === 1)
    assert(stringResult(0).getString(2) == "100", "stringvalue incorrect")
    assert(stringResult(0).getInt(1) === 100)
  }

  test("SPARK-1913 regression: columns only referenced by pushed down filters should remain") {
    val query = sql(s"SELECT mystring FROM testfiltersource WHERE myint < 10")
    assert(query.collect().size === 10)
  }

  test("Importing nested Parquet file (Addressbook)") {
    val result = TestSQLContext
      .parquetFile(ParquetTestData.testNestedDir1.toString)
      .toSchemaRDD
      .collect()
    assert(result != null)
    assert(result.size === 2)
    val first_record = result(0)
    val second_record = result(1)
    assert(first_record != null)
    assert(second_record != null)
    assert(first_record.size === 3)
    assert(second_record(1) === null)
    assert(second_record(2) === null)
    assert(second_record(0) === "A. Nonymous")
    assert(first_record(0) === "Julien Le Dem")
    val first_owner_numbers = first_record(1)
      .asInstanceOf[CatalystConverter.ArrayScalaType[_]]
    val first_contacts = first_record(2)
      .asInstanceOf[CatalystConverter.ArrayScalaType[_]]
    assert(first_owner_numbers != null)
    assert(first_owner_numbers(0) === "555 123 4567")
    assert(first_owner_numbers(2) === "XXX XXX XXXX")
    assert(first_contacts(0)
      .asInstanceOf[CatalystConverter.StructScalaType[_]].size === 2)
    val first_contacts_entry_one = first_contacts(0)
      .asInstanceOf[CatalystConverter.StructScalaType[_]]
    assert(first_contacts_entry_one(0) === "Dmitriy Ryaboy")
    assert(first_contacts_entry_one(1) === "555 987 6543")
    val first_contacts_entry_two = first_contacts(1)
      .asInstanceOf[CatalystConverter.StructScalaType[_]]
    assert(first_contacts_entry_two(0) === "Chris Aniszczyk")
  }

  test("Importing nested Parquet file (nested numbers)") {
    val result = TestSQLContext
      .parquetFile(ParquetTestData.testNestedDir2.toString)
      .toSchemaRDD
      .collect()
    assert(result.size === 1, "number of top-level rows incorrect")
    assert(result(0).size === 5, "number of fields in row incorrect")
    assert(result(0)(0) === 1)
    assert(result(0)(1) === 7)
    val subresult1 = result(0)(2).asInstanceOf[CatalystConverter.ArrayScalaType[_]]
    assert(subresult1.size === 3)
    assert(subresult1(0) === (1.toLong << 32))
    assert(subresult1(1) === (1.toLong << 33))
    assert(subresult1(2) === (1.toLong << 34))
    val subresult2 = result(0)(3)
      .asInstanceOf[CatalystConverter.ArrayScalaType[_]](0)
      .asInstanceOf[CatalystConverter.StructScalaType[_]]
    assert(subresult2.size === 2)
    assert(subresult2(0) === 2.5)
    assert(subresult2(1) === false)
    val subresult3 = result(0)(4)
      .asInstanceOf[CatalystConverter.ArrayScalaType[_]]
    assert(subresult3.size === 2)
    assert(subresult3(0).asInstanceOf[CatalystConverter.ArrayScalaType[_]].size === 2)
    val subresult4 = subresult3(0).asInstanceOf[CatalystConverter.ArrayScalaType[_]]
    assert(subresult4(0).asInstanceOf[CatalystConverter.ArrayScalaType[_]](0) === 7)
    assert(subresult4(1).asInstanceOf[CatalystConverter.ArrayScalaType[_]](0) === 8)
    assert(subresult3(1).asInstanceOf[CatalystConverter.ArrayScalaType[_]].size === 1)
    assert(subresult3(1).asInstanceOf[CatalystConverter.ArrayScalaType[_]](0)
      .asInstanceOf[CatalystConverter.ArrayScalaType[_]](0) === 9)
  }

  test("Simple query on addressbook") {
    val data = TestSQLContext
      .parquetFile(ParquetTestData.testNestedDir1.toString)
      .toSchemaRDD
    val tmp = data.where('owner === "Julien Le Dem").select('owner as 'a, 'contacts as 'c).collect()
    assert(tmp.size === 1)
    assert(tmp(0)(0) === "Julien Le Dem")
  }

  test("Projection in addressbook") {
    val data = nestedParserSqlContext
      .parquetFile(ParquetTestData.testNestedDir1.toString)
      .toSchemaRDD
    data.registerTempTable("data")
    val query = nestedParserSqlContext.sql("SELECT owner, contacts[1].name FROM data")
    val tmp = query.collect()
    assert(tmp.size === 2)
    assert(tmp(0).size === 2)
    assert(tmp(0)(0) === "Julien Le Dem")
    assert(tmp(0)(1) === "Chris Aniszczyk")
    assert(tmp(1)(0) === "A. Nonymous")
    assert(tmp(1)(1) === null)
  }

  test("Simple query on nested int data") {
    val data = nestedParserSqlContext
      .parquetFile(ParquetTestData.testNestedDir2.toString)
      .toSchemaRDD
    data.registerTempTable("data")
    val result1 = nestedParserSqlContext.sql("SELECT entries[0].value FROM data").collect()
    assert(result1.size === 1)
    assert(result1(0).size === 1)
    assert(result1(0)(0) === 2.5)
    val result2 = nestedParserSqlContext.sql("SELECT entries[0] FROM data").collect()
    assert(result2.size === 1)
    val subresult1 = result2(0)(0).asInstanceOf[CatalystConverter.StructScalaType[_]]
    assert(subresult1.size === 2)
    assert(subresult1(0) === 2.5)
    assert(subresult1(1) === false)
    val result3 = nestedParserSqlContext.sql("SELECT outerouter FROM data").collect()
    val subresult2 = result3(0)(0)
      .asInstanceOf[CatalystConverter.ArrayScalaType[_]](0)
      .asInstanceOf[CatalystConverter.ArrayScalaType[_]]
    assert(subresult2(0).asInstanceOf[CatalystConverter.ArrayScalaType[_]](0) === 7)
    assert(subresult2(1).asInstanceOf[CatalystConverter.ArrayScalaType[_]](0) === 8)
    assert(result3(0)(0)
      .asInstanceOf[CatalystConverter.ArrayScalaType[_]](1)
      .asInstanceOf[CatalystConverter.ArrayScalaType[_]](0)
      .asInstanceOf[CatalystConverter.ArrayScalaType[_]](0) === 9)
  }

  test("nested structs") {
    val data = nestedParserSqlContext
      .parquetFile(ParquetTestData.testNestedDir3.toString)
      .toSchemaRDD
    data.registerTempTable("data")
    val result1 = nestedParserSqlContext.sql("SELECT booleanNumberPairs[0].value[0].truth FROM data").collect()
    assert(result1.size === 1)
    assert(result1(0).size === 1)
    assert(result1(0)(0) === false)
    val result2 = nestedParserSqlContext.sql("SELECT booleanNumberPairs[0].value[1].truth FROM data").collect()
    assert(result2.size === 1)
    assert(result2(0).size === 1)
    assert(result2(0)(0) === true)
    val result3 = nestedParserSqlContext.sql("SELECT booleanNumberPairs[1].value[0].truth FROM data").collect()
    assert(result3.size === 1)
    assert(result3(0).size === 1)
    assert(result3(0)(0) === false)
  }

  test("simple map") {
    val data = TestSQLContext
      .parquetFile(ParquetTestData.testNestedDir4.toString)
      .toSchemaRDD
    data.registerTempTable("mapTable")
    val result1 = sql("SELECT data1 FROM mapTable").collect()
    assert(result1.size === 1)
    assert(result1(0)(0)
      .asInstanceOf[CatalystConverter.MapScalaType[String, _]]
      .getOrElse("key1", 0) === 1)
    assert(result1(0)(0)
      .asInstanceOf[CatalystConverter.MapScalaType[String, _]]
      .getOrElse("key2", 0) === 2)
    val result2 = sql("""SELECT data1["key1"] FROM mapTable""").collect()
    assert(result2(0)(0) === 1)
  }

  test("map with struct values") {
    val data = nestedParserSqlContext
      .parquetFile(ParquetTestData.testNestedDir4.toString)
      .toSchemaRDD
    data.registerTempTable("mapTable")
    val result1 = nestedParserSqlContext.sql("SELECT data2 FROM mapTable").collect()
    assert(result1.size === 1)
    val entry1 = result1(0)(0)
      .asInstanceOf[CatalystConverter.MapScalaType[String, CatalystConverter.StructScalaType[_]]]
      .getOrElse("seven", null)
    assert(entry1 != null)
    assert(entry1(0) === 42)
    assert(entry1(1) === "the answer")
    val entry2 = result1(0)(0)
      .asInstanceOf[CatalystConverter.MapScalaType[String, CatalystConverter.StructScalaType[_]]]
      .getOrElse("eight", null)
    assert(entry2 != null)
    assert(entry2(0) === 49)
    assert(entry2(1) === null)
    val result2 = nestedParserSqlContext.sql("""SELECT data2["seven"].payload1, data2["seven"].payload2 FROM mapTable""").collect()
    assert(result2.size === 1)
    assert(result2(0)(0) === 42.toLong)
    assert(result2(0)(1) === "the answer")
  }

  test("Writing out Addressbook and reading it back in") {
    // TODO: find out why CatalystConverter.ARRAY_ELEMENTS_SCHEMA_NAME
    // has no effect in this test case
    val tmpdir = Utils.createTempDir()
    Utils.deleteRecursively(tmpdir)
    val result = nestedParserSqlContext
      .parquetFile(ParquetTestData.testNestedDir1.toString)
      .toSchemaRDD
    result.saveAsParquetFile(tmpdir.toString)
    nestedParserSqlContext
      .parquetFile(tmpdir.toString)
      .toSchemaRDD
      .registerTempTable("tmpcopy")
    val tmpdata = nestedParserSqlContext.sql("SELECT owner, contacts[1].name FROM tmpcopy").collect()
    assert(tmpdata.size === 2)
    assert(tmpdata(0).size === 2)
    assert(tmpdata(0)(0) === "Julien Le Dem")
    assert(tmpdata(0)(1) === "Chris Aniszczyk")
    assert(tmpdata(1)(0) === "A. Nonymous")
    assert(tmpdata(1)(1) === null)
    Utils.deleteRecursively(tmpdir)
  }

  test("Writing out Map and reading it back in") {
    val data = nestedParserSqlContext
      .parquetFile(ParquetTestData.testNestedDir4.toString)
      .toSchemaRDD
    val tmpdir = Utils.createTempDir()
    Utils.deleteRecursively(tmpdir)
    data.saveAsParquetFile(tmpdir.toString)
    nestedParserSqlContext
      .parquetFile(tmpdir.toString)
      .toSchemaRDD
      .registerTempTable("tmpmapcopy")
    val result1 = nestedParserSqlContext.sql("""SELECT data1["key2"] FROM tmpmapcopy""").collect()
    assert(result1.size === 1)
    assert(result1(0)(0) === 2)
    val result2 = nestedParserSqlContext.sql("SELECT data2 FROM tmpmapcopy").collect()
    assert(result2.size === 1)
    val entry1 = result2(0)(0)
      .asInstanceOf[CatalystConverter.MapScalaType[String, CatalystConverter.StructScalaType[_]]]
      .getOrElse("seven", null)
    assert(entry1 != null)
    assert(entry1(0) === 42)
    assert(entry1(1) === "the answer")
    val entry2 = result2(0)(0)
      .asInstanceOf[CatalystConverter.MapScalaType[String, CatalystConverter.StructScalaType[_]]]
      .getOrElse("eight", null)
    assert(entry2 != null)
    assert(entry2(0) === 49)
    assert(entry2(1) === null)
    val result3 = nestedParserSqlContext.sql("""SELECT data2["seven"].payload1, data2["seven"].payload2 FROM tmpmapcopy""").collect()
    assert(result3.size === 1)
    assert(result3(0)(0) === 42.toLong)
    assert(result3(0)(1) === "the answer")
    Utils.deleteRecursively(tmpdir)
  }
}

// TODO: the code below is needed temporarily until the standard parser is able to parse
// nested field expressions correctly
class NestedParserSQLContext(@transient override val sparkContext: SparkContext) extends SQLContext(sparkContext) {
  override protected[sql] val parser = new NestedSqlParser()
}

class NestedSqlLexical(override val keywords: Seq[String]) extends SqlLexical(keywords) {
  override def identChar = letter | elem('_')
  delimiters += (".")
}

class NestedSqlParser extends SqlParser {
  override val lexical = new NestedSqlLexical(reservedWords)

  override protected lazy val baseExpression: PackratParser[Expression] =
    expression ~ "[" ~ expression <~ "]" ^^ {
      case base ~ _ ~ ordinal => GetItem(base, ordinal)
    } |
    expression ~ "." ~ ident ^^ {
      case base ~ _ ~ fieldName => GetField(base, fieldName)
    } |
    TRUE ^^^ Literal(true, BooleanType) |
    FALSE ^^^ Literal(false, BooleanType) |
    cast |
    "(" ~> expression <~ ")" |
    function |
    "-" ~> literal ^^ UnaryMinus |
    ident ^^ UnresolvedAttribute |
    "*" ^^^ Star(None) |
    literal
}
