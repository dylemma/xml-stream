package io.dylemma.spac
package impl

case class TransformerTap[In](f: In => Unit) extends Transformer.Stateless[In, In] {
	def push(in: In, out: Transformer.HandlerWrite[In]): Signal = {
		f(in)
		out.push(in)
	}
	def finish(out: Transformer.HandlerWrite[In]): Unit = ()
}
