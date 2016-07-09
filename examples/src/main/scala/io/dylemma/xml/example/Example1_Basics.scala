package io.dylemma.xml.example

import javax.xml.stream.events.XMLEvent

import io.dylemma.xsp._
import io.dylemma.xsp.syntax._

object Example1_Basics extends App {

	val libraryXml = """<library>
		| <book>Don Quixote</book>
		| <book>A Tale of Two Cities</book>
		| <book>The Lord of the Rings</book>
		| <book>The Little Prince</book>
		| <book>Harry Potter and the Sorcerer's Stone</book>
		| <book>And Then There Were None</book>
		|</library>""".stripMargin

	/*
	A `Parser[Context, Out]` is able to parse a stream of XMLEvents to produce a `Result[Out]`.
	Some parsers require a specific context value to be passed in before they can start parsing,
	but this one doesn't, so its `Context` type is `Any`.

	The `Parser.forText` parser will collect all of the `Characters` events it encounters, and concatenate them.
	 */
	val bookParser: Parser[Any, String] = Parser.forText

	/*
	If we run the `bookParser` by itself on the `libraryXml`, we get the titles of all of the
	books, along with the whitespace between the <book> elements. We probably don't want that...
	 */
	val allBookTitles = bookParser parse libraryXml
	println(allBookTitles)
	println("\n")

	/*
	We probably want the titles of each book separately. To do this, we use a `Splitter`.
	A `Splitter` divides the incoming stream of events into substreams. This one will make
	a new substream for each `<book>` elememnt inside a `<library>` element, i.e. there
	will be a substream for `"<book>Don Quixote</book>"` and another substream for
	`"<book>A Tale of Two Cities</book>"`, and so on.

	Note that some `Splitters` can extract a "context" value. This one simply matches without
	extracting anything, so its type parameter is just `Unit`.
	 */
	val bookSplitter: Splitter[Unit] = Splitter("library" \ "book")

	/*
	By attaching a parser to a splitter, you run the parser on each individual substream.
	This way we can get a separate event for each book.
	The result of combining a Splitter and a Parser is called a "Transformer" because it
	"transforms" an stream of inputs into a stream of something else.
	*/
	val bookTransformer: Transformer[XMLEvent, String] = bookSplitter.through(bookParser)

	/*
	To actually get a result from a stream, you'll either need a `Parser` or a `Consumer`.
	A Consumer is like Parser, except that it doesn't require a specific context type to work,
	and it can work on any type of inputs, not just XMLEvents.

	Transformers can be turned into Consumers via a handful of convenience methods.
	They can also be turned into Parsers as long as their input type is XMLEvent.
	 */
	val bookListConsumer: Consumer[XMLEvent, Result[List[String]]] = bookTransformer.consumeToList
	val bookListParser: Parser[Any, List[String]] = bookTransformer.parseToList

	/*
	The bookList parser and consumer will yield the same result, which is a `xsp.Result` containing
	the list of titles emitted by the `bookTransformer`.

	Note that the `parse` and `consume` methods work on a large number of types.
	See the docs for specifics, but for example, you could parse a File, String,
	InputStream, Iterable[XMLEvent], or Iterator[XMLEvent].
	 */
	val allBooksResult1 = bookListConsumer consume libraryXml
	val allBooksResult2 = bookListParser parse libraryXml
	assert(allBooksResult1 == allBooksResult2)
	println(allBooksResult1)
	println("\n")

	/*
	You can handle results as they are discovered by using one of the `foreach` transformer methods.
	 */
	val foreachConsumer = bookTransformer.consumeForEach{ title => println(s"book: $title") }
	foreachConsumer consume libraryXml

}