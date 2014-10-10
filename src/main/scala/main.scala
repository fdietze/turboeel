import akka.actor.{ ActorRef, ActorSystem, Props, Actor, Inbox }
import scala.concurrent.duration._
import org.jibble.pircbot._
import collection.mutable
import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import java.net.InetSocketAddress
import java.io.File

case class Join(channel: String)
case class Download(server: String, channel:String, botname: String, pack: String)
case object PrintStatus

class Downloader(transfer:DccFileTransfer) extends Actor {
  val file = transfer.getFile();
  lazy val printClock = Main.system.scheduler.schedule(0.seconds, 5.second, self, PrintStatus)(Main.system.dispatcher)

  def receivedBytes : Long = {
    transfer.getProgress
  }

  def totalBytes : Long = {
    transfer.getSize
  }

  private[this] var _closed = false
  def close() {
    _closed = true
    transfer.close()
    printClock.cancel()
  }
  def isClosed = _closed

  override def preStart() {
    println("starting download")
    transfer.receive(file, true);
    printClock

    // start a watchdog
    context.actorOf(Props(classOf[Watchdog], scala.ref.WeakReference(this)))
  }

  /// Check transfer completeness and disable printClock if done
  def checkStatus() {
    if(receivedBytes == totalBytes) {
      close()
    }
  }

  def receive = {
    case PrintStatus => {
      checkStatus()
      println(f"$file%s: ${transfer.getProgressPercentage}%5.2f%% ${transfer.getTransferRate / 8192}%7dKib/s")
    }
  }
}

class IrcServerConnection(server:String) extends Actor {
  import context.actorOf

  val bot : PircBot = new PircBot {
    override def onPrivateMessage(sender:String, login:String, hostname:String, message:String) {
      Main.eventHandlers map {
        _ match {
          case mhb@EventHandler.Box(t) => mhb.tcInst.handle(
            t,
            Event.Chat(
              content = message,
              sender = sender,
              receiver = IRCBot.Box(bot)
              ))
        }
      }
    }
    /// Called whenever someone (including this bot) joins a channel
    override def onJoin(channel:String, sender: String, login: String, hostname: String) {
      Main.eventHandlers map {
        _ match {
          case mhb@EventHandler.Box(t) => mhb.tcInst.handle(
            t,
            Event.Join(
              channel = channel,
              who = sender,
              receiver =IRCBot.Box(bot)
              ))
        }
      }
    }
    /// Called whenever a topic is sent to the bot
    override def onTopic(channel:String, topic: String, setBy: String, date: Long, changed: Boolean) {
      Main.eventHandlers map {
        _ match {
          case mhb@EventHandler.Box(t) => mhb.tcInst.handle(
            t,
            Event.Topic(
              channel = channel,
              topic = topic,
              setBy = setBy,
              receiver = IRCBot.Box(bot)
              ))
        }
      }
    }
    override def onIncomingFileTransfer(transfer:DccFileTransfer) {
      actorOf(Props(classOf[Downloader], transfer), transfer.getFile.getName)
    }
  }

  override def preStart() {
    var tries = 0
    var nick = "eel"
    var fullNick = nick
    do {
      try {
        println(s"connecting to $server as $fullNick")
        bot.connect(server)
        bot.changeNick(fullNick)
      }
      catch {
        case exception:Throwable =>
          println(exception.getMessage)
          // exception match {
             //TODO: case e:NickAlreadyInUseException => fullNick = nick + tries
          // }
      }
      tries += 1
    } while(!bot.isConnected && tries < 3)
  }

  def receive = {
    case Join(channel)   =>
      bot.joinChannel(channel)
      println(s"joining $channel")

    case Download(_, channel, botname, pack) =>
      self ! Join(channel)
      bot.sendMessage(botname, s"xdcc get #$pack")
  }
}

class TurboEel extends Actor {
  import context.actorOf
  val commandServer = actorOf(Props(classOf[CommandServer], self), "commandserver")
  val ircServers = mutable.HashMap.empty[String, ActorRef]
  def newConnection(server:String) =
    actorOf(Props(classOf[IrcServerConnection], server), server)

  def receive = {
    case download@Download(server, _, _, _) =>
      ircServers.getOrElseUpdate(server, newConnection(server)) ! download
    //case join@Join(server, _) => ircServers(server) ! join
  }
}

/// Parses data incoming through the socket. Use a program such as
/// netcat (e.g. netcat -v localhost 3532) to send commands.
///
/// Currently implemented commands are:
///
/// 1. get <server> <channel> <botname> <pack>
///
///    Will start downloading an xdcc packet from the specified bot
///    inside the channel at the server
class CommandClient(turboEel:ActorRef) extends Actor {
  import Tcp._
  def receive = {
    case Received(data) =>
      data.utf8String.trim.split(" ").toList match {
        case "get" :: server :: channel :: botname :: pack :: Nil =>
          turboEel ! Download(server, channel, botname, pack)
        // TODO:
        // case "join" :: server :: channel :: Nil =>
        //   turboEel ! Join(server, channel)

        case "shutdown" :: Nil =>
          Main.shutdown()

        case m => sender() ! Write(ByteString(s"unknown command or wrong number of arguments: $m\n"))

      }
    case PeerClosed     => context stop self
  }
}

class CommandServer(turboEel:ActorRef) extends Actor {
  import Tcp._
  import context.actorOf
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", 3532))

  def receive = {
    case b @ Bound(localAddress) =>
    case CommandFailed(_: Bind) => context stop self
    case c @ Connected(remote, local) =>
      val handler = actorOf(Props(classOf[CommandClient], turboEel),"commandclient")
      val connection = sender()
      connection ! Register(handler)
  }
}

object Main extends App {

  var eventHandlers : List[EventHandler.Box[_]] = List.empty

  val system = ActorSystem("world")
  val turboEel = system.actorOf(Props[TurboEel], "turboeel")

  def shutdown() {
    println("shutting down...")
    system.shutdown()
  }

  /// Add a Event handler that simply prints out any chat message
  eventHandlers :+= EventHandler.Box(
    (event : Event) => event match {
      case Event.Chat(content, sender, receiver) => println(s"Received message from ${sender} to bot ${receiver.name}: ${content}")
      case _ =>
  })
  /// Add some more message handlers
  eventHandlers :+= EventHandler.Box(PredefEventHandlers.handleJoinRequestMessage _)

  //turboEel ! Download("irc.freenode.net","#testchannel2", "joreji", "10")

  sys addShutdownHook { shutdown() }
}
