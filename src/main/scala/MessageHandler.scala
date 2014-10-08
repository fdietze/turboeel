case class Channel(private val _name : String) extends AnyVal {
  def name = if(_name.startsWith("#")) _name else "#" + _name
}

/// Type class for anything that is an IRC Bot.
///
/// If you change anything here, make sure to also change
/// the implicits in the companion object
trait IRCBot[T] {

  def name(bot : T) : String
  def join(bot : T, chan : Channel)

}


/// Some common type class impl. for IRCBot
///
/// This includes TC for the Box class and the PircBot class.
object IRCBot {

  /// Make sure that anything that looks like an IRCBot, is extended with IRCBot functionality
  implicit class IRCBotTCWrapper[T : IRCBot](val inner : T) {
    def name = {implicitly[IRCBot[T]].name(inner)}
    def join(chan : Channel) {implicitly[IRCBot[T]].join(inner, chan)}
  }

  /// Box for anything that has a IRCBot type class. Use for lists and such.
  case class Box[T](t:T)(implicit val tcInst : IRCBot[T])

  /// Implement IRCBox type class for Boxes as well
  implicit def TCIRCBot_Box[T] = new IRCBot[IRCBot.Box[T]] {
    def name(bot : IRCBot.Box[T]) = {bot match {case bBox@IRCBot.Box(t) => bBox.tcInst.name(t) } }
    def join(bot : IRCBot.Box[T], chan : Channel) {bot match {case bBox@IRCBot.Box(t) => bBox.tcInst.join(t, chan)}}
  }

  /// type class impl. for PircBot
  implicit object TCIRCBot_PircBot extends IRCBot[org.jibble.pircbot.PircBot] {
    def name(bot : org.jibble.pircbot.PircBot) = bot.getNick
    def join(bot : org.jibble.pircbot.PircBot, chan : Channel) {bot.joinChannel(chan.name)}
  }

}


// wrapper for a chat message. This can be a message inside a channel,
// or a private message
case class ChatMessage(content : String,
                       sender : String,
                       receiver : IRCBot.Box[_]
                       // date : String
)

// type class for anything that can handle a message
trait MessageHandler[T] {

  def handle(self : T, message : ChatMessage)

}


/// some common type class impl. for MessageHandler
object MessageHandler {

  /// Box for anything that has a MessageHandler type class. Use for lists and such.
  case class Box[T](t:T)(implicit val tcInst : MessageHandler[T])


  /// ensure simple functions can be passed as a message handler
  implicit object TCMessageHandler_FunctionStringUnit extends MessageHandler[(String) => Unit] {
    def handle(self : (String) => Unit, msg : ChatMessage) {self(msg.content)}
  }

  implicit object TCMessageHandler_FunctionChatMessageUnit extends MessageHandler[(ChatMessage) => Unit] {
    def handle(self : (ChatMessage) => Unit, msg : ChatMessage) {self(msg)}
  }

}


/// Some message handler implementations that do different things
/// based on what the message says
object MessageHandlers {

  // this handler will make sure the bot joins the requested channel,
  // so that downloads won't be stopped/rejected
  def handleJoinRequestMessage(msg : ChatMessage) {



  }

}
