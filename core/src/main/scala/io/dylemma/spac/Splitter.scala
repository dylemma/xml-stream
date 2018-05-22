package io.dylemma.spac

import io.dylemma.spac.handlers.{ContextTracker, SplitOnMatchHandler, StackSplitterHandler}
import io.dylemma.spac.types.Stackable

import scala.language.higherKinds

/** A stream transformation which divides a stream of `In` events into substreams,
  * where each substream is associated with a `Context` value.
  *
  * A splitter must be combined with a Consumer via the `through` method.
  * The consumer will be run on each of the substreams created by this splitter,
  * generating a single value for each substream. The combination of a Splitter
  * and a Consumer/Parser yields a `Transformer`.
  *
  * For example, given a stream of symbols like
  * {{{ A A [ 1 2 3 ] B C [ 4 5 6 ] }}}
  * an example Splitter might use `[` and `]` as signals that a context is starting
  * or ending, respectively. So the substreams identified by that splitter would be
  *  - `[ 1 2 3 ]`
  *  - `[ 4 5 6 ]`
  *
  * The example splitter would need to be combined with a Consumer/Parser that understands
  * those substreams individually. For example, a Consumer that calculates the sum of
  * the numbers between the `[` and `]` would generate the following results for each
  * respective substream:
  *  - `6`
  *  - `15`
  *
  * Thus the combination of the example splitter and the summing consumer would yield
  * a transformer that turns the stream `A A [ 1 2 3 ] B C [ 4 5 6 ]` into the stream
  * `6 15`. That transformer will need to be attached to some downstream handler to
  * produce a meaningful value from the transformed stream, e.g. a `Consumer.toList`
  * could consume the `6 15` stream as a `List(6, 15)`.
  * @tparam In
  * @tparam Context
  */
trait Splitter[In, +Context] {
	/** Combine this splitter with a "joiner" which obtains a consumer (generalized as
	  * a `HandlerFactory` for its substreams based on a Context value.
	  * If the consumer logic is independent of any Context value, you can pass
	  * a HandlerFactory directly instead of wrapping it as a function, since
	  * `HandlerFactory` extends `Any => HandlerFactory`.
	  *
	  * @param joiner The consumer logic for substreams generated by this Splitter
	  * @tparam Out The result type returned by the consumer logic
	  * @return A Transformer that feeds inputs through this splitter to create
	  *         substreams, then feeds those substreams into the `joiner` to create
	  *         `Out` values that will be passed downstream.
	  */
	def through[Out](joiner: Context => HandlerFactory[In, Out]): Transformer[In, Out]
}

/** Base implementation for Splitters that deal with a "stack-like" input type, e.g. XML or JSON.
  * As the stream goes through the splitter, a context stack is maintained, and passed into a
  * ContextMatcher. Substream boundaries are defined as the events that cause the stack to enter
  * and exit states that cause the `matcher` returns a value.
  *
  * @param matcher A function that decides whether the context stack represents a `Context` value
  * @param stackable A strategy for updating the context stack based on inputs
  * @tparam In The input type
  * @tparam StackElem The context stack type
  * @tparam Context The interpreted value returned by the `matcher` given a context stack
  */
class BaseStackSplitter[In, StackElem, +Context](
	matcher: ContextMatcher[StackElem, Context]
)(
	implicit stackable: Stackable.Aux[In, StackElem]
) extends Splitter[In, Context] { self =>
	override def toString = s"Splitter($matcher)"
	def through[P](joiner: Context => HandlerFactory[In, P]): Transformer[In, P] = new Transformer[In, P] {
		override def toString = s"$self.through($joiner)"
		def makeHandler[Out](downstream: Handler[P, Out]): Handler[In, Out] = {
			new StackSplitterHandler(matcher, joiner, downstream)
		}
	}

	def as[P](implicit parser: Context => HandlerFactory[In, P]) = through(parser)

}

/** Typeclass that abstracts Splitter construction for specific "context stack" types.
  *
  * @tparam StackElem The "context stack" type
  * @tparam Instance The Splitter subclass, e.g. `XMLSplitter` or `JSONSplitter`
  */
trait SplitterApply[StackElem, Instance[+_]] {
	/** Construct a new Splitter given a ContextMatcher.
	  *
	  * @param matcher A function that decides whether the "context stack" represents a `Context` value
	  * @tparam Context The interpreted value returned by the `matcher` given a context stack
	  * @return A new Splitter based on the `matcher`
	  */
	def apply[Context](matcher: ContextMatcher[StackElem, Context]): Instance[Context]
}

object Splitter {

	/** Create a Splitter for some stack-like format, using the `matcher` to identify where substreams start and end.
	  * As inputs come through the returned splitter, a "context stack" state will be updated and passed to the given
	  * `matcher`. The substream boundaries are the inputs that cause the context stack to enter and exit states for
	  * which the `matcher` identifies a `Context` value.
	  *
	  * For example:
	  *
	  * {{{
	  * // for XML:
	  * val matcher: ContextMatcher[StartElem, MyContext] = /* ... */
	  * val splitter = Splitter(matcher) // returns an XMLSplitter[MyContext]
	  * }}}
	  *
	  * @param matcher A function that decides whether the context stack represents a `Context` value
	  * @param stacker A strategy for updating the context stack based on inputs
	  * @tparam StackElem The type of items in the context stack
	  * @tparam Context The type of value returned by `matcher` to be used as a substream context.
	  * @tparam Instance The specific Splitter subclass, e.g. XMLSplitter or JSONSplitter
	  * @return A new Splitter that uses the given `matcher` to identify where substreams start and end
	  */
	def apply[StackElem, Context, Instance[+_]](matcher: ContextMatcher[StackElem, Context])(implicit stacker: SplitterApply[StackElem, Instance]): Instance[Context] = {
		stacker(matcher)
	}

	/** Create a Splitter that treats consecutive matched values as substreams.
	  * For example, given a matcher like `{ case c if c.isLetter => c }`, a stream like
	  * {{{1 2 3 A B C 4 5 6 D 7 8 E F G H 9}}}
	  * could be treated as having three substreams, where each substream's "context value"
	  * is the first letter in that group (because context is always defined by the beginning
	  * of the substream).
	  *
	  *  - `A B C` with context `'A'` (between the 3 and 4)
	  *  - `D` with context `'D'` (between the 6 and 7)
	  *  - `E F G H` with context `'E'` (between the 8 and 9)
	  *
	  *
	  * @param matcher A function defining which inputs count as a "match"
	  * @tparam In
	  * @tparam Context
	  * @return
	  */
	def splitOnMatch[In, Context](matcher: PartialFunction[In, Context]): Splitter[In, Context] = {
		new Splitter[In, Context] {self =>
			override def toString = s"Splitter.splitOnMatch($matcher)"
			def through[Out](joiner: Context => HandlerFactory[In, Out]): Transformer[In, Out] = {
				new Transformer[In, Out] {
					override def toString = s"$self{ $joiner }"
					def makeHandler[A](downstream: Handler[Out, A]): Handler[In, A] = {
						new SplitOnMatchHandler(matcher, joiner, downstream)
					}
				}
			}
		}
	}

	/** Create a Splitter that treats consecutive values matching the predicate `p` as
	  * substreams with no particular context value.
	  * For example, given a matcher like `i => i % 2 == 0`, a stream like
	  * {{{1 3 2 2 4 5 6 7 8 10 4 3 1}}}
	  * could be treated as having three substreams:
	  *
	  *  - `2 2 4`
	  *  - `6`
	  *  - `8 10 4`
	  *
	  * @param p
	  * @tparam In
	  * @return
	  */
	def splitOnMatch[In](p: In => Boolean): Splitter[In, Any] = splitOnMatch[In, Any] {
		case in if p(in) => ()
	}
}