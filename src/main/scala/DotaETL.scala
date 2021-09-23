import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions.WindowSpec
import org.apache.spark.sql.expressions.Window
import scalaj.http._

import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`


object DotaETL extends App {

  val spark = SparkSession.builder()
    .master("local[1]")
    .appName("DotaETL")
    .getOrCreate()
  spark.sparkContext.setLogLevel("ERROR")
  import spark.implicits._

  val account_id = 639740
  val number_matches = 5

  def prepParsedDF(df: DataFrame, array_to_parse: String): DataFrame = {
    def parseDFArray(df: DataFrame, array_to_parse: String): DataFrame =
      df.select($"match_id", explode(col(array_to_parse)).alias(array_to_parse))

    def sumTeamKills(df: DataFrame): DataFrame = {
      val w = Window.partitionBy("match_id", "players.isRadiant")
      df.select(
      $"match_id",
        $"players.account_id",
        $"players.isRadiant",
        $"players.kills",
        $"players.assists",
        sum("players.kills").over(w).alias("total_team_kills"))
    }

    def calcKP(df: DataFrame): DataFrame =
      df.select(
        $"match_id",
        $"account_id",
        when($"isRadiant" === true, "Radiant").otherwise("Dire").alias("team"),
        ((($"kills" + $"assists")/$"total_team_kills")*100).alias("KP"))

    calcKP(sumTeamKills(parseDFArray(df, array_to_parse)))
  }

  def getMatchesDF(df: DataFrame, col_to_get: String): DataFrame = {
    def getMatchesList(df: DataFrame, col_to_get: String): List[Long] = {
      val func = (x: Row) => x.getLong(0)
      df.select(col_to_get).map(func).collect.toList
    }

    def getMatchDataFromAPI(match_list: List[Long]): DataFrame = {
      val func = (x: Long) => Http(s"https://api.opendota.com/api/matches/$x").asString.body
      val matchDS = match_list.map(func)toDS()
      spark.read.json(matchDS)
    }

    getMatchDataFromAPI(getMatchesList(df, col_to_get))
  }

  def prepAccountDF(df: DataFrame, number_of_matches: Int): DataFrame =
    df.select(lit(account_id).alias("account_id"),
      $"match_id",
      $"kills",
      $"assists",
      $"deaths",
      ($"kills" + $"assists" / $"deaths").alias("KDA")).limit(number_of_matches)

  def getDataFrameFromAPI(request: HttpRequest): DataFrame = {
    val response = request.asString
    val ds = Seq(response.body).toDS()
    spark.read.json(ds)
  }

  val recentMatchesDF = getDataFrameFromAPI(Http(s"https://api.opendota.com/api/players/$account_id/recentMatches"))
  val accountDF = prepAccountDF(recentMatchesDF, number_matches)

  val nameDF = getDataFrameFromAPI(Http(s"https://api.opendota.com/api/players/$account_id")).select("profile.account_id", "profile.name")
  val playerDF = accountDF.join(nameDF, "account_id")
  val matchDF = getMatchesDF(playerDF, "match_id")

  val parsedDF = prepParsedDF(matchDF, "players")
  parsedDF.show()

  val summaryDF = playerDF.join(parsedDF, Seq("account_id", "match_id"))
  val aggregatedDF = summaryDF.groupBy("name")
    .agg(
      count("match_id").as("total_games"),
      max("KDA").as("max_kda"),
      min("KDA").as("min_kda"),
      avg("KDA").as("avg_kda"),
      max("KP").as("max_kp"),
      min("KP").as("min_kp"),
      avg("KP").as("avg_kp"))

  val formattedDF = aggregatedDF.select(
    $"name".alias("player_name"),
    $"total_games",
    round($"max_kda", 2).alias("max_kda"),
    round($"min_kda", 2).alias("min_kda"),
    round($"avg_kda", 2).alias("avg_kda"),
    concat(round($"max_kp"), lit("%")).alias("max_kp"),
    concat(round($"min_kp"), lit("%")).alias("min_kp"),
    concat(round($"avg_kp"), lit("%")).alias("avg_kp")
  )
  //formattedDF.show()
  //TODO: make it runnable in Dockerfile
  //formattedDF.coalesce(1).write.json("resources/output")

  spark.stop()
  System.exit(0)
}
