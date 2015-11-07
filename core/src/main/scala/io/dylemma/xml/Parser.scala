package io.dylemma.xml

import javax.xml.stream.events.XMLEvent
import scala.concurrent.{ Future, ExecutionContext }

import play.api.libs.iteratee.{ Enumerator, Iteratee }

/**
 * Created by dylan on 11/5/2015.
 */

trait Parser[-C, +T] { self =>
	import Parser._

	def toIteratee(context: C)(implicit ec: ExecutionContext): Iteratee[XMLEvent, Result[T]]

	def parse[In: AsInputStream](input: In)(implicit ec: ExecutionContext, ev: Unit <:< C): Future[Parser.Result[T]] = {
		parse(XMLEventEnumerator(input))
	}

	def parse(stream: Enumerator[XMLEvent])(implicit ec: ExecutionContext, ev: Unit <:< C): Future[Parser.Result[T]] = {
		val consumer = toIteratee(())
		stream run consumer
	}

	/** Creates a new Parser that passes successful results through a
		* transformation function (`f`)
		*/
	def map[U](f: T => U): Parser[C, U] = new Parser[C, U] {
		def toIteratee(context: C)(implicit ec: ExecutionContext) = self.toIteratee(context).map(_ map f)
	}

	/** Creates a new Parser that transforms each result according to
		* a transformation function (`f`).
		*/
	def mapR[U](f: Result[T] => Result[U]) = new Parser[C, U] {
		def toIteratee(context: C)(implicit ec: ExecutionContext) = self.toIteratee(context).map(f)
	}

	/** Create a parser that adapts to another context type (`C1`) by mapping
		* values from that type to an appropriate context value for this parser.
		*/
	def mapContext[C1](f: C1 => C) = new Parser[C1, T] {
		def toIteratee(context: C1)(implicit ec: ExecutionContext) = self.toIteratee(f(context))
	}
}


object Parser {
	/** Result value from parsing some input.
		* Possible values are Success (with a value), Error (with an exception), or Empty.
		*
		* @tparam T The type of values contained in a `Success`ful result
		*/
	sealed trait Result[+T] {
		def map[U](f: T => U): Result[U] = this match {
			case Empty => Empty
			case e: Error => e
			case Success(t) =>
				try { Success(f(t)) }
				catch { case err: Throwable => Error(err) }
		}
		def withFilter(f: T => Boolean): Result[T] = this match {
			case Empty => Empty
			case e: Error => e
			case s @ Success(t) =>
				try { if(f(t)) s else Empty }
				catch { case err: Throwable => Error(err) }
		}
		def flatMap[U](f: T => Result[U]): Result[U] = this match {
			case Empty => Empty
			case e: Error => e
			case Success(t) =>
				try { f(t) }
				catch { case err: Throwable => Error(err) }
		}
		def foreach[U](f: T => U): Unit = this match {
			case Success(t) => f(t)
			case _ => ()
		}
	}

	case object Empty extends Result[Nothing]
	case class Error(cause: Throwable) extends Result[Nothing]
	case class Success[T](result: T) extends Result[T]
}

