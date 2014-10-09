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

  override def preStart() {
    println("starting download")
    transfer.receive(file, true);
    Main.system.scheduler.schedule(0.seconds, 5.second, self, PrintStatus)(Main.system.dispatcher)
  }

  def receive = {
    case PrintStatus => println(f"$file%s: ${transfer.getProgressPercentage}%5.2f%% ${transfer.getTransferRate / 8192}%7dKib/s")
  }
}

class IrcServerConnection(server:String) extends Actor {


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
      context.actorOf(Props(classOf[Downloader], transfer))
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
  import context.system
  val commandServer = system.actorOf(Props(classOf[CommandServer], self))
  val ircServers = mutable.HashMap.empty[String, ActorRef].withDefault {
    server => system.actorOf(Props(classOf[IrcServerConnection], server))
  }

  def receive = {
    case download@Download(server, _, _, _) => ircServers(server) ! download
    //case join@Join(server, _) => ircServers(server) ! join
  }
}

class CommandClient(turboEel:ActorRef) extends Actor {
  import Tcp._
  def receive = {
    case Received(data) =>
      data.utf8String.split(" ").toList match {
        case "get" :: server :: channel :: botname :: pack :: Nil =>
          turboEel ! Download(server, channel, botname, pack)
        // TODO:
        // case "join" :: server :: channel :: Nil =>
        //   turboEel ! Join(server, channel)
        case _ => sender() ! Write(ByteString("unknown command or wrong number of arguments\n"))

      }
    case PeerClosed     => context stop self
  }
}

class CommandServer(turboEel:ActorRef) extends Actor {
  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", 3532))

  def receive = {
    case b @ Bound(localAddress) =>
    case CommandFailed(_: Bind) => context stop self
    case c @ Connected(remote, local) =>
      val handler = context.actorOf(Props(classOf[CommandClient], turboEel))
      val connection = sender()
      connection ! Register(handler)
  }
}

object Main extends App {

  var eventHandlers : List[EventHandler.Box[_]] = List.empty

  val system = ActorSystem("turboeel")
  val turboEel = system.actorOf(Props[TurboEel])

  /// Add a Event handler that simply prints out any chat message
  eventHandlers :+= EventHandler.Box(
    (event : Event) => event match {
      case Event.Chat(content, sender, receiver) => println(s"Received message from ${sender} to bot ${receiver.name}: ${content}")
      case _ =>
  })
  /// Add some more message handlers
  eventHandlers :+= EventHandler.Box(PredefEventHandlers.handleJoinRequestMessage _)

  //turboEel ! Download("irc.freenode.net","#testchannel2", "joreji", "10")

  sys addShutdownHook {
    println("Shutdown Hook!")
  }
}
