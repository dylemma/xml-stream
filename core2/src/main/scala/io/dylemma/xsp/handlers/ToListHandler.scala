package io.dylemma.xsp.handlers

import io.dylemma.xsp.{Handler, Result}

class ToListHandler[A] extends Handler[A, Result[List[A]]] {
	override def toString = "ToList"
	private val lb = List.newBuilder[A]
	private var _finished = false
	def isFinished: Boolean = _finished
	def handleInput(input: A): Option[Result[List[A]]] = {
		lb += input
		None
	}
	def handleError(err: Throwable): Option[Result[List[A]]] = {
		_finished = true
		lb.clear()
		Some(Result.Error(err))
	}
	def handleEnd(): Result[List[A]] = {
		if(_finished) throw new IllegalStateException("handleEnd() called after finish")
		else Result(lb.result())
	}
}