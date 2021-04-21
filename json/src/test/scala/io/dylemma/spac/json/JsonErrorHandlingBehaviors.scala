package io.dylemma.spac
package json

import cats.effect.SyncIO
import cats.syntax.apply._
import fs2.Stream
import io.dylemma.spac.json.JsonEvent.{JString, _}
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

trait JsonErrorHandlingBehaviors { self: AnyFunSpec with Matchers =>
	/** Behavior suite that inspect the 'spac trace' of exceptions thrown in a handful of
	  * situations where the parse logic is somehow "wrong".
	  */
	def jsonErrorHandlingParserWithStringSource(stringToJsonStream: String => Stream[SyncIO, JsonEvent])(implicit stringParsable: Parsable[cats.Id, String, JsonEvent]) = {
		case class ParserCase[A](parser: JsonParser[A], rawJson: String) {
			lazy val events = JsonParser.toList.parse(rawJson)
			def eventsItr = events.iterator

			def runParse(): A = parser.parse(rawJson)
			def runParseSeq(): A = parser.parse(events)
			def runParseIterator(): A = parser.parse(fs2.Stream.fromIterator[SyncIO](eventsItr, 1))
			def runParserAsPipe(): A = stringToJsonStream(rawJson).through(parser.toPipe).compile.lastOrError.unsafeRunSync()

			def checkBehaviorsWith(baseBehavior: (String, () => A) => Unit) = {
				describe(".parse(rawSource)") {
					it should behave like baseBehavior("parse", runParse _)
				}
				describe(".parse(eventSequence)") {
					it should behave like baseBehavior("parse", runParseSeq _)
				}
				describe(".parse(eventStream)") {
					it should behave like baseBehavior("parse", runParseIterator _)
				}
				describe(".toPipe") {
					it should behave like baseBehavior("toPipe", runParserAsPipe _)
				}
			}
		}

		describe("control case") {
			val rawJson =
				"""{
				  |  "id": "abc123",
				  |  "data": [
				  |    { "a": 1, "b": true },
				  |    { "a": 2 },
				  |    { "a": 3, "b": false }
				  |  ]
				  |}""".stripMargin

			val parser = (
				JsonParser.fieldOf[String]("id"),
				JsonParser.fieldOf[Any]("data", JsonParser.listOf {
					(JsonParser.fieldOf[Int]("a"), JsonParser.nullableFieldOf[Boolean]("b")).tupled
				})
			).tupled

			ParserCase(parser, rawJson).checkBehaviorsWith { (methodName, doParse) =>
				it("should successfully parse the control input") {
					noException should be thrownBy doParse()
				}
			}
		}

		describe("wrong 'id' case") {
			val rawJson =
				"""{
				  |  "id": "hello",
				  |  "data": [
				  |    { "a": 1, "b": true },
				  |    { "a": 2 },
				  |    { "a": 3, "b": false }
				  |  ]
				  |}""".stripMargin

			val IdLine = new LineNumberCell
			val CompoundLine = new LineNumberCell

			val parser = (
				IdLine & JsonParser.fieldOf[Boolean]("id"),
				JsonParser.fieldOf[Any]("data", JsonParser.listOf {
					(JsonParser.fieldOf[Int]("a"), JsonParser.fieldOf[Boolean]("b")).tupled
				})
			).tupled &: CompoundLine

			ParserCase(parser, rawJson).checkBehaviorsWith { (methodName, doParse) =>
				it ("should include 'spac trace' information for the unexpected input exception") {
					intercept[SpacException.UnexpectedInputException[_]]{ doParse() }.spacTrace.toList should matchPattern {
						case SpacTraceElement.InInput(JString("hello")) ::
							SpacTraceElement.InInputContext(FieldStart("id"), ContextWithLine(2)) ::
							SpacTraceElement.InInputContext(ObjectStart(), ContextWithLine(1)) ::
							SpacTraceElement.InSplitter(_, IdLine()) ::
							SpacTraceElement.InCompound(0, 2, CompoundLine()) ::
							SpacTraceElement.InParse("parser", `methodName`, _) ::
							Nil =>
					}
				}
			}
		}

		describe("missing field case") {
			val rawJson =
				"""{
				  |  "id": 3,
				  |  "data": [
				  |    { "a": 1, "b": true },
				  |    { "a": 2 },
				  |    { "a": 3, "b": false }
				  |  ]
				  |}""".stripMargin

			val InnerCompoundLine = new LineNumberCell
			val CompoundLine = new LineNumberCell
			val BFieldLine = new LineNumberCell
			val DataFieldLine = new LineNumberCell

			val parser = (
				JsonParser.fieldOf[Int]("id"),
				JsonParser.fieldOf[Any]("data", DataFieldLine & JsonParser.listOf {
					(
						JsonParser.fieldOf[Int]("a"),
						JsonParser.fieldOf[Boolean]("b") &: BFieldLine
					).tupled &: InnerCompoundLine
				})
			).tupled &: CompoundLine

			ParserCase(parser, rawJson).checkBehaviorsWith { (methodName, doParse) =>
				it ("should include 'spac trace' information for the unexpected input exception") {
					intercept[SpacException.MissingFirstException[_]]{ doParse() }.spacTrace.toList should matchPattern {
						case SpacTraceElement.InInput(IndexEnd(1)) ::
							SpacTraceElement.InSplitter("b", BFieldLine()) ::
							SpacTraceElement.InCompound(1, 2, InnerCompoundLine()) ::
							SpacTraceElement.InInputContext(IndexStart(1), ContextWithLine(5)) ::
							SpacTraceElement.InInputContext(ArrayStart(), ContextWithLine(3)) ::
							SpacTraceElement.InSplitter("anyIndex", DataFieldLine()) ::
							SpacTraceElement.InInputContext(FieldStart("data"), ContextWithLine(3)) ::
							SpacTraceElement.InInputContext(ObjectStart(), ContextWithLine(1)) ::
							SpacTraceElement.InSplitter("data", DataFieldLine()) ::
							SpacTraceElement.InCompound(1, 2, CompoundLine()) ::
							SpacTraceElement.InParse("parser", `methodName`, _) ::
							Nil =>
					}
				}
			}
		}

		describe("inner parser that throws errors") {
			val rawJson =
				"""{
				  |  "foo": [
				  |    "1",
				  |    "2",
				  |    "hello"
				  |  ]
				  |}""".stripMargin

			val SplitterLine = new LineNumberCell

			val parser = Splitter
				.json(SplitterLine & "foo" \ anyIndex )
				.joinBy(JsonParser[String].map(_.toInt))
				.parseToList

			ParserCase(parser, rawJson).checkBehaviorsWith { (methodName, doParse) =>
				it ("should capture the thrown exception and add 'spac trace' information") {
					val caughtError = intercept[SpacException.CaughtError] { doParse() }
					caughtError.nonSpacCause should matchPattern { case e: NumberFormatException => }
					caughtError.spacTrace.toList should matchPattern {
						case SpacTraceElement.InInput(JString("hello")) ::
							SpacTraceElement.InInputContext(IndexStart(2), ContextWithLine(5)) ::
							SpacTraceElement.InInputContext(ArrayStart(), ContextWithLine(2)) ::
							SpacTraceElement.InInputContext(FieldStart("foo"), ContextWithLine(2)) ::
							SpacTraceElement.InInputContext(ObjectStart(), ContextWithLine(1)) ::
							SpacTraceElement.InSplitter("foo \\ anyIndex", SplitterLine()) ::
							SpacTraceElement.InParse("parser", `methodName`, _) ::
							Nil =>
					}
				}
			}
		}

		describe("running an exception-throwing transformer") {
			val rawJson =
				"""[
				  |  { "a": 1, "b": true },
				  |  { "a": 2, "b": false },
				  |  { "a": 3, "b": null },
				  |  { "a": 4, "b": true }
				  |]""".stripMargin

			val BFieldLine = new LineNumberCell
			val CompoundLine = new LineNumberCell
			val MainSplitterLine = new LineNumberCell


			val innerParser = (
				JsonParser.fieldOf[Int]("a"),
				JsonParser.fieldOf[Boolean]("b") &: BFieldLine
			).tupled &: CompoundLine
			val transformer = Splitter.json(anyIndex).joinBy(innerParser) &: MainSplitterLine

			def expectedSpacTrace(parseMethod: String, ParseLine: LineNumberCell): PartialFunction[Any, _] = {
				case SpacTraceElement.InInput(JNull()) ::
					SpacTraceElement.InInputContext(FieldStart("b"), ContextWithLine(4)) ::
					SpacTraceElement.InInputContext(ObjectStart(), ContextWithLine(4)) ::
					SpacTraceElement.InSplitter("b", BFieldLine()) ::
					SpacTraceElement.InCompound(1, 2, CompoundLine()) ::
					SpacTraceElement.InInputContext(IndexStart(2), ContextWithLine(4)) ::
					SpacTraceElement.InInputContext(ArrayStart(), ContextWithLine(1)) ::
					SpacTraceElement.InSplitter("anyIndex", MainSplitterLine()) ::
					SpacTraceElement.InParse("transformer", `parseMethod`, ParseLine()) ::
					Nil => // ok
			}

			it("as an iterator should provide 'spac trace' information") {
				val TransformLine = new LineNumberCell
				val rawEvents = JsonParser.toList.parse(rawJson)
				val outputItr = transformer.transform(rawEvents.iterator) &: TransformLine
				val caught = intercept[SpacException.UnexpectedInputException[_]] { outputItr.toList }
				caught.spacTrace.toList should matchPattern(expectedSpacTrace("transform", TransformLine))
			}

			it("as an fs2.Pipe should provide 'spac trace' information") {
				val ToPipeLine = new LineNumberCell
				val drainStreamIO = ToPipeLine & stringToJsonStream(rawJson).through(transformer.toPipe).compile.drain
				intercept[SpacException.UnexpectedInputException[_]] { drainStreamIO.unsafeRunSync() }
					.spacTrace.toList
					.should(matchPattern(expectedSpacTrace("toPipe", ToPipeLine)))
			}
		}
	}

}
