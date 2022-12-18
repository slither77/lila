package lila.game

import akka.stream.scaladsl.*
import akka.util.ByteString
import chess.format.{ Fen, Uci }
import chess.{ Centis, Color, Game as ChessGame, Replay, Situation }
import chess.variant.Variant
import play.api.libs.json.*
import play.api.libs.ws.JsonBodyWritables.*
import play.api.libs.ws.{ StandaloneWSClient, StandaloneWSResponse }
import scala.util.chaining.*

import lila.common.config.BaseUrl
import lila.common.Json.given
import lila.common.Maths

object GifExport:
  case class UpstreamStatus(code: Int) extends lila.base.LilaException:
    val message = s"gif service status: $code"

final class GifExport(
    ws: StandaloneWSClient,
    lightUserApi: lila.user.LightUserApi,
    baseUrl: BaseUrl,
    url: String
)(using scala.concurrent.ExecutionContext):
  private val targetMedianTime = Centis(80)
  private val targetMaxTime    = Centis(200)

  def fromPov(
      pov: Pov,
      initialFen: Option[Fen.Epd],
      theme: String,
      piece: String
  ): Fu[Source[ByteString, ?]] =
    lightUserApi.preloadMany(pov.game.userIds) >>
      ws.url(s"$url/game.gif")
        .withMethod("POST")
        .addHttpHeaders("Content-Type" -> "application/json")
        .withBody(
          Json.obj(
            "white" -> Namer.playerTextBlocking(pov.game.whitePlayer, withRating = true)(using
              lightUserApi.sync
            ),
            "black" -> Namer.playerTextBlocking(pov.game.blackPlayer, withRating = true)(using
              lightUserApi.sync
            ),
            "comment" -> s"${baseUrl.value}/${pov.game.id} rendered with https://github.com/lichess-org/lila-gif",
            "orientation" -> pov.color.name,
            "delay"       -> targetMedianTime.centis, // default delay for frames
            "frames"      -> frames(pov.game, initialFen),
            "theme"       -> theme,
            "piece"       -> piece
          )
        )
        .stream() pipe upstreamResponse(s"pov ${pov.game.id}")

  def gameThumbnail(game: Game, theme: String, piece: String): Fu[Source[ByteString, ?]] =
    val query = List(
      "fen"         -> (Fen write game.chess).value,
      "white"       -> Namer.playerTextBlocking(game.whitePlayer, withRating = true)(using lightUserApi.sync),
      "black"       -> Namer.playerTextBlocking(game.blackPlayer, withRating = true)(using lightUserApi.sync),
      "orientation" -> game.naturalOrientation.name
    ) ::: List(
      game.lastMoveKeys.map { "lastMove" -> _ },
      game.situation.checkSquare.map { "check" -> _.key },
      some("theme" -> theme),
      some("piece" -> piece)
    ).flatten
    lightUserApi.preloadMany(game.userIds) >>
      ws.url(s"$url/image.gif")
        .withMethod("GET")
        .withQueryStringParameters(query*)
        .stream() pipe upstreamResponse(s"gameThumbnail ${game.id}")

  def thumbnail(
      fen: Fen.Epd,
      lastMove: Option[String],
      orientation: Color,
      variant: Variant,
      theme: String,
      piece: String
  ): Fu[Source[ByteString, ?]] =
    val query = List(
      "fen"         -> fen.value,
      "orientation" -> orientation.name
    ) ::: List(
      lastMove.map { "lastMove" -> _ },
      Fen.read(variant, fen).flatMap(_.checkSquare.map { "check" -> _.key }),
      some("theme" -> theme),
      some("piece" -> piece)
    ).flatten
    ws.url(s"$url/image.gif")
      .withMethod("GET")
      .withQueryStringParameters(query*)
      .stream() pipe upstreamResponse(s"thumbnail $fen")

  private def upstreamResponse(name: String)(res: Fu[StandaloneWSResponse]): Fu[Source[ByteString, ?]] =
    res flatMap {
      case res if res.status != 200 =>
        logger.warn(s"GifExport $name ${res.status}")
        fufail(GifExport.UpstreamStatus(res.status))
      case res => fuccess(res.bodyAsSource)
    }

  private def scaleMoveTimes(moveTimes: Vector[Centis]): Vector[Centis] =
    // goal for bullet: close to real-time
    // goal for classical: speed up to reach target median, avoid extremely
    // fast moves, unless they were actually played instantly
    Maths.median(moveTimes.map(_.centis)).map(Centis.ofDouble(_)).filter(_ >= targetMedianTime) match
      case Some(median) =>
        val scale = targetMedianTime.centis.toFloat / median.centis.atLeast(1).toFloat
        moveTimes.map { t =>
          if (t * 2 < median) t atMost (targetMedianTime *~ 0.5)
          else t *~ scale atLeast (targetMedianTime *~ 0.5) atMost targetMaxTime
        }
      case None => moveTimes.map(_ atMost targetMaxTime)

  private def frames(game: Game, initialFen: Option[Fen.Epd]) =
    Replay.gameMoveWhileValid(
      game.pgnMoves,
      initialFen | game.variant.initialFen,
      game.variant
    ) match
      case (init, games, _) =>
        val steps = (init, None) :: (games map { case (g, Uci.WithSan(uci, _)) =>
          (g, uci.some)
        })
        framesRec(
          steps.zip(scaleMoveTimes(~game.moveTimes).map(_.some).padTo(steps.length, None)),
          Json.arr()
        )

  @annotation.tailrec
  private def framesRec(games: List[((ChessGame, Option[Uci]), Option[Centis])], arr: JsArray): JsArray =
    games match
      case Nil =>
        arr
      case ((game, uci), scaledMoveTime) :: tail =>
        // longer delay for last frame
        val delay = if (tail.isEmpty) Centis(500).some else scaledMoveTime
        framesRec(tail, arr :+ frame(game.situation, uci, delay))

  private def frame(situation: Situation, uci: Option[Uci], delay: Option[Centis]) =
    Json
      .obj(
        "fen"      -> (Fen write situation),
        "lastMove" -> uci.map(_.uci)
      )
      .add("check", situation.checkSquare.map(_.key))
      .add("delay", delay.map(_.centis))
