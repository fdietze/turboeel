abstract class Event {
  val receiver : IRCBot.Box[_]
}

object Event {

  case class Join(val channel: String,
                  val who: String,
                  val receiver : IRCBot.Box[_]) extends Event

  case class DccFileTransfer(
    /// TODO: ideally this should be wrapped to not have the dependency here...
    val transfer : org.jibble.pircbot.DccFileTransfer,
    val receiver : IRCBot.Box[_]) extends Event


  case class Topic(val channel: String,
                   val topic: String,
                   val setBy: String,
                   val receiver : IRCBot.Box[_]) extends Event


  // wrapper for a chat message. This can be a message inside a channel,
  // or a private message
  case class Chat(val content : String,
                  val sender : String,
                    // date : String
                  val receiver : IRCBot.Box[_]) extends Event



}
