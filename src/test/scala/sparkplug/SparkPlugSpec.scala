package sparkplug

import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.scalatest._
import sparkplug.models.{PlugAction, PlugDetail, PlugRule, PlugRuleValidationError}

case class TestRow(title: String, brand: String, price: Int)
case class TestRowWithPlugDetails(title: String, brand: String, price: Int, plugDetails: Seq[PlugDetail] = Seq())

class SparkPlugSpec extends FlatSpec with Matchers {
  implicit val spark: SparkSession = SparkSession.builder
    .config(new SparkConf())
    .enableHiveSupport()
    .master("local[*]")
    .getOrCreate()

  "SparkPlug" should "return input df as is" in {
    val df = spark.emptyDataFrame
    val sparkPlug = SparkPlug.builder.create()
    sparkPlug.plug(df, List.empty).right.get should be (df)
  }

  it should "add plug details to the df if enabled" in {
    val df = spark.emptyDataFrame
    val sparkPlug = SparkPlug.builder.enablePlugDetails.create()
    sparkPlug.plug(df, List.empty).right.get.schema.fieldNames should contain("plugDetails")
  }


  it should "validate rules if enabled" in {
    val df = spark.createDataFrame(List(
      TestRow("iPhone", "Apple", 300),
      TestRow("Galaxy", "Samsung", 200)
    ))
    val sparkPlug = SparkPlug.builder.enableRulesValidation.create()
    val invalidRules = List(
      PlugRule("rule1", "title like '%iPhone%'", Seq(PlugAction("randomField", "1"))),
      PlugRule("rule2", "title like '%iPhone%'", Seq(PlugAction("price", "too high")))
    )
    sparkPlug.plug(df, invalidRules).left.get should be (List(
      PlugRuleValidationError("rule1", "Field randomField not found in the schema."),
      PlugRuleValidationError("rule2", "Value \"too high\" cannot be assigned to field price.")
    ))
  }

  it should "apply rules" in {
    val df = spark.createDataFrame(List(
      TestRow("iPhone", "Apple", 300),
      TestRow("Galaxy", "Samsung", 200)
    ))
    val sparkPlug = SparkPlug.builder.create()
    val rules = List(
      PlugRule("rule1", "title like '%iPhone%'", Seq(PlugAction("price", "1000"))),
      PlugRule("rule2", "title like '%Galaxy%'", Seq(PlugAction("price", "700")))
    )

    import spark.implicits._
    val output = sparkPlug.plug(df, rules).right.get.as[TestRow].collect()
    output.length should be (2)
    output(0).price should be(1000)
    output(1).price should be(700)
  }

  it should "apply rules with plug details" in {
    val df = spark.createDataFrame(List(
      TestRowWithPlugDetails("iPhone", "Apple", 300),
      TestRowWithPlugDetails("Galaxy", "Samsung", 200)
    ))
    val sparkPlug = SparkPlug.builder.enablePlugDetails.create()
    val rules = List(
      PlugRule("rule1", "title like '%iPhone%'", Seq(PlugAction("price", "1000"))),
      PlugRule("rule2", "title like '%Galaxy%'", Seq(PlugAction("price", "700")))
    )

    import spark.implicits._
    val output = sparkPlug.plug(df, rules).right.get.as[TestRowWithPlugDetails].collect()
    output.length should be (2)
    output(0).price should be(1000)
    output(0).plugDetails should be(Seq(PlugDetail("rule1", Seq("price"))))

    output(1).price should be(700)
    output(1).plugDetails should be(Seq(PlugDetail("rule2", Seq("price"))))
  }

  it should "apply rules with plug details even if not in input" in {
    val df = spark.createDataFrame(List(
      TestRow("iPhone", "Apple", 300),
      TestRow("Galaxy", "Samsung", 200)
    ))
    val sparkPlug = SparkPlug.builder.enablePlugDetails.create()
    val rules = List(
      PlugRule("rule1", "title like '%iPhone%'", Seq(PlugAction("price", "1000"))),
      PlugRule("rule2", "title like '%Galaxy%'", Seq(PlugAction("price", "700")))
    )

    import spark.implicits._
    val output = sparkPlug.plug(df, rules).right.get.as[TestRowWithPlugDetails].collect()
    output.length should be (2)
    output(0).price should be(1000)
    output(0).plugDetails should be(Seq(PlugDetail("rule1", Seq("price"))))

    output(1).price should be(700)
    output(1).plugDetails should be(Seq(PlugDetail("rule2", Seq("price"))))
  }
}
