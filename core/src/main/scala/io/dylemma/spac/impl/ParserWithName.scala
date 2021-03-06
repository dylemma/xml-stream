package io.dylemma.spac
package impl

class ParserWithName[In, Out](self: Parser[In, Out], name: String) extends Parser[In, Out] {
	override def toString = name
	def newHandler = self.newHandler
}
