package io.dylemma.spac

import cats.data.Chain
import cats.effect.SyncIO
import io.dylemma.spac.impl.{ParserInterruptedBy, ParserOrElseList}
import io.dylemma.spac.types.Stackable2
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.util.Success

class ParserTests extends AnyFunSpec with Matchers with ScalaCheckPropertyChecks with SpacTestUtils {

	describe("Parser # map") {
		val firstOptParser = Parser[SyncIO, Int].firstOpt
		val listParser = Parser[SyncIO, Int].toList

		it("should return a result equivalent to running the mapping function on the base parser's result") {
			def invariant[A, B](base: Parser[SyncIO, Int, A], f: A => B) = {
				forAll { (list: List[Int]) =>
					base.map(f).parse(list).unsafeRunSync() shouldEqual f(base.parse(list).unsafeRunSync())
				}
			}

			invariant[Option[Int], String](firstOptParser, _.fold("")("It was " + _))
			invariant[List[Int], String](listParser, _.mkString("_"))
		}
		it("should pull the same number of inputs from the source as the base parser would have") {
			def invariant[A, B](base: Parser[SyncIO, Int, A], f: A => B) = {
				forAll { (list: List[Int]) =>
					val basePullCount = countPullsIn(list) { base.parse(_).unsafeRunSync() }
					val mappedPullCount = countPullsIn(list) { base.map(f).parse(_).unsafeRunSync() }
					basePullCount shouldEqual mappedPullCount
				}
			}

			invariant[Option[Int], String](firstOptParser, _.fold("")("It was " + _))
			invariant[List[Int], String](listParser, _.mkString("_"))
		}
		describe("with a mapping function that throws exceptions") {
			val dummyException = new Exception("oh no")
			val errorProneParser = listParser.map[String](_ => throw dummyException)
			it("should capture exceptions thrown by the mapping function and lift them into the effect context") {
				forAll { (list: List[Int]) =>
					errorProneParser.parse(list).attempt.unsafeRunSync() shouldEqual Left(dummyException)
				}
			}
			it("should not throw the exception until the base parser has yielded a result") {
				forAll { (list: List[Int]) =>
					countPullsIn(list) { errorProneParser.parse(_).attempt.unsafeRunSync() } shouldEqual (list.size + 1)
				}
			}
		}
	}

	describe("Parser # orElse") {
		val p1 = Parser[SyncIO, Int].firstOpt.map(_ => "firstOpt")
		val p2 = Parser[SyncIO, Int].toList.map(_ => "toList")
		val p3 = Parser[SyncIO, Int].fold("")(_ + _)
		val err1 = new Exception("Error 1")
		val err2 = new Exception("Error 2")
		val pErr1 = Parser.eval { SyncIO.raiseError(err1) }
		val pErr2 = Parser.eval { SyncIO.raiseError(err2) }

		it("should return the result from the earliest underlying parser that succeeds") {
			forAll { (list: List[Int]) =>
				whenever(list.nonEmpty) {
					p1.orElse(p2).parse(list).unsafeRunSync() shouldEqual "firstOpt"
					p2.orElse(p1).parse(list).unsafeRunSync() shouldEqual "firstOpt"
				}
			}
		}
		it("should return the result whichever successful parser was earliest in the orElse chain") {
			forAll { (list: List[Int]) =>
				p2.orElse(p3).parse(list).unsafeRunSync() shouldEqual "toList"
				p3.orElse(p2).parse(list).unsafeRunSync() shouldEqual list.mkString
			}
		}
		it("should discard errors from underlying parsers as long as at least one succeeds") {
			forAll { (list: List[Int]) =>
				p2.orElse(pErr1).parse(list).unsafeRunSync() shouldEqual "toList"
				pErr1.orElse(p2).parse(list).unsafeRunSync() shouldEqual "toList"
			}
		}
		it("should raise a NoSuccessfulParsersException if all of the underlying parsers fail") {
			forAll { (list: List[Int]) =>
				val e = intercept[SpacException.FallbackChainFailure] {
					pErr1.orElse(pErr2).parse(list).unsafeRunSync()
				}
				e.underlyingErrors.toList shouldEqual List(err2, err1)
			}
		}
		it("should allow many parsers to be composed together into a single fallback chain") {
			val parser = p2 orElse p3 orElse pErr1 orElse pErr2 orElse p1
			forAll { (list: List[Int]) =>
				// normally p1 will finish first because it finishes on the first step,
				// but if the list is empty, everything finishes at once, so the tiebreaker is which one is earlier in the chain
				parser.parse(list).unsafeRunSync() shouldEqual (if (list.isEmpty) "toList" else "firstOpt")
			}
			// making sure that repeatedly calling `orElse` will build up one big chain rather than a hierarchy of chains
			parser should matchPattern {
				// Scalac is being stupid and doesn't understand this line
				// case ParserOrElseList(Right(`p2`) :: Right(`p3`) :: Right(`pErr1`) :: Right(`pErr2`) :: Right(`p1`) :: Nil) =>
				// so I'm doing it this way instead >:(
				case ParserOrElseList(Right(a) :: Right(b) :: Right(c) :: Right(d) :: Right(e) :: Nil)
					if (a == p2) && (b == p3) && (c == pErr1) && (d == pErr2) && (e == p1) =>
			}
		}
	}

	describe("Parser # attempt") {
		val p1 = Parser[SyncIO].pure(42)
		val p2 = Parser[SyncIO].first[String].map(_.toInt)
		val err = new Exception("oh no")
		val pErr = Parser.eval(SyncIO { throw err })

		it("should wrap a successful parser's result in a `Right`") {
			forAll { (list: List[Int]) =>
				p1.attempt.parseSeq(list).unsafeRunSync() shouldEqual Right(42)
			}
		}
		it("should catch an exception thrown by a failed parser if the Err type is Throwable") {
			p2.attempt.parseSeq(Nil).unsafeRunSync() should matchPattern { case Left(e: SpacException.MissingFirstException[_]) => }
			p2.attempt.parseSeq(List("hi")).unsafeRunSync() should matchPattern { case Left(e: NumberFormatException) => }
			p2.attempt.parseSeq(List("42")).unsafeRunSync() shouldEqual Right(42)
			pErr.attempt.parseSeq(List("...")).unsafeRunSync() shouldEqual Left(err)
		}
		it("should exit upon non-exception error conditions, depending on the MonadError for the effect type") {
			import cats.instances.either._
			val p3 = Parser[Either[String, +*], String].foldEval(0) { (sum, next) =>
				if (next.forall(_.isDigit)) Right(sum + next.toInt)
				else Left(s"'$next' is not a number")
			}

			p3.parseSeq(List("1", "2", "3")) shouldEqual Right(6)
			p3.parseSeq(List("1", "2", "go")) shouldEqual Left("'go' is not a number")
			p3.attempt.parseSeq(List("1", "2", "3")) shouldEqual Right(Right(6))
			p3.attempt.parseSeq(List("1", "2", "go")) shouldEqual Right(Left("'go' is not a number"))
		}
	}

	describe("Parser # rethrow") {
		val p1 = Parser[SyncIO].pure(Right(42))
		val err = new Exception("oh no")
		val p2 = Parser[SyncIO].pure(Left(err: Throwable))

		it("should unwrap a successful `Right` result as a plain result instead") {
			p1.rethrow.parseSeq(List(1, 2, 3)).unsafeRunSync() shouldEqual 42
		}

		it("should treat a successful `Left` result as an error in the effect context instead") {
			intercept[Exception] { p2.rethrow.parseSeq(List(1, 2, 3)).unsafeRunSync() } shouldEqual err
		}
	}

	describe("Parser # unwrapSafe") {
		val p1 = Parser[SyncIO].pure(Success(42))
		val err = new Exception("oh no")
		val p2 = p1.map(_ => throw err)

		it("should unwrap a Success result as a plain result instead") {
			p1.unwrapSafe.parseSeq(List(1, 2, 3)).unsafeRunSync() shouldEqual 42
		}

		it("should treat a successful `Failure` result as an error in the effect context instead") {
			intercept[Exception] { p2.unwrapSafe.parseSeq(List(1, 2, 3)).unsafeRunSync() } shouldEqual err
		}
	}

	describe("Parser # expectInputs") {
		val p1 = Parser[SyncIO].toList[Int]
		val p2 = p1.expectInputs[Int](List(
			"1" -> { _ == 1 },
			"an even number" -> { _ % 2 == 0 },
			"3" -> { _ == 3 },
		))

		it("should not interrupt the underlying parser if all of the inputs match the expectations") {
			forAll { (tail: List[Int]) =>
				val list = 1 :: 2 :: 3 :: tail
				p2.parseSeq(list).unsafeRunSync() shouldEqual list
			}
		}

		it("should raise an UnfulfilledInputsException which lists the remaining expected inputs, upon encountering an early EOF") {
			def expectUnfulfilled(expectations: List[String], inputs: List[Int]) = {
				intercept[SpacException.UnfulfilledInputsException] { p2.parseSeq(inputs).unsafeRunSync() }
					.expectations.toList.shouldEqual(expectations)
			}

			expectUnfulfilled(List("1", "an even number", "3"), Nil)
			expectUnfulfilled(List("an even number", "3"), List(1))
			expectUnfulfilled(List("3"), List(1, 2))
		}

		it("should raise an UnexpectedInputException which lists the current and remaining expected inputs, upon encountering an input that doesn't match the predicate") {
			def expectUnexpected(unexpected: Int, expectations: List[String], inputs: List[Int]) = {
				val captured = intercept[SpacException.UnexpectedInputException[Int]] { p2.parseSeq(inputs).unsafeRunSync() }
				captured.expectations.toList shouldEqual expectations
				captured.input shouldEqual unexpected
			}

			expectUnexpected(69, List("1", "an even number", "3"), List(69, 2, 3))
			expectUnexpected(7, List("an even number", "3"), List(1, 7, 3))
			expectUnexpected(5, List("3"), List(1, 2, 5)) // three, sir!
		}
	}

	describe("Parser # interruptedBy") {
		val p1 = Parser[SyncIO].toList[Int]
		val interrupter = Transformer.op[SyncIO, Int, Any] { Emit.one(_).filter(_ == 0) } :> Parser.firstOpt
		val parser = p1 interruptedBy interrupter

		it("should proceed normally if the interrupter never yields a result") {
			forAll { (_list: List[Int]) =>
				val list = _list.filterNot(_ == 0) // 0 is the interruption criteria
				parser.parseSeq(list).unsafeRunSync() shouldEqual list
			}
		}

		it("should stop parsing when the interrupter yields a result, not sending the input that triggered the interruption") {
			parser.parseSeq(List(3, 2, 1, 0, 5, 4, 3, 2, 1)).unsafeRunSync() shouldEqual List(3, 2, 1)
		}

		it("should raise an error if the interrupter raises an error") {
			val dummyException = new Exception("hi")
			val errorInterrupter = interrupter.map { _ => throw dummyException }
			val list = List(3, 2, 1, 0, -1, -2, -3)
			p1.interruptedBy(errorInterrupter).parse(list).attempt.unsafeRunSync() shouldEqual Left(dummyException)
			p1.interruptedBy(errorInterrupter.attempt).parse(list).attempt.unsafeRunSync() shouldEqual Right(List(3, 2, 1))
		}

		it("should raise an error if the base parser raises an error") {
			val dummyException = new Exception("oh no")
			val p2 = Parser[SyncIO, Int].fold(0) { (sum, i) =>
				val nextSum = sum + i
				if (nextSum > 5) throw dummyException else nextSum
			}
			val pE = p2.interruptedBy(interrupter)
			pE.parseSeq(List(1, 1, 1, 1)).attempt.unsafeRunSync() shouldEqual Right(4)
			pE.parseSeq(List(1, 2, 3, 4)).attempt.unsafeRunSync() shouldEqual Left(dummyException)
		}
	}

	describe("Parser # beforeContext") {
		type In = (Int, String)
		implicit val DemoStackable: Stackable2[In, String] = {
			case e@(i, s) if i > 0 => ContextPush(ContextTrace(Chain.nil), s).afterInput
			case e@(i, _) if i < 0 => ContextPop.beforeInput
			case e => StackInterpretation.NoChange
		}
		val matcher = new SingleItemContextMatcher.Predicate[String](_ == "one") \ new SingleItemContextMatcher.Predicate[String](_ == "two")

		val p1 = Transformer[SyncIO, In].map(_._2) :> Parser.toList
		val parser = p1.beforeContext(matcher)

		it("should delegate to `interruptedBy`") {
			parser shouldBe a[ParserInterruptedBy[SyncIO, _, _]]
		}

		it("should not interrupt the main parser if the `matcher` never causes a ContextPush") {
			parser.parseSeq(List(
				0 -> "A",
				0 -> "B",
				1 -> "C", // stack push "C"
				-1 -> "D" // stack pop
			)).unsafeRunSync() shouldEqual List("A", "B", "C", "D")
		}

		it("should interrupt the main parser immediately when the `matcher` causes a ContextPush") {
			parser.parseSeq(List(
				0 -> "A",
				1 -> "one", // stack push "one"
				1 -> "two", // stack push "two" --> `matcher` should match here and interrupt the toList parser
				-1 -> "exit2", // would-be stack pop
				-1 -> "exit1", // would-be stack pop
				0 -> "end"
			)).unsafeRunSync() shouldEqual List("A", "one")
		}
	}

	describe("Parser # followedBy / followedByStream") {

		// shared Stackable instances for the tests
		val NoStackChanges: Stackable2[Int, Nothing] = _ => StackInterpretation.NoChange
		val StackOnTens: Stackable2[Int, Int] = { i =>
			if (i % 10 == 0) {
				if (i > 0) ContextPush(ContextTrace(Chain.nil), i).afterInput
				else if (i < 0) ContextPop.beforeInput
				else StackInterpretation.NoChange
			} else StackInterpretation.NoChange
		}

		it ("should start the follow-up parser as soon as the base parser returns a result") {
			implicit val testStackable: Stackable2[Int, Nothing] = NoStackChanges
			val base = Parser[SyncIO, Int].first

			val parser = base.followedBy { i => Transformer[SyncIO, Int].map(_ * i) :> Parser.toList }
			parser.parseSeq(List(1, 2, 3, 4)).unsafeRunSync() shouldEqual List(2, 3, 4)
			parser.parseSeq(List(2, 3, 4, 5)).unsafeRunSync() shouldEqual List(6, 8, 10)

			val transformer = base.followedByStream { i => Transformer[SyncIO, Int].map(_ * i) }
			(transformer :> Parser.toList).parseSeq(List(1, 2, 3, 4)).unsafeRunSync() shouldEqual List(2, 3, 4)
			(transformer :> Parser.toList).parseSeq(List(2, 3, 4, 5)).unsafeRunSync() shouldEqual List(6, 8, 10)
		}

		it ("should feed the stack-accumulating events to the follow-up parser before continuing") {
			implicit val testStackable: Stackable2[Int, Int] = StackOnTens

			val match10 = new SingleItemContextMatcher.Predicate[Int](_ == 10)
			val base = Splitter[SyncIO, Int](match10).first.into(_ => Transformer[SyncIO, Int].take(3) :> Parser.toList)
			val input = List(
				1, 2, 3, 4, // all of this should end up ignored by the `base`
				10, // context push to start the base off, plus the follow-up should see this during the reply
				5, 6, 7, // first 3 in the context should be captured by the base
				9, 8, 7, // these should be sent to the follow-up after replaying the 10
			)

			val parser = base.followedBy(initialList => Parser[SyncIO, Int].toList.map(initialList -> _))
			parser.parseSeq(input).unsafeRunSync() shouldEqual {
				List(5, 6, 7) -> List(10, 9, 8, 7)
			}

			val transformer = base.followedByStream(initialList => Transformer[SyncIO, Int].map(initialList -> _))
			(transformer :> Parser.toList).parseSeq(input).unsafeRunSync() shouldEqual List(
				List(5, 6, 7) -> 10,
				List(5, 6, 7) -> 9,
				List(5, 6, 7) -> 8,
				List(5, 6, 7) -> 7
			)
		}

		it ("should properly accumulate the stack for replaying to the follow-up") {
			implicit val testStackable: Stackable2[Int, Int] = StackOnTens

			val base = Transformer[SyncIO, Int].filter(_ == 42) :> Parser.first
			val input = List(
				10, 20, -20, -10, // at this point the stack should go back to Nil
				10, 11, 20, 21, 30, 31, // the 10, 20, and 30 should be saved on the stack and get replayed to the follow-up
				40, -40, // just some extra push+pop of the stack
				42, // finish the base parser
				1, 2, 3, // some junk for the follow-up to grab
			)

			val parser = base.followedBy(_ => Parser.toList)
			parser.parseSeq(input).unsafeRunSync() shouldEqual {
				List(10, 20, 30, 1, 2, 3)
			}

			val transformer = base.followedByStream(i => Transformer[SyncIO, Int].map(i - _))
			(transformer :> Parser.toList).parseSeq(input).unsafeRunSync() shouldEqual {
				List(32, 22, 12, 41, 40, 39)
			}
		}

		it ("should be able to end the follow-up parser if that parser would finish during the stack replay") {
			implicit val testStackable: Stackable2[Int, Int] = StackOnTens

			val base = Transformer[SyncIO, Int].filter(_ == 42) :> Parser.firstOpt

			val parser = base.followedBy(_ => Parser.firstOpt)
			parser.parseSeq(List(10, 42, 11)).unsafeRunSync() shouldEqual Some(10)
			parser.parseSeq(List(42, 11)).unsafeRunSync() shouldEqual Some(11)
		}

		it ("should `finish` the follow-up parser immediately if the base parser consumes the whole input and there is no stack") {
			implicit val testStackable: Stackable2[Int, Int] = StackOnTens

			val base = Transformer[SyncIO, Int].filter(_ == 42) :> Parser.firstOpt
			val parser = base.followedBy(_ => Parser.firstOpt)

			parser.parseSeq(List(1, 2, 3, 42)).unsafeRunSync() shouldEqual None
		}

		it ("should allow chaining of the followedBy relationship") {
			implicit val testStackable: Stackable2[Int, Int] = NoStackChanges
			val find42 = Transformer[SyncIO, Int].filter(_ == 42) :> Parser.firstOpt
			val find69 = Transformer[SyncIO, Int].filter(_ == 69) :> Parser.firstOpt
			val getFirst = Parser[SyncIO].firstOpt[Int]

			val parser = find42.followedBy(_42 => find69.followedBy(_69 => getFirst))
			val parserAltSyntax = for {
				_42 <- find42.followedBy
				_96 <- find69.followedBy
				firstOpt <- getFirst
			} yield firstOpt
			val input = List(1, 2, 3, 42, 5, 69, 7)
			parser.parse(input).unsafeRunSync() shouldEqual Some(7)
			parserAltSyntax.parse(input).unsafeRunSync() shouldEqual Some(7)
		}

	}
}
