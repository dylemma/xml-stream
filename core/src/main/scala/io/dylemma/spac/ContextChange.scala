package io.dylemma.spac

/** Represents either entering (`ContextPush`) or exiting (`ContextPop`) some matched context within a stream of inputs.
  *
  * ContextChanges will generally be used to designate "sub-stream" boundaries, i.e. a selection of xml elements from within a stream,
  * but may be used more generally to attach a stack-like state to stream transformers.
  *
  * @tparam In The value type of the elements in the stream being inspected
  * @tparam C The type of the matched context
  */
sealed trait ContextChange[+In, +C]
case class ContextPush[+In, +C](location: ContextTrace[In], context: C) extends ContextChange[In, C]
case object ContextPop extends ContextChange[Nothing, Nothing]