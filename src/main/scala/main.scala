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
      Main.messageHandlers map {
        _ match {
          case mhb@MessageHandler.Box(t) => mhb.tcInst.handle(
            t,
            ChatMessage(
              content = message,
              sender = sender,
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
  }
}

class CommandClient(turboEel:ActorRef) extends Actor {
  import Tcp._
  def receive = {
    case Received(data) =>
      data.utf8String.split(" ").toList match {
        case "get" :: server :: channel :: botname :: pack :: Nil =>
          turboEel ! Download(server, channel, botname, pack)
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

  var messageHandlers : List[MessageHandler.Box[_]] = List.empty

  val system = ActorSystem("turboeel")
  val turboEel = system.actorOf(Props[TurboEel])

  /// Add a message handler that simply prints out
  messageHandlers :+= MessageHandler.Box((msg : ChatMessage) => {
                                           println(s"Received message from ${msg.sender} to bot ${msg.receiver.name}: ${msg.content}")
  })

  turboEel ! Download("irc.freenode.net", "#testchannel2", "joreji", "10")

  sys addShutdownHook {
    println("Shutdown Hook!")
  }
}
