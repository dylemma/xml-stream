package io.dylemma.spac.syntax

import io.dylemma.spac.handlers.SequencedInStackHandler
import io.dylemma.spac.types.Stackable
import io.dylemma.spac.{ParserCombination, Parser, FollowedBy, FromHandlerFactory, Handler, Transformer}

trait ConsumerSyntax {

	/** Implicitly adds `followedBy` and `followedByStream` to Consumers with Stackable input types.
	  * (Due to type variance conflicts between Consumer's `In` type and Stackable's type, these
	  * methods can't be defined directly on Consumer.)
	  *
	  * @param consumer The wrapped Consumer
	  * @tparam In The consumer's input type, which must be a member of the `Stackable` typeclass
	  * @tparam T1 The consumer's output type
	  */
	implicit class ConsumerFollowedByOps[In: Stackable, T1](consumer: Parser[In, T1]) {

		/** An intermediate object with an `apply` and `flatMap` that both create a sequenced consumer
		  * which combines this consumer with a function to create the next one.
		  *
		  * Examples:
		  * {{{
		  *    val c1: Consumer[In, A] = /* ... */
		  *    def getC2(c1Result: A): Consumer[In, B] = /* ... */
		  *    val combined: Consumer[In, B] = c1.followedBy(getC2)
		  *
		  *    // alternative `flatMap` syntax
		  *    val combined: Consumer[In, B] = for {
		  *      c1Result <- c1.followedBy
		  *      c2Result <- getC2(c1Result)
		  *    } yield c2Result
		  * }}}
		  *
		  * An example of where this is useful is when a parser for XML element depends on values
		  * parsed from one of its previous siblings, but where you don't want to wait until the
		  * end of their parent element before they can be combined.
		  *
		  * @return An intermediate object which has an `apply` and `flatMap` that can be used
		  *         to combine this Consumer and another in a sequence.
		  */
//		def followedBy = new FollowedBy[({ type M[+T2] = AltParser[In, T2] })#M, T1] {
//			def apply[T2](getNext: T1 => AltParser[In, T2]): AltParser[In, T2] = new AltParser[In, T2] {
//				override def toString = s"$consumer.followedBy($getNext)"
//				def makeHandler(): Handler[In, T2] = {
//					new SequencedInStackHandler[In, T1, T2](consumer.makeHandler(), r1 => getNext(r1).makeHandler())
//				}
//			}
//		}

		/** An intermediate object with an `apply` and `flatMap` that can be used to create a Transformer from result of this consumer.
		  *
		  * Examples:
		  * {{{
		  *    val c1: Consumer[In, A] = /* ... */
		  *    def getStream(c1Result: A): Transformer[In, B] = /* ... */
		  *    val combined: Transformer[In, B] = c1.followedByStream(getStream)
		  *
		  *    // alternative `flatMap` syntax
		  *    val combined: Transformer[In, B] = for {
		  *      c1Result <- c1.followedByStream
		  *      c2Result <- getStream(c1Result)
		  *    } yield c2Result
		  * }}}
		  *
		  * An example of where this is useful is when an XML element contains some "dictionary" object
		  * at the beginning, followed by a sequence of "data" objects which reference the dictionary.
		  * For large sequences, combining them to a List (to use with Parser's `and` combiners) is undesireable;
		  * we can use this approach to avoid doing so.
		  *
		  * @return An intermediate object which has an `apply` and `flatMap` that can be used
		  *         to combine this consumer and a Transformer in a sequence.
		  */
//		def followedByStream = new FollowedBy[({ type F[+T2] = Transformer[In, T2] })#F, T1] {
//			def apply[T2](getTransformer: (T1) => Transformer[In, T2]): Transformer[In, T2] = new Transformer[In, T2] {
//				override def toString = s"$consumer.followedBy($getTransformer)"
//				def makeHandler[Out](next: Handler[T2, Out]): Handler[In, Out] = {
//					val handler1 = consumer.makeHandler()
//					def getHandler2(h1Result: T1) = getTransformer(h1Result).makeHandler(next)
//					new SequencedInStackHandler(handler1, getHandler2)
//				}
//			}
//		}

	}

	/** Adds the `and` and `~` methods to Consumers.
	  * Unlike Parsers, which have a fixed input type, the combination methods had to be separated from the main Consumer
	  * trait due to type variance conflicts on the `In` type.
	  * This upgrade should apply to *all* consumers thanks to [[FromHandlerFactory.toConsumer]].
	  *
	  * @param self A consumer
	  * @param fhf A type-level necessity that amounts to a no-op
	  * @tparam In The consumer input type
	  * @tparam A The consumer output type
	  */
	implicit class ConsumerCombineOps[In, A](self: Parser[In, A])(implicit fhf: FromHandlerFactory[In, ({ type C[+o] = Parser[In, o] })#C]) {
		/** Starting point for Consumer combination methods.
		  *
		  * @return A HandlerCombination instance for `Consumer`
		  */
//		protected def combination = new HandlerCombination[In, ({ type C[+o] = AltParser[In, o] })#C]

		/** Combine this Consumer with another one of the same input type.
		  * Note that the value returned by this method is an intermediate object which should be finalized
		  * by calling its `asTuple` or `as{(a,b) => result}` method.
		  * Further combinations can be added by calling `and` again on the intermediate object.
		  *
		  * Example:
		  * {{{
		  * val p1: Consumer[In, A] = /* ... */
		  * val p2: Consumer[In, B] = /* ... */
		  * val pc: Consumer[In, (A, B)] = (p1 and p2).asTuple
		  * // or
		  * val pc: Consumer[In, R] = (p1 and p2).as{ (a, b) => combineResults(a, b) }
		  * }}}
		  *
		  * @param other Another Consumer to combine with
		  * @tparam B The output type of the other Consumer
		  * @return An intermediate oject with `as` and `asTuple` methods that finish the combination
		  * @note This method is implicit added to *all* Consumers, since there is a `FromHandlerFactory`
		  *       instance available for every `Consumer` type. The fact that this method is added implicitly
		  *       is due to a type-variance conflict related to the `In` type.
		  */
//		def and[B](other: AltParser[In, B]) = combination.combine(self, other)

		/** Operator version of `and`, used to combine this Consumer with another one of the same input type.
		  * Note that the value returned by this method is an intermediate object which should be finalized
		  * by calling its `asTuple` or `as{(a,b) => result}` method.
		  * Further combinations can be added by calling `and` again on the intermediate object.
		  *
		  * Example:
		  * {{{
		  * val p1: Consumer[In, A] = /* ... */
		  * val p2: Consumer[In, B] = /* ... */
		  * val pc: Consumer[In, (A, B)] = (p1 ~ p2).asTuple
		  * // or
		  * val pc: Consumer[In, R] = (p1 ~ p2).as{ (a, b) => combineResults(a, b) }
		  * }}}
		  *
		  * @param other Another Consumer to combine with
		  * @tparam B The output type of the other Consumer
		  * @return An intermediate oject with `as` and `asTuple` methods that finish the combination
		  * @note This method is implicit added to *all* Consumers, since there is a `FromHandlerFactory`
		  *       instance available for every `Consumer` type. The fact that this method is added implicitly
		  *       is due to a type-variance conflict related to the `In` type.
		  */
//		def ~[B](other: AltParser[In, B]) = combination.combine(self, other)
	}
}
