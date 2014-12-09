---
layout: global
title: Spark SQL Programming Guide
---

* This will become a table of contents (this text will be scraped).
{:toc}

# Overview

<div class="codetabs">
<div data-lang="scala"  markdown="1">

Spark SQL allows relational queries expressed in SQL, HiveQL, or Scala to be executed using
Spark.  At the core of this component is a new type of RDD,
[SchemaRDD](api/scala/index.html#org.apache.spark.sql.SchemaRDD).  SchemaRDDs are composed
[Row](api/scala/index.html#org.apache.spark.sql.catalyst.expressions.Row) objects along with
a schema that describes the data types of each column in the row.  A SchemaRDD is similar to a table
in a traditional relational database.  A SchemaRDD can be created from an existing RDD, [Parquet](http://parquet.io)
file, a JSON dataset, or by running HiveQL against data stored in [Apache Hive](http://hive.apache.org/).

All of the examples on this page use sample data included in the Spark distribution and can be run in the `spark-shell`.

</div>

<div data-lang="java"  markdown="1">
Spark SQL allows relational queries expressed in SQL or HiveQL to be executed using
Spark.  At the core of this component is a new type of RDD,
[JavaSchemaRDD](api/scala/index.html#org.apache.spark.sql.api.java.JavaSchemaRDD).  JavaSchemaRDDs are composed
[Row](api/scala/index.html#org.apache.spark.sql.api.java.Row) objects along with
a schema that describes the data types of each column in the row.  A JavaSchemaRDD is similar to a table
in a traditional relational database.  A JavaSchemaRDD can be created from an existing RDD, [Parquet](http://parquet.io)
file, a JSON dataset, or by running HiveQL against data stored in [Apache Hive](http://hive.apache.org/).
</div>

<div data-lang="python"  markdown="1">

Spark SQL allows relational queries expressed in SQL or HiveQL to be executed using
Spark.  At the core of this component is a new type of RDD,
[SchemaRDD](api/python/pyspark.sql.SchemaRDD-class.html).  SchemaRDDs are composed
[Row](api/python/pyspark.sql.Row-class.html) objects along with
a schema that describes the data types of each column in the row.  A SchemaRDD is similar to a table
in a traditional relational database.  A SchemaRDD can be created from an existing RDD, [Parquet](http://parquet.io)
file, a JSON dataset, or by running HiveQL against data stored in [Apache Hive](http://hive.apache.org/).

All of the examples on this page use sample data included in the Spark distribution and can be run in the `pyspark` shell.
</div>
</div>

**Spark SQL is currently an alpha component. While we will minimize API changes, some APIs may change in future releases.**

***************************************************************************************************

# Getting Started

<div class="codetabs">
<div data-lang="scala"  markdown="1">

The entry point into all relational functionality in Spark is the
[SQLContext](api/scala/index.html#org.apache.spark.sql.SQLContext) class, or one of its
descendants.  To create a basic SQLContext, all you need is a SparkContext.

{% highlight scala %}
val sc: SparkContext // An existing SparkContext.
val sqlContext = new org.apache.spark.sql.SQLContext(sc)

// createSchemaRDD is used to implicitly convert an RDD to a SchemaRDD.
import sqlContext.createSchemaRDD
{% endhighlight %}

</div>

<div data-lang="java" markdown="1">

The entry point into all relational functionality in Spark is the
[JavaSQLContext](api/scala/index.html#org.apache.spark.sql.api.java.JavaSQLContext) class, or one
of its descendants.  To create a basic JavaSQLContext, all you need is a JavaSparkContext.

{% highlight java %}
JavaSparkContext sc = ...; // An existing JavaSparkContext.
JavaSQLContext sqlContext = new org.apache.spark.sql.api.java.JavaSQLContext(sc);
{% endhighlight %}

</div>

<div data-lang="python"  markdown="1">

The entry point into all relational functionality in Spark is the
[SQLContext](api/python/pyspark.sql.SQLContext-class.html) class, or one
of its decedents.  To create a basic SQLContext, all you need is a SparkContext.

{% highlight python %}
from pyspark.sql import SQLContext
sqlContext = SQLContext(sc)
{% endhighlight %}

</div>

</div>

# Data Sources

<div class="codetabs">
<div data-lang="scala"  markdown="1">
Spark SQL supports operating on a variety of data sources through the `SchemaRDD` interface.
Once a dataset has been loaded, it can be registered as a table and even joined with data from other sources.
</div>

<div data-lang="java"  markdown="1">
Spark SQL supports operating on a variety of data sources through the `JavaSchemaRDD` interface.
Once a dataset has been loaded, it can be registered as a table and even joined with data from other sources.
</div>

<div data-lang="python"  markdown="1">
Spark SQL supports operating on a variety of data sources through the `SchemaRDD` interface.
Once a dataset has been loaded, it can be registered as a table and even joined with data from other sources.
</div>
</div>

## RDDs

<div class="codetabs">

<div data-lang="scala"  markdown="1">

One type of table that is supported by Spark SQL is an RDD of Scala case classes.  The case class
defines the schema of the table.  The names of the arguments to the case class are read using
reflection and become the names of the columns. Case classes can also be nested or contain complex
types such as Sequences or Arrays. This RDD can be implicitly converted to a SchemaRDD and then be
registered as a table.  Tables can be used in subsequent SQL statements.

{% highlight scala %}
// sc is an existing SparkContext.
val sqlContext = new org.apache.spark.sql.SQLContext(sc)
// createSchemaRDD is used to implicitly convert an RDD to a SchemaRDD.
import sqlContext.createSchemaRDD

// Define the schema using a case class.
// Note: Case classes in Scala 2.10 can support only up to 22 fields. To work around this limit,
// you can use custom classes that implement the Product interface.
case class Person(name: String, age: Int)

// Create an RDD of Person objects and register it as a table.
val people = sc.textFile("examples/src/main/resources/people.txt").map(_.split(",")).map(p => Person(p(0), p(1).trim.toInt))
people.registerTempTable("people")

// SQL statements can be run by using the sql methods provided by sqlContext.
val teenagers = sqlContext.sql("SELECT name FROM people WHERE age >= 13 AND age <= 19")

// The results of SQL queries are SchemaRDDs and support all the normal RDD operations.
// The columns of a row in the result can be accessed by ordinal.
teenagers.map(t => "Name: " + t(0)).collect().foreach(println)
{% endhighlight %}

</div>

<div data-lang="java"  markdown="1">

One type of table that is supported by Spark SQL is an RDD of [JavaBeans](http://stackoverflow.com/questions/3295496/what-is-a-javabean-exactly).  The BeanInfo
defines the schema of the table. Currently, Spark SQL does not support JavaBeans that contain
nested or contain complex types such as Lists or Arrays.  You can create a JavaBean by creating a
class that implements Serializable and has getters and setters for all of its fields.

{% highlight java %}

public static class Person implements Serializable {
  private String name;
  private int age;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public int getAge() {
    return age;
  }

  public void setAge(int age) {
    this.age = age;
  }
}

{% endhighlight %}


A schema can be applied to an existing RDD by calling `applySchema` and providing the Class object
for the JavaBean.

{% highlight java %}
// sc is an existing JavaSparkContext.
JavaSQLContext sqlContext = new org.apache.spark.sql.api.java.JavaSQLContext(sc)

// Load a text file and convert each line to a JavaBean.
JavaRDD<Person> people = sc.textFile("examples/src/main/resources/people.txt").map(
  new Function<String, Person>() {
    public Person call(String line) throws Exception {
      String[] parts = line.split(",");

      Person person = new Person();
      person.setName(parts[0]);
      person.setAge(Integer.parseInt(parts[1].trim()));

      return person;
    }
  });

// Apply a schema to an RDD of JavaBeans and register it as a table.
JavaSchemaRDD schemaPeople = sqlContext.applySchema(people, Person.class);
schemaPeople.registerTempTable("people");

// SQL can be run over RDDs that have been registered as tables.
JavaSchemaRDD teenagers = sqlContext.sql("SELECT name FROM people WHERE age >= 13 AND age <= 19")

// The results of SQL queries are SchemaRDDs and support all the normal RDD operations.
// The columns of a row in the result can be accessed by ordinal.
List<String> teenagerNames = teenagers.map(new Function<Row, String>() {
  public String call(Row row) {
    return "Name: " + row.getString(0);
  }
}).collect();

{% endhighlight %}

</div>

<div data-lang="python"  markdown="1">

One type of table that is supported by Spark SQL is an RDD of dictionaries.  The keys of the
dictionary define the columns names of the table, and the types are inferred by looking at the first
row. Any RDD of dictionaries can converted to a SchemaRDD and then registered as a table.  Tables
can be used in subsequent SQL statements.

{% highlight python %}
# sc is an existing SparkContext.
from pyspark.sql import SQLContext
sqlContext = SQLContext(sc)

# Load a text file and convert each line to a dictionary.
lines = sc.textFile("examples/src/main/resources/people.txt")
parts = lines.map(lambda l: l.split(","))
people = parts.map(lambda p: {"name": p[0], "age": int(p[1])})

# Infer the schema, and register the SchemaRDD as a table.
# In future versions of PySpark we would like to add support for registering RDDs with other
# datatypes as tables
schemaPeople = sqlContext.inferSchema(people)
schemaPeople.registerTempTable("people")

# SQL can be run over SchemaRDDs that have been registered as a table.
teenagers = sqlContext.sql("SELECT name FROM people WHERE age >= 13 AND age <= 19")

# The results of SQL queries are RDDs and support all the normal RDD operations.
teenNames = teenagers.map(lambda p: "Name: " + p.name)
for teenName in teenNames.collect():
  print teenName
{% endhighlight %}

</div>

</div>

**Note that Spark SQL currently uses a very basic SQL parser.**
Users that want a more complete dialect of SQL should look at the HiveQL support provided by
`HiveContext`.

## Parquet Files

[Parquet](http://parquet.io) is a columnar format that is supported by many other data processing systems.
Spark SQL provides support for both reading and writing Parquet files that automatically preserves the schema
of the original data.  Using the data from the above example:

<div class="codetabs">

<div data-lang="scala"  markdown="1">

{% highlight scala %}
// sqlContext from the previous example is used in this example.
// createSchemaRDD is used to implicitly convert an RDD to a SchemaRDD.
import sqlContext.createSchemaRDD

val people: RDD[Person] = ... // An RDD of case class objects, from the previous example.

// The RDD is implicitly converted to a SchemaRDD by createSchemaRDD, allowing it to be stored using Parquet.
people.saveAsParquetFile("people.parquet")

// Read in the parquet file created above.  Parquet files are self-describing so the schema is preserved.
// The result of loading a Parquet file is also a SchemaRDD.
val parquetFile = sqlContext.parquetFile("people.parquet")

//Parquet files can also be registered as tables and then used in SQL statements.
parquetFile.registerTempTable("parquetFile")
val teenagers = sqlContext.sql("SELECT name FROM parquetFile WHERE age >= 13 AND age <= 19")
teenagers.map(t => "Name: " + t(0)).collect().foreach(println)
{% endhighlight %}

</div>

<div data-lang="java"  markdown="1">

{% highlight java %}
// sqlContext from the previous example is used in this example.

JavaSchemaRDD schemaPeople = ... // The JavaSchemaRDD from the previous example.

// JavaSchemaRDDs can be saved as Parquet files, maintaining the schema information.
schemaPeople.saveAsParquetFile("people.parquet");

// Read in the Parquet file created above.  Parquet files are self-describing so the schema is preserved.
// The result of loading a parquet file is also a JavaSchemaRDD.
JavaSchemaRDD parquetFile = sqlContext.parquetFile("people.parquet");

//Parquet files can also be registered as tables and then used in SQL statements.
parquetFile.registerTempTable("parquetFile");
JavaSchemaRDD teenagers = sqlContext.sql("SELECT name FROM parquetFile WHERE age >= 13 AND age <= 19");
List<String> teenagerNames = teenagers.map(new Function<Row, String>() {
  public String call(Row row) {
    return "Name: " + row.getString(0);
  }
}).collect();
{% endhighlight %}

</div>

<div data-lang="python"  markdown="1">

{% highlight python %}
# sqlContext from the previous example is used in this example.

schemaPeople # The SchemaRDD from the previous example.

# SchemaRDDs can be saved as Parquet files, maintaining the schema information.
schemaPeople.saveAsParquetFile("people.parquet")

# Read in the Parquet file created above.  Parquet files are self-describing so the schema is preserved.
# The result of loading a parquet file is also a SchemaRDD.
parquetFile = sqlContext.parquetFile("people.parquet")

# Parquet files can also be registered as tables and then used in SQL statements.
parquetFile.registerTempTable("parquetFile");
teenagers = sqlContext.sql("SELECT name FROM parquetFile WHERE age >= 13 AND age <= 19")
teenNames = teenagers.map(lambda p: "Name: " + p.name)
for teenName in teenNames.collect():
  print teenName
{% endhighlight %}

</div>

</div>

## JSON Datasets
<div class="codetabs">

<div data-lang="scala"  markdown="1">
Spark SQL can automatically infer the schema of a JSON dataset and load it as a SchemaRDD.
This conversion can be done using one of two methods in a SQLContext:

* `jsonFile` - loads data from a directory of JSON files where each line of the files is a JSON object.
* `jsonRdd` - loads data from an existing RDD where each element of the RDD is a string containing a JSON object.

{% highlight scala %}
// sc is an existing SparkContext.
val sqlContext = new org.apache.spark.sql.SQLContext(sc)

// A JSON dataset is pointed to by path.
// The path can be either a single text file or a directory storing text files.
val path = "examples/src/main/resources/people.json"
// Create a SchemaRDD from the file(s) pointed to by path
val people = sqlContext.jsonFile(path)

// The inferred schema can be visualized using the printSchema() method.
people.printSchema()
// root
//  |-- age: IntegerType
//  |-- name: StringType

// Register this SchemaRDD as a table.
people.registerTempTable("people")

// SQL statements can be run by using the sql methods provided by sqlContext.
val teenagers = sqlContext.sql("SELECT name FROM people WHERE age >= 13 AND age <= 19")

// Alternatively, a SchemaRDD can be created for a JSON dataset represented by
// an RDD[String] storing one JSON object per string.
val anotherPeopleRDD = sc.parallelize(
  """{"name":"Yin","address":{"city":"Columbus","state":"Ohio"}}""" :: Nil)
val anotherPeople = sqlContext.jsonRDD(anotherPeopleRDD)
{% endhighlight %}

</div>

<div data-lang="java"  markdown="1">
Spark SQL can automatically infer the schema of a JSON dataset and load it as a JavaSchemaRDD.
This conversion can be done using one of two methods in a JavaSQLContext :

* `jsonFile` - loads data from a directory of JSON files where each line of the files is a JSON object.
* `jsonRdd` - loads data from an existing RDD where each element of the RDD is a string containing a JSON object.

{% highlight java %}
// sc is an existing JavaSparkContext.
JavaSQLContext sqlContext = new org.apache.spark.sql.api.java.JavaSQLContext(sc);

// A JSON dataset is pointed to by path.
// The path can be either a single text file or a directory storing text files.
String path = "examples/src/main/resources/people.json";
// Create a JavaSchemaRDD from the file(s) pointed to by path
JavaSchemaRDD people = sqlContext.jsonFile(path);

// The inferred schema can be visualized using the printSchema() method.
people.printSchema();
// root
//  |-- age: IntegerType
//  |-- name: StringType

// Register this JavaSchemaRDD as a table.
people.registerTempTable("people");

// SQL statements can be run by using the sql methods provided by sqlContext.
JavaSchemaRDD teenagers = sqlContext.sql("SELECT name FROM people WHERE age >= 13 AND age <= 19");

// Alternatively, a JavaSchemaRDD can be created for a JSON dataset represented by
// an RDD[String] storing one JSON object per string.
List<String> jsonData = Arrays.asList(
  "{\"name\":\"Yin\",\"address\":{\"city\":\"Columbus\",\"state\":\"Ohio\"}}");
JavaRDD<String> anotherPeopleRDD = sc.parallelize(jsonData);
JavaSchemaRDD anotherPeople = sqlContext.jsonRDD(anotherPeopleRDD);
{% endhighlight %}
</div>

<div data-lang="python"  markdown="1">
Spark SQL can automatically infer the schema of a JSON dataset and load it as a SchemaRDD.
This conversion can be done using one of two methods in a SQLContext:

* `jsonFile` - loads data from a directory of JSON files where each line of the files is a JSON object.
* `jsonRdd` - loads data from an existing RDD where each element of the RDD is a string containing a JSON object.

{% highlight python %}
# sc is an existing SparkContext.
from pyspark.sql import SQLContext
sqlContext = SQLContext(sc)

# A JSON dataset is pointed to by path.
# The path can be either a single text file or a directory storing text files.
path = "examples/src/main/resources/people.json"
# Create a SchemaRDD from the file(s) pointed to by path
people = sqlContext.jsonFile(path)

# The inferred schema can be visualized using the printSchema() method.
people.printSchema()
# root
#  |-- age: IntegerType
#  |-- name: StringType

# Register this SchemaRDD as a table.
people.registerTempTable("people")

# SQL statements can be run by using the sql methods provided by sqlContext.
teenagers = sqlContext.sql("SELECT name FROM people WHERE age >= 13 AND age <= 19")

# Alternatively, a SchemaRDD can be created for a JSON dataset represented by
# an RDD[String] storing one JSON object per string.
anotherPeopleRDD = sc.parallelize([
  '{"name":"Yin","address":{"city":"Columbus","state":"Ohio"}}'])
anotherPeople = sqlContext.jsonRDD(anotherPeopleRDD)
{% endhighlight %}
</div>

</div>

## Hive Tables

Spark SQL also supports reading and writing data stored in [Apache Hive](http://hive.apache.org/).
However, since Hive has a large number of dependencies, it is not included in the default Spark assembly.
In order to use Hive you must first run "`sbt/sbt -Phive assembly/assembly`" (or use `-Phive` for maven).
This command builds a new assembly jar that includes Hive. Note that this Hive assembly jar must also be present
on all of the worker nodes, as they will need access to the Hive serialization and deserialization libraries
(SerDes) in order to access data stored in Hive.

Configuration of Hive is done by placing your `hive-site.xml` file in `conf/`.

<div class="codetabs">

<div data-lang="scala"  markdown="1">

When working with Hive one must construct a `HiveContext`, which inherits from `SQLContext`, and
adds support for finding tables in in the MetaStore and writing queries using HiveQL. Users who do
not have an existing Hive deployment can still create a HiveContext.  When not configured by the
hive-site.xml, the context automatically creates `metastore_db` and `warehouse` in the current
directory.

{% highlight scala %}
// sc is an existing SparkContext.
val hiveContext = new org.apache.spark.sql.hive.HiveContext(sc)

hiveContext.sql("CREATE TABLE IF NOT EXISTS src (key INT, value STRING)")
hiveContext.sql("LOAD DATA LOCAL INPATH 'examples/src/main/resources/kv1.txt' INTO TABLE src")

// Queries are expressed in HiveQL
hiveContext.sql("FROM src SELECT key, value").collect().foreach(println)
{% endhighlight %}

</div>

<div data-lang="java"  markdown="1">

When working with Hive one must construct a `JavaHiveContext`, which inherits from `JavaSQLContext`, and
adds support for finding tables in in the MetaStore and writing queries using HiveQL. In addition to
the `sql` method a `JavaHiveContext` also provides an `hql` methods, which allows queries to be
expressed in HiveQL.

{% highlight java %}
// sc is an existing JavaSparkContext.
JavaHiveContext hiveContext = new org.apache.spark.sql.hive.api.java.HiveContext(sc);

hiveContext.sql("CREATE TABLE IF NOT EXISTS src (key INT, value STRING)");
hiveContext.sql("LOAD DATA LOCAL INPATH 'examples/src/main/resources/kv1.txt' INTO TABLE src");

// Queries are expressed in HiveQL.
Row[] results = hiveContext.sql("FROM src SELECT key, value").collect();

{% endhighlight %}

</div>

<div data-lang="python"  markdown="1">

When working with Hive one must construct a `HiveContext`, which inherits from `SQLContext`, and
adds support for finding tables in in the MetaStore and writing queries using HiveQL. In addition to
the `sql` method a `HiveContext` also provides an `hql` methods, which allows queries to be
expressed in HiveQL.

{% highlight python %}
# sc is an existing SparkContext.
from pyspark.sql import HiveContext
hiveContext = HiveContext(sc)

hiveContext.sql("CREATE TABLE IF NOT EXISTS src (key INT, value STRING)")
hiveContext.sql("LOAD DATA LOCAL INPATH 'examples/src/main/resources/kv1.txt' INTO TABLE src")

# Queries can be expressed in HiveQL.
results = hiveContext.sql("FROM src SELECT key, value").collect()

{% endhighlight %}

</div>
</div>

# Writing Language-Integrated Relational Queries

**Language-Integrated queries are currently only supported in Scala.**

Spark SQL also supports a domain specific language for writing queries.  Once again,
using the data from the above examples:

{% highlight scala %}
// sc is an existing SparkContext.
val sqlContext = new org.apache.spark.sql.SQLContext(sc)
// Importing the SQL context gives access to all the public SQL functions and implicit conversions.
import sqlContext._
val people: RDD[Person] = ... // An RDD of case class objects, from the first example.

// The following is the same as 'SELECT name FROM people WHERE age >= 10 AND age <= 19'
val teenagers = people.where('age >= 10).where('age <= 19).select('name)
teenagers.map(t => "Name: " + t(0)).collect().foreach(println)
{% endhighlight %}

The DSL uses Scala symbols to represent columns in the underlying table, which are identifiers
prefixed with a tick (`'`).  Implicit conversions turn these symbols into expressions that are
evaluated by the SQL execution engine.  A full list of the functions supported can be found in the
[ScalaDoc](api/scala/index.html#org.apache.spark.sql.SchemaRDD).

<!-- TODO: Include the table of operations here. -->

## Running the Thrift JDBC server

The Thrift JDBC server implemented here corresponds to the [`HiveServer2`](https://cwiki.apache.org/confluence/display/Hive/Setting+Up+HiveServer2)
in Hive 0.12. You can test the JDBC server with the beeline script comes with either Spark or Hive 0.12.

To start the JDBC server, run the following in the Spark directory:

    ./sbin/start-thriftserver.sh

The default port the server listens on is 10000.  To listen on customized host and port, please set
the `HIVE_SERVER2_THRIFT_PORT` and `HIVE_SERVER2_THRIFT_BIND_HOST` environment variables. You may
run `./sbin/start-thriftserver.sh --help` for a complete list of all available options.  Now you can
use beeline to test the Thrift JDBC server:

    ./bin/beeline

Connect to the JDBC server in beeline with:

    beeline> !connect jdbc:hive2://localhost:10000

Beeline will ask you for a username and password. In non-secure mode, simply enter the username on
your machine and a blank password. For secure mode, please follow the instructions given in the
[beeline documentation](https://cwiki.apache.org/confluence/display/Hive/HiveServer2+Clients).

Configuration of Hive is done by placing your `hive-site.xml` file in `conf/`.

You may also use the beeline script comes with Hive.

To set a [Fair Scheduler](job-scheduling.html#fair-scheduler-pools) pool for a JDBC client session,
users can set the `spark.sql.thriftserver.scheduler.pool` variable:

    SET spark.sql.thriftserver.scheduler.pool=accounting;

### Migration Guide for Shark Users

#### Reducer number

In Shark, default reducer number is 1 and is controlled by the property `mapred.reduce.tasks`. Spark
SQL deprecates this property by a new property `spark.sql.shuffle.partitions`, whose default value
is 200. Users may customize this property via `SET`:

    SET spark.sql.shuffle.partitions=10;
    SELECT page, count(*) c 
    FROM logs_last_month_cached
    GROUP BY page ORDER BY c DESC LIMIT 10;

You may also put this property in `hive-site.xml` to override the default value.

For now, the `mapred.reduce.tasks` property is still recognized, and is converted to
`spark.sql.shuffle.partitions` automatically.

#### Caching

The `shark.cache` table property no longer exists, and tables whose name end with `_cached` are no
longer automatically cached. Instead, we provide `CACHE TABLE` and `UNCACHE TABLE` statements to
let user control table caching explicitly:

    CACHE TABLE logs_last_month;
    UNCACHE TABLE logs_last_month;

**NOTE:** `CACHE TABLE tbl` is lazy, it only marks table `tbl` as "need to by cached if necessary",
but doesn't actually cache it until a query that touches `tbl` is executed. To force the table to be
cached, you may simply count the table immediately after executing `CACHE TABLE`:

    CACHE TABLE logs_last_month;
    SELECT COUNT(1) FROM logs_last_month;

Several caching related features are not supported yet:

* User defined partition level cache eviction policy
* RDD reloading
* In-memory cache write through policy

### Compatibility with Apache Hive

#### Deploying in Existing Hive Warehouses

Spark SQL Thrift JDBC server is designed to be "out of the box" compatible with existing Hive
installations. You do not need to modify your existing Hive Metastore or change the data placement
or partitioning of your tables.

#### Supported Hive Features

Spark SQL supports the vast majority of Hive features, such as:

* Hive query statements, including:
  * `SELECT`
  * `GROUP BY`
  * `ORDER BY`
  * `CLUSTER BY`
  * `SORT BY`
* All Hive operators, including:
  * Relational operators (`=`, `⇔`, `==`, `<>`, `<`, `>`, `>=`, `<=`, etc)
  * Arithmetic operators (`+`, `-`, `*`, `/`, `%`, etc)
  * Logical operators (`AND`, `&&`, `OR`, `||`, etc)
  * Complex type constructors
  * Mathematical functions (`sign`, `ln`, `cos`, etc)
  * String functions (`instr`, `length`, `printf`, etc)
* User defined functions (UDF)
* User defined aggregation functions (UDAF)
* User defined serialization formats (SerDes)
* Joins
  * `JOIN`
  * `{LEFT|RIGHT|FULL} OUTER JOIN`
  * `LEFT SEMI JOIN`
  * `CROSS JOIN`
* Unions
* Sub-queries
  * `SELECT col FROM ( SELECT a + b AS col from t1) t2`
* Sampling
* Explain
* Partitioned tables
* All Hive DDL Functions, including:
  * `CREATE TABLE`
  * `CREATE TABLE AS SELECT`
  * `ALTER TABLE`
* Most Hive Data types, including:
  * `TINYINT`
  * `SMALLINT`
  * `INT`
  * `BIGINT`
  * `BOOLEAN`
  * `FLOAT`
  * `DOUBLE`
  * `STRING`
  * `BINARY`
  * `TIMESTAMP`
  * `ARRAY<>`
  * `MAP<>`
  * `STRUCT<>`

#### Unsupported Hive Functionality

Below is a list of Hive features that we don't support yet. Most of these features are rarely used
in Hive deployments.

**Major Hive Features**

* Tables with buckets: bucket is the hash partitioning within a Hive table partition. Spark SQL
  doesn't support buckets yet.

**Esoteric Hive Features**

* Tables with partitions using different input formats: In Spark SQL, all table partitions need to
  have the same input format.
* Non-equi outer join: For the uncommon use case of using outer joins with non-equi join conditions
  (e.g. condition "`key < 10`"), Spark SQL will output wrong result for the `NULL` tuple.
* `UNIONTYPE`
* Unique join
* Single query multi insert
* Column statistics collecting: Spark SQL does not piggyback scans to collect column statistics at
  the moment.

**Hive Input/Output Formats**

* File format for CLI: For results showing back to the CLI, Spark SQL only supports TextOutputFormat.
* Hadoop archive

**Hive Optimizations**

A handful of Hive optimizations are not yet included in Spark. Some of these (such as indexes) are
not necessary due to Spark SQL's in-memory computational model. Others are slotted for future
releases of Spark SQL.

* Block level bitmap indexes and virtual columns (used to build indexes)
* Automatically convert a join to map join: For joining a large table with multiple small tables,
  Hive automatically converts the join into a map join. We are adding this auto conversion in the
  next release.
* Automatically determine the number of reducers for joins and groupbys: Currently in Spark SQL, you
  need to control the degree of parallelism post-shuffle using "`SET spark.sql.shuffle.partitions=[num_tasks];`". We are going to add auto-setting of parallelism in the
  next release.
* Meta-data only query: For queries that can be answered by using only meta data, Spark SQL still
  launches tasks to compute the result.
* Skew data flag: Spark SQL does not follow the skew data flags in Hive.
* `STREAMTABLE` hint in join: Spark SQL does not follow the `STREAMTABLE` hint.
* Merge multiple small files for query results: if the result output contains multiple small files,
  Hive can optionally merge the small files into fewer large files to avoid overflowing the HDFS
  metadata. Spark SQL does not support that.

## Running the Spark SQL CLI

The Spark SQL CLI is a convenient tool to run the Hive metastore service in local mode and execute
queries input from command line. Note: the Spark SQL CLI cannot talk to the Thrift JDBC server.

To start the Spark SQL CLI, run the following in the Spark directory:

    ./bin/spark-sql

Configuration of Hive is done by placing your `hive-site.xml` file in `conf/`.
You may run `./bin/spark-sql --help` for a complete list of all available
options.

# Cached tables

Spark SQL can cache tables using an in-memory columnar format by calling `cacheTable("tableName")`.
Then Spark SQL will scan only required columns and will automatically tune compression to minimize
memory usage and GC pressure. You can call `uncacheTable("tableName")` to remove the table from memory.

Note that if you just call `cache` rather than `cacheTable`, tables will _not_ be cached in
in-memory columnar format. So we strongly recommend using `cacheTable` whenever you want to
cache tables.
