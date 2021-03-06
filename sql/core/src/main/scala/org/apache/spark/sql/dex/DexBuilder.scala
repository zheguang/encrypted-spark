/*
Copyright 2020, Brown University, Providence, RI.

                        All Rights Reserved

Permission to use, copy, modify, and distribute this software and
its documentation for any purpose other than its incorporation into a
commercial product or service is hereby granted without fee, provided
that the above copyright notice appear in all copies and that both
that copyright notice and this permission notice appear in supporting
documentation, and that the name of Brown University not be used in
advertising or publicity pertaining to distribution of the software
without specific, written prior permission.

BROWN UNIVERSITY DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE,
INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR ANY
PARTICULAR PURPOSE.  IN NO EVENT SHALL BROWN UNIVERSITY BE LIABLE FOR
ANY SPECIAL, INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package org.apache.spark.sql.dex
// scalastyle:off

import java.sql.{Connection, DriverManager}
import java.util.Properties

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.dex.{Crypto, DataCodec, DexConstants, DexException, DexPrimitives}
import org.apache.spark.sql.dex.DexBuilder.{ForeignKey, PrimaryKey, createHashIndex, createTreeIndex}
import org.apache.spark.sql.catalyst.dex.DexConstants._
import org.apache.spark.sql.catalyst.dex.DexPrimitives._
import org.apache.spark.sql.catalyst.expressions.Literal
import org.apache.spark.sql.execution.datasources.jdbc.JDBCOptions
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import org.apache.spark.sql.jdbc.JdbcDialects
import org.apache.spark.sql.types.LongType
import org.apache.spark.sql.{Column, DataFrame, SaveMode, SparkSession}
import org.apache.spark.util.Utils

object DexVariant {
  def from(str: String): DexVariant = str.toLowerCase match {
    case "dexspx" => DexSpx
    case "dexcorr" => DexCorr
    case "dexpkfk" => DexPkFk
  }
}
sealed trait DexVariant {
  def name: String = getClass.getSimpleName
}
sealed trait DexStandalone
case object DexSpx extends DexVariant with DexStandalone
case object DexCorr extends DexVariant with DexStandalone
case object DexPkFk extends DexVariant

object DexBuilder {
  def createHashIndex(conn: Connection, tableName: String, col: String): Unit = {
    conn.prepareStatement(
      s"""
         |CREATE INDEX IF NOT EXISTS "${tableName}_${col}_hash"
         |ON "$tableName"
         |USING HASH ($col)
      """.stripMargin).executeUpdate()
  }

  def createTreeIndex(conn: Connection, tableName: String, col: String): Unit = {
    conn.prepareStatement(
      s"""
         |CREATE INDEX IF NOT EXISTS "${tableName}_${col}_tree"
         |ON "$tableName"
         |($col)
      """.stripMargin).executeUpdate()
  }

  case class PrimaryKey(attr: TableAttribute)
  case class ForeignKey(attr: TableAttribute, ref: TableAttribute)

  def compoundKeysFrom(primaryKeys: Set[PrimaryKey], foreignKeys: Set[ForeignKey]): Set[TableAttribute] = primaryKeys.collect {
    case pk if pk.attr.isInstanceOf[TableAttributeCompound] => pk.attr
  } union foreignKeys.collect {
    case fk if fk.attr.isInstanceOf[TableAttributeCompound] => fk.attr
  }

}

class DexBuilder(session: SparkSession) extends Serializable with Logging {
  // take a csv file and load it into dataframe
  // take a dataframe, encrypt it, and load it into remote postgres.  Store keys into sessionCatalog.
  import session.sqlContext.implicits._

  //private val encDbUrl = SQLConf.get.dexEncryptedDataSourceUrl
  private val encDbUrl = "jdbc:postgresql://localhost:8433/test_edb"
  private val encDbProps = {
    val p = new Properties()
    p.setProperty("Driver", "org.postgresql.Driver")
    p
  }

  private def shuffle(df: DataFrame): DataFrame = df.sample(1)

  private val udfCell = udf(dexCellOf _)

  private val udfMasterTrapdoorSingletonForPred = udf { dexPredicate: String =>
    // dexTrapdoor(DexPrimitives.masterSecret.hmacKey.getEncoded, dexPredicate)
    dexMasterTrapdoorForPred(dexPredicate, None)
  }

  private val udfSecondaryTrapdoorSingletonForRid = udf { (masterTrapdoor: Array[Byte], rid: Long) =>
    // dexTrapdoor(masterTrapdoor, rid)
    dexSecondaryTrapdoorForRid(masterTrapdoor, rid, None)
  }

  private val udfMasterTrapdoorForPred = udf { (dexPredicate: String, j: Int) =>
    // dexTrapdoor(DexPrimitives.masterSecret.hmacKey.getEncoded, dexPredicate, j)
    dexMasterTrapdoorForPred(dexPredicate, Some(j))
  }

  private val udfSecondaryTrapdoorForRid = udf { (masterTrapdoor: Array[Byte], rid: Long, j: Int) =>
    // dexTrapdoor(masterTrapdoor, rid, j)
    dexSecondaryTrapdoorForRid(masterTrapdoor, rid, Some(j))
  }

  private val udfEmmLabel = udf { (trapdoor: Array[Byte], counter: Int) =>
    dexEmmLabelOf(trapdoor, counter)
  }
  private val udfEmmValue = udf { (trapdoor: Array[Byte], rid: Long) =>
    dexEmmValueOf(trapdoor, rid)
  }

  private val udfRid = udf { rid: Long =>
    dexRidOf(rid)
  }

  private def pibasCounterOn(c: Column): Column = row_number().over(Window.partitionBy(c).orderBy(c)) - 1

  private def primaryKeyAndForeignKeysFor(t: TableName, primaryKeys: Set[PrimaryKey], foreignKeys: Set[ForeignKey]): (PrimaryKey, Set[ForeignKey]) = {
    val pk = {
      val pks = primaryKeys.filter(_.attr.table == t)
      require(pks.size == 1, s"$t doesn't have a primary key: every table should have a primary key, atom or compound")
      pks.headOption.get
    }
    val fks = foreignKeys.filter(_.attr.table == t)
    (pk, fks)
  }

  private def compoundKeyCol(c: TableAttributeCompound): Column = {
    /* doesn't work: 2 * col1 + 3 * col2 = 12 for (0,4) and (6,0), but these two should be mapped to different values.
    Unless we do 2 ^ col1 + 3 ^ col2 by unique factorization theorem, but then this number of huge
    def sieve(s: Stream[Int]): Stream[Int] = {
      s.head #:: sieve(s.tail.filter(_ % s.head != 0))
    }*/
    //val primes = sieve(Stream.from(2))
    //val uniquePrimeFactorization = c.attrs.map(col).zip(primes).map(x => x._1 * x._2).reduce((x, y) => x + y)
    //uniquePrimeFactorization
    // Use '&' to distinguish 1 || 12 collision with 11 || 2
    //concat(c.attrs.flatMap(x => Seq(col(x), lit("_and_"))).dropRight(1): _*)
    def cantorPairing(a: Column, b: Column): Column = {
      // https://en.wikipedia.org/wiki/Pairing_function
      (a + b) * (a + b + 1) / 2 + b
    }
    require(c.attrs.size == 2, "only length-2 compound key is supported")
    val cols = c.attrs.map(col)
    cantorPairing(cols.head, cols(1)).cast(LongType) // unique identifier for ordered pair of numbers
  }

  private def pkCol(pk: PrimaryKey): Column = pk.attr match {
    case a: TableAttributeAtom => col(a.attr)
    case c: TableAttributeCompound => compoundKeyCol(c)
  }

  private def longRidCol(pk: PrimaryKey): Column = pkCol(pk)

  private def bytesRidCol(pk: PrimaryKey): Column = udfRid(pkCol(pk))

  private def encColName(t: TableName, c: AttrName): String = {
    // s"${c}_prf"
    dexColNameOf(c)
  }

  def buildPkFkSchemeFromData(nameToDf: Map[TableName, DataFrame],
                              primaryKeys: Set[PrimaryKey],
                              foreignKeys: Set[ForeignKey]): Unit = {

    nameToDf.foreach {
      case (t, d) =>
        val (pk, fks) = primaryKeyAndForeignKeysFor(t, primaryKeys, foreignKeys)

        def ridColName(pk: PrimaryKey): String = "rid"
        def pfkColName(fk: ForeignKey): String = DexPrimitives.dexColNameOf(s"pfk_${fk.ref.table}_${fk.attr.table}")
        def fpkColName(fk: ForeignKey): String = DexPrimitives.dexColNameOf(s"fpk_${fk.attr.table}_${fk.ref.table}")
        def valColName(t: TableName, c: AttrName): String = DexPrimitives.dexColNameOf(s"val_${t}_${c}")
        def depValColName(t: TableName, c: AttrName): String = DexPrimitives.dexColNameOf(s"depval_${t}_${c}")

        /*def outputCols(d: DataFrame, pk: PrimaryKey, fks: Set[ForeignKey]): Seq[AttrName] = d.columns.flatMap {
          case c if c == pk.attr.attr =>
            Seq(pkColName(pk))
          case c if fks.map(_.attr.attr).contains(c) =>
            val fk = fks.find(_.attr.attr == c).get
            Seq(pfkColName(fk), fpkColName(fk))
          case c =>
            Seq(valColName(t, c), encColName(t, c))
        }*/

        def encIndexColNamesOf(d: DataFrame, pk: PrimaryKey, fks: Set[ForeignKey]): Seq[AttrName] = {
          Seq(ridColName(pk)) ++
            fks.flatMap(fk => Seq(pfkColName(fk), fpkColName(fk))) ++
            d.columns.collect {
              case c if nonKey(pk, fks, c) => valColName(t, c)
            } ++ d.columns.collect {
              case c if nonKey(pk, fks, c) => depValColName(t, c)
            }
        }
        def encDataColNamesOf(t: TableName, d: DataFrame, pk: PrimaryKey, fks: Set[ForeignKey]): Seq[AttrName] = d.columns.collect {
          case c if nonKey(pk, fks, c) =>
            encColName(t, c)
        }

        //  p.p1 (=f.f1) <- f.p1
        //  p.p1 (=f.f1) <- f.p2
        //  p.p2 (=f.f2) <- f.p3
        // one-to-many join (think in terms of tables, i.e. pfkCol means primary table join foreign table)
        def pfkCol(fk: ForeignKey): Column = {
          /*def labelOf(c: Column): Column = concat(
            lit(fk.ref.table), lit("~"), lit(fk.attr.table), lit("~"), c, lit("~"),
            pibasCounterOn(c)
          )*/
          def labelOf(fkCol: Column): Column = {
            val joinPred = dexPkfkJoinPredicatePrefixOf(fk.ref.table, fk.attr.table)
            // val masterTrapdoor = dexTrapdoor(DexPrimitives.masterSecret.hmacKey.getEncoded, joinPred)
            val masterTrapdoor = dexMasterTrapdoorForPred(joinPred, None)
            // assume rid is c
            val secondaryTrapdoor = udfSecondaryTrapdoorSingletonForRid(lit(masterTrapdoor), fkCol)
            udfEmmLabel(secondaryTrapdoor, pibasCounterOn(fkCol))
          }

          fk.attr match {
            case a: TableAttributeAtom => labelOf(col(a.attr))
            case c: TableAttributeCompound => labelOf(compoundKeyCol(c))
          }
        }

        // f.p1 -> p.p1 (=f.f1)
        // f.p2 -> p.p1 (=f.f1)
        // f.p3 -> p.p2 (=f.f2)
        // many-to-one join (think in terms of tables, i.e. fpkCol means foreign table join primary table)
        def fpkCol(fk: ForeignKey, longRidCol: Column): Column = {
          // This longRidCol is of the table that fk resides in.
          /*def labelOf(c: Column): Column = concat(
            c, lit("_enc_"),
            lit(fk.attr.table), lit("~"), lit(fk.ref.table), lit("~"),
            col(pkColName(pk))
          )*/
          def valueOf(fkCol: Column): Column = {
            val joinPred = dexPkfkJoinPredicatePrefixOf(fk.attr.table, fk.ref.table)
            // val masterTrapdoor = dexTrapdoor(DexPrimitives.masterSecret.hmacKey.getEncoded, joinPred)
            val masterTrapdoor = dexMasterTrapdoorForPred(joinPred, None)
            val secondaryTrapdoor = udfSecondaryTrapdoorSingletonForRid(lit(masterTrapdoor), longRidCol)
            udfEmmValue(secondaryTrapdoor, fkCol)
          }
          fk.attr match {
            case a: TableAttributeAtom => valueOf(col(a.attr))
            case c: TableAttributeCompound => valueOf(compoundKeyCol(c))
          }
        }
        def valCol(t: TableName, c: AttrName): Column = {
          /*concat(
            lit(t), lit("~"), lit(c), lit("~"), col(c), lit("~"),
            pibasCounterOn(col(c))
          )*/
          val udfFilterPredicate = udf(dexFilterPredicate(dexFilterPredicatePrefixOf(t, c)) _)
          val trapdoor = udfMasterTrapdoorSingletonForPred(udfFilterPredicate(col(c)))
          udfEmmLabel(trapdoor, pibasCounterOn(col(c)))
        }
        def depValCol(t: TableName, c: AttrName, ridCol: AttrName): Column = {
          // prf(prf(t, c, val), rid)
          val udfFilterPredicate = udf(dexFilterPredicate(dexFilterPredicatePrefixOf(t, c)) _)
          val masterTrapdoor = udfMasterTrapdoorSingletonForPred(udfFilterPredicate(col(c)))
          udfSecondaryTrapdoorSingletonForRid(masterTrapdoor, col(ridCol))
        }
        def encCol(t: TableName, c: AttrName): Column = {
          // concat(col(c), lit("_enc"))
          udfCell(col(c))
        }

        // Of this table:
        // (1) create rid column based on primary key (WARNING: in long, for convenicnece in next step)
        // (2) create join indices columns based on foreign keys (interface with long rids (bad), but eventually converted to bytes. fixme)
        // (3) create filter indices columns based on nonkey columns
        // (4) encrypt nonkey columns
        // (5) convert long rid to bytes rid
        val ridPkDf = shuffle(d).withColumn(ridColName(pk), longRidCol(pk)) // create rid column of type long
        val pkfkDf = fks.foldLeft(ridPkDf) { case (pd, fk) =>
          pd.withColumn(pfkColName(fk), pfkCol(fk))
            .withColumn(fpkColName(fk), fpkCol(fk, col(ridColName(pk))))
        }

        val pkfkEncDf = d.columns.filterNot(
          c => c == pk.attr.attr || fks.map(_.attr.attr).contains(c)
        ).foldLeft(pkfkDf) {
          case (pd, c) =>
            pd.withColumn(valColName(t, c), valCol(t, c))
              .withColumn(depValColName(t, c), depValCol(t, c, ridColName(pk)))
              .withColumn(encColName(t, c), encCol(t, c))
        }

        val ridPkfkEncDf = pkfkEncDf.withColumn(ridColName(pk), bytesRidCol(pk))

        def encTableNameOf(t: TableName): String =  {
           // s"${t}_prf"
          dexTableNameOf(t)
        }

        val encTableName = encTableNameOf(t)
        val encIndexColNames = encIndexColNamesOf(d, pk, fks)
        val encDataColNames = encDataColNamesOf(t, d, pk, fks)
        ridPkfkEncDf.selectExpr(encIndexColNames ++ encDataColNames:_*)
          .write.mode(SaveMode.Overwrite).jdbc(encDbUrl, encTableName, encDbProps)

        Utils.classForName("org.postgresql.Driver")
        val encConn = DriverManager.getConnection(encDbUrl, encDbProps)
        try {
          encIndexColNames.foreach { c =>
            createTreeIndex(encConn, encTableName, c)
          }
          //encConn.prepareStatement(s"analyze $encTableName").execute()
        } finally {
          encConn.close()
        }
    }

    Utils.classForName("org.postgresql.Driver")
    val encConn = DriverManager.getConnection(encDbUrl, encDbProps)
    try {
      encConn.prepareStatement(s"vacuum").execute()
      encConn.prepareStatement(s"analyze").execute()
    } finally {
      encConn.close()
    }
  }

  private def nonKey(pk: PrimaryKey, fks: Set[ForeignKey], c: String): Boolean = {
    val nonPk = pk match {
      case PrimaryKey(attr: TableAttributeAtom) => c != attr.attr
      case PrimaryKey(attr: TableAttributeCompound) => c != attr.attr && !attr.attrs.contains(c)
    }
    val nonFk = fks.forall {
      case ForeignKey(attr: TableAttributeAtom, attrRef: TableAttributeAtom) =>
        c != attr.attr && c != attrRef.attr
      case ForeignKey(attr: TableAttributeCompound, attrRef: TableAttributeCompound) =>
        c != attr.attr && !attr.attrs.contains(c) && c != attrRef.attr && !attrRef.attrs.contains(c)
      case _ => throw DexException("unsupported")
    }
    nonPk && nonFk
  }

  def buildFromData(dexStandaloneVariant: DexStandalone, nameToDf: Map[TableName, DataFrame], primaryKeys: Set[PrimaryKey], foreignKeys: Set[ForeignKey]): Unit = {
    val nameToRidDf = nameToDf.map { case (n, d) =>
      val ridDf = shuffle(d).withColumn(DexConstants.ridCol, monotonically_increasing_id()).cache()
      val (pk, fks) = primaryKeyAndForeignKeysFor(n, primaryKeys, foreignKeys)
      val ridPkDf = pk.attr match {
        case c: TableAttributeCompound =>
          ridDf.withColumn(c.attr, compoundKeyCol(c))
        case a: TableAttributeAtom =>
          ridDf
      }
      val ridPkFkDf = fks.foldLeft(ridPkDf) {
        case (df, fk) => fk.attr match {
          case c: TableAttributeCompound =>
            df.withColumn(c.attr, compoundKeyCol(c))
          case a: TableAttributeAtom =>
            df
        }
      }
      n -> ridPkFkDf
    }

    def buildEncRidTables(): Unit = {
      val encNameToEncDf = nameToRidDf.map { case (n, r) =>
        encryptTable(n, r)
      }
      encNameToEncDf.foreach { case (n, e) =>
        e.write.mode(SaveMode.Overwrite).jdbc(encDbUrl, n, encDbProps)
        buildIndexFor(n, DexConstants.ridCol)
      }
    }


    def buildTFilter(): Unit = {
      val tFilterDfParts = nameToRidDf.flatMap { case (n, r) =>
        val (pk, fks) = primaryKeyAndForeignKeysFor(n, primaryKeys, foreignKeys)
        r.columns.filter(c => nonKey(pk, fks, c) && c != DexConstants.ridCol).map { c =>
          val udfFilterPredicate = udf(dexFilterPredicate(dexFilterPredicatePrefixOf(n, c)) _)
          r.withColumn("counter", row_number().over(Window.partitionBy(c).orderBy(c)) - 1).repartition(col(c))
            .withColumn("predicate", udfFilterPredicate(col(c)))
            .withColumn("master_trapdoor_1", udfMasterTrapdoorForPred($"predicate", lit(1)))
            .withColumn("master_trapdoor_2", udfMasterTrapdoorForPred($"predicate", lit(2)))
            .withColumn("label", udfEmmLabel($"master_trapdoor_1", $"counter"))
            .withColumn("value", udfEmmValue($"master_trapdoor_2", $"rid"))
            .select("label", "value")
        }
      }
      val tFilterDf = shuffle(tFilterDfParts.reduce((d1, d2) => d1 union d2))
      tFilterDf.write.mode(SaveMode.Overwrite).jdbc(encDbUrl, DexConstants.tFilterName, encDbProps)
      buildIndexFor(DexConstants.tFilterName, DexConstants.emmLabelCol)
    }

    def buildTDependentFilter(): Unit = {
      val tDepFilterDfParts = nameToRidDf.flatMap { case (n, r) =>
        val (pk, fks) = primaryKeyAndForeignKeysFor(n, primaryKeys, foreignKeys)
        r.columns.filter(c => nonKey(pk, fks, c) && c != DexConstants.ridCol).map { c =>
          val udfFilterPredicate = udf(dexFilterPredicate(dexFilterPredicatePrefixOf(n, c)) _)
          r.withColumn("predicate", udfFilterPredicate(col(c)))
            .withColumn("master_trapdoor", udfMasterTrapdoorSingletonForPred($"predicate"))
            .withColumn(DexConstants.tDepFilterCol, udfSecondaryTrapdoorSingletonForRid($"master_trapdoor", col(DexConstants.ridCol)))
            .select(DexConstants.tDepFilterCol)
        }
      }
      val tDepFilterDf = shuffle(tDepFilterDfParts.reduce((d1, d2) => d1 union d2))
      tDepFilterDf.write.mode(SaveMode.Overwrite).jdbc(encDbUrl, DexConstants.tDepFilterName, encDbProps)
      buildIndexFor(DexConstants.tDepFilterName, DexConstants.tDepFilterCol)
    }

    /*val tDomainDfParts = nameToRidDf.flatMap { case (n, r) =>
      r.columns.filterNot(_ == "rid").map { c =>
        r.select(col(c)).distinct()
          .withColumn("counter", row_number().over(Window.orderBy(monotonically_increasing_id())) - 1).repartition(col(c))
          .withColumn("predicate", lit(domainPredicateOf(n, c)))
          .withColumn("label", udfLabel($"predicate", $"counter"))
          .withColumn("value", udfValue(col(c)))
          .select("label", "value").repartition($"label")
      }
    }
    val tDomainDf = tDomainDfParts.reduce((d1, d2) => d1 union d2)
    tDomainDf.write.mode(SaveMode.Overwrite).jdbc(encDbUrl, DexConstants.tDomainName, encDbProps)
    buildIndexFor(Seq(DexConstants.tDomainName))
    */


    def buildTUncorrelatedJoin(): Unit = {
      val tUncorrJoinDfParts = for {
        ForeignKey(attr, attrRef) <- foreignKeys
        (attrLeft, attrRight) = if (attr.qualifiedName <= attrRef.qualifiedName) (attr, attrRef) else (attrRef, attr)
      } yield {
        val (dfLeft, dfRight) = (nameToRidDf(attrLeft.table), nameToRidDf(attrRight.table))
        val uncorrJoinPredicate = dexUncorrJoinPredicateOf(attrLeft, attrRight)
        val masterTrapdoors = (
          dexTrapdoorForPred(DexPrimitives.masterSecret.hmacKey.getEncoded, uncorrJoinPredicate, 1),
          dexTrapdoorForPred(DexPrimitives.masterSecret.hmacKey.getEncoded, uncorrJoinPredicate, 2))
        dfLeft.withColumnRenamed("rid", "rid_left")
          .join(dfRight.withColumnRenamed("rid", "rid_right"), col(attrLeft.attr) === col(attrRight.attr))
          .withColumn("counter", row_number().over(Window.orderBy(monotonically_increasing_id())) - 1).repartition($"counter")
          .withColumn("label", udfEmmLabel(lit(masterTrapdoors._1), $"counter"))
          .withColumn("value_left", udfEmmValue(lit(masterTrapdoors._2), $"rid_left"))
          .withColumn("value_right", udfEmmValue(lit(masterTrapdoors._2), $"rid_right"))
          .select("label", "value_left", "value_right")
      }
      val tUncorrJoinDf = shuffle(tUncorrJoinDfParts.reduce((d1, d2) => d1 union d2))
      tUncorrJoinDf.write.mode(SaveMode.Overwrite).jdbc(encDbUrl, DexConstants.tUncorrJoinName, encDbProps)
      buildIndexFor(DexConstants.tUncorrJoinName, DexConstants.emmLabelCol)
    }

    def buildTCorrelatedJoin(): Unit = {
      val tCorrJoinDfParts = for {
        ForeignKey(attr, attrRef) <- foreignKeys
      } yield {
        def joinFor(attrLeft: TableAttribute, attrRight: TableAttribute): DataFrame = {
          //val udfJoinPred = udf(dexPredicatesConcat(dexCorrJoinPredicatePrefixOf(attrLeft, attrRight)) _)
          val joinPred = dexCorrJoinPredicatePrefixOf(attrLeft, attrRight)
          val masterTrapdoor = dexTrapdoorForPred(DexPrimitives.masterSecret.hmacKey.getEncoded, joinPred)
          val (dfLeft, dfRight) = (nameToRidDf(attrLeft.table), nameToRidDf(attrRight.table))
          require(dfLeft.columns.contains(attrLeft.attr) && dfRight.columns.contains(attrRight.attr), s"${attrLeft.attr} and ${attrRight.attr}")
          dfLeft.withColumnRenamed("rid", "rid_left").join(dfRight.withColumnRenamed("rid", "rid_right"), col(attrLeft.attr) === col(attrRight.attr))
            //.groupBy("rid_left").agg(collect_list($"rid_right").as("rids_right"))
            //.select($"rid_left", posexplode($"rids_right").as("counter" :: "rid_right" :: Nil))
            .withColumn("counter", row_number().over(Window.partitionBy("rid_left").orderBy("rid_left")) - 1).repartition($"counter")
            .withColumn("secondary_trapdoor_1", udfSecondaryTrapdoorForRid(lit(masterTrapdoor), $"rid_left", lit(1)))
            .withColumn("secondary_trapdoor_2", udfSecondaryTrapdoorForRid(lit(masterTrapdoor), $"rid_left", lit(2)))
            .withColumn("label", udfEmmLabel($"secondary_trapdoor_1", $"counter"))
            .withColumn("value", udfEmmValue($"secondary_trapdoor_2", $"rid_right"))
            .select("label", "value")
        }
        joinFor(attr, attrRef) union joinFor(attrRef, attr)
      }
      val tCorrJoinDf = shuffle(tCorrJoinDfParts.reduce((d1, d2) => d1 union d2))
      tCorrJoinDf.write.mode(SaveMode.Overwrite).jdbc(encDbUrl, DexConstants.tCorrJoinName, encDbProps)
      buildIndexFor(DexConstants.tCorrJoinName, DexConstants.emmLabelCol)
    }

    def buildIndexFor(encName: String, col: String): Unit = {
      Utils.classForName("org.postgresql.Driver")
      val encConn = DriverManager.getConnection(encDbUrl, encDbProps)
      try {
          createHashIndex(encConn, encName, col)
      } finally {
        encConn.close()
      }
    }

    def analyzeAll(): Unit = {
      val encConn = DriverManager.getConnection(encDbUrl, encDbProps)
      try {
        encConn.prepareStatement("vacuum").execute()
        encConn.prepareStatement("analyze").execute()
      } finally {
        encConn.close()
      }
    }

    dexStandaloneVariant match {
      case DexSpx =>
        buildEncRidTables()
        buildTFilter()
        buildTUncorrelatedJoin()
        analyzeAll()
      case DexCorr =>
        buildEncRidTables()
        buildTFilter()
        buildTDependentFilter()
        buildTCorrelatedJoin()
        analyzeAll()
      case x => throw DexException("unsupported: " + x.getClass.toString)
    }
  }

  private def encryptTable(table: TableName, ridDf: DataFrame): (String, DataFrame) = {
    require(ridDf.columns.contains("rid"))
    val colToRandCol = ridDf.columns.collect {
      case c if c == "rid" => "rid" -> "rid"
      case c => c -> dexColNameOf(c)
    }.toMap
    val ridDfProject = colToRandCol.values.map(col).toSeq

    (dexTableNameOf(table),
      ridDf.columns.foldLeft(ridDf) {
        case (d, c) if c == "rid" =>
          d.withColumn(colToRandCol(c), udfRid(col(c)))
        case (d, c) =>
          d.withColumn(colToRandCol(c), udfCell(col(c)))
      }.select(ridDfProject: _*))
  }
}
