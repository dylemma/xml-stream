package io.dylemma.xml.example

import javax.xml.stream.events.XMLEvent

import io.dylemma.spac._
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat

object Example_FromReadme extends App {

	case class Post(date: LocalDate, author: Author, stats: Stats, body: String, comments: List[Comment])
	case class Author(id: String, name: String)
	case class Stats(numLikes: Int, numTweets: Int)
	case class Comment(date: LocalDate, author: Author, body: String)

	val rawXml = """<blog>
		|  <post date="2015-11-16">
		|    <author name="dylemma" id="abc123"/>
		|    <stats likes="123" tweets="4"/>
		|    <body>Hello world!</body>
		|    <comments>
		|      <comment date="2015-11-18">
		|        <author name="anonymous" id="def456"/>
		|        <body>I'm commenting on your fake blog!</body>
		|      </comment>
		|    </comments>
		|  </post>
		|  <post date="2015-11-18">
		|    <author name="johndoe" id="004200"/>
		|    <stats likes="7" tweets="1"/>
		|    <body>A second blog post, huzzah!</body>
		|    <comments>
		|      <comment date="2015-11-19">
		|        <author name="anonymous" id="def456"/>
		|        <body>It's me again</body>
		|      </comment>
		|    </comments>
		|  </post>
		|</blog>"""

	val commentDateFormat = DateTimeFormat.forPattern("yyyy-MM-dd")
	val dateAttributeParser = Parser.forMandatoryAttribute("date").map(commentDateFormat.parseLocalDate)

	implicit val AuthorParser: Parser[Any, Author] = Parser.combine(
		Parser.forMandatoryAttribute("id"),
		Parser.forMandatoryAttribute("name")
	).as(Author)

	val authorElementParser = Splitter(* \ "author").first[Author]

	implicit val StatsParser: Parser[Any, Stats] = Parser.combine(
		Parser.forMandatoryAttribute("likes").map(_.toInt),
		Parser.forMandatoryAttribute("tweets").map(_.toInt)
	).as(Stats)

	implicit val CommentParser: Parser[Any, Comment] = Parser.combine(
		dateAttributeParser,
		authorElementParser,
		Splitter(* \ "body").first.asText
	).as(Comment)

	implicit val PostParser: Parser[Any, Post] = Parser.combine(
		dateAttributeParser,
		authorElementParser,
		Splitter(* \ "stats").first[Stats],
		Splitter(* \ "body").first.asText,
		Splitter(* \ "comments" \ "comment").asListOf[Comment]
	).as(Post)

	val postTransformer: Transformer[XMLEvent, Post] = Splitter("blog" \ "post") through PostParser
	val postTransformerAlt = Splitter("blog" \ "post").as[Post] // available because PostParser is marked implicit

	postTransformer.consumeForEach(println) consume rawXml
}