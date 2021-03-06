package sparkplug

import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import sparkplug.models.{PlugDetail, PlugRule, PlugRuleValidationError}
import sparkplug.udfs.SparkPlugUDFs

import scala.util.Try

case class SparkPlug(
    isPlugDetailsEnabled: Boolean,
    isValidateRulesEnabled: Boolean)(implicit val spark: SparkSession) {

  private val tableName = "__plug_table__"

  def plug(in: DataFrame, rules: List[PlugRule])
    : Either[List[PlugRuleValidationError], DataFrame] = {
    val validationResult = validateRules(in, rules)
    if (validationResult.nonEmpty) {
      Left(validationResult)
    } else {
      registerUdf(spark)
      val rulesBroadcast = spark.sparkContext.broadcast(rules)
      val preProcessedInput = preProcessInput(in)

      val pluggedDf = rulesBroadcast.value.foldLeft(preProcessedInput) {
        case (df: DataFrame, rule: PlugRule) =>
          val output = applyRule(df, rule)

          rule.withColumnsRenamed(output)
      }

      Right(pluggedDf)
    }
  }

  private def validateRules(in: DataFrame, rules: List[PlugRule]) = {
    if (isValidateRulesEnabled) {
      Option(rules.flatMap(_.validate(in.schema)))
        .filter(_.nonEmpty)
        .getOrElse(rules.flatMap(r => validateRuleSql(in.schema, r)))
    } else
      List.empty
  }

  private def validateRuleSql(schema: StructType, rule: PlugRule) = {
    Try(applyRule(emptyDf(schema), rule)).failed
      .map { t =>
        List(
          PlugRuleValidationError(rule.name, s"[SQL Error] ${t.getMessage}"))
      }
      .getOrElse(List())
  }

  private def emptyDf(schema: StructType) = {
    spark.createDataFrame(spark.sparkContext.emptyRDD[Row], schema)
  }

  private def preProcessInput(in: DataFrame) = {
    if (isPlugDetailsEnabled && !in.schema.fields.exists(
          _.name == PlugRule.plugDetailsColumn)) {
      val emptyOverrideDetails = udf(() => Seq[PlugDetail]())
      in.withColumn(PlugRule.plugDetailsColumn, emptyOverrideDetails())
    } else {
      in
    }
  }

  private def registerUdf(spark: SparkSession) = {
    if (isPlugDetailsEnabled) {
      SparkPlugUDFs.registerUDFs(spark.sqlContext)
    }
  }

  private def applyRule(frame: DataFrame, rule: PlugRule) = {
    applySql(
      frame,
      s"select *,${rule.asSql(frame.schema, isPlugDetailsEnabled)} from $tableName")
  }

  private def applySql(in: DataFrame, sql: String): DataFrame = {
    in.createOrReplaceTempView(tableName)
    in.sqlContext.sql(sql)
  }

}

case class SparkPlugBuilder(isPlugDetailsEnabled: Boolean = false,
                            isValidateRulesEnabled: Boolean = false)(
    implicit val spark: SparkSession) {
  def enablePlugDetails = copy(isPlugDetailsEnabled = true)
  def enableRulesValidation = copy(isValidateRulesEnabled = true)

  def create() = new SparkPlug(isPlugDetailsEnabled, isValidateRulesEnabled)
}

object SparkPlug {
  def builder(implicit spark: SparkSession) = SparkPlugBuilder()
}
