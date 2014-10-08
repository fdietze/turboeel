import akka.actor.{ ActorRef, ActorSystem, Props, Actor, Inbox }
import scala.concurrent.duration._
import org.jibble.pircbot._
import collection.mutable
import java.io.File

case class Connect(server: String)
case class Join(channel: String)
case class Download(server: String, channel:String, botname: String, pack: String)
case object Status

class Downloader(val transfer:DccFileTransfer) extends Actor {
  val file = transfer.getFile();

  override def preStart() {
    println("starting download")
    transfer.receive(file, true);
    Main.system.scheduler.schedule(0.seconds, 5.second, self, Status)(Main.system.dispatcher)
  }

  def receive = {
    case Status => println(f"$file%s: ${transfer.getProgressPercentage}%5.2f%% ${transfer.getTransferRate / 8192}%7dKib/s")
  }
}

class IrcServer extends Actor {
  val bot = new PircBot {

    override def onPrivateMessage(sender:String, login:String, hostname:String, message:String) {
      println(message)
    }
    override def onIncomingFileTransfer(transfer:DccFileTransfer) {
      context.actorOf(Props(classOf[Downloader], transfer))
    }
  }

  def receive = {
    case Connect(server) =>
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
            //   //TODO: case e:NickAlreadyInUseException => fullNick = nick + tries
            // }
        }
        tries += 1
      } while(!bot.isConnected && tries < 3)

    case Join(channel)   =>
      bot.joinChannel(channel)
      println(s"joining $channel")

    case Download(_, channel, botname, pack) =>
      self ! Join(channel)
      bot.sendMessage(botname, s"xdcc get #$pack")
  }
}

class TurboEel extends Actor {
  val servers = mutable.HashMap.empty[String, ActorRef].withDefault{
    server =>
      val ircServer = Main.system.actorOf(Props[IrcServer])
      ircServer ! Connect(server)
      ircServer
  }

  def receive = {
    case download@Download(server, _, _, _) =>
      servers(server) ! download
  }
}

object Main extends App {
  val system = ActorSystem("turboeel")
  val turboEel = system.actorOf(Props[TurboEel])

  turboEel ! Download("irc.freenode.net", "#hhhhuuuu", "botname", "168")

  sys addShutdownHook {
    println("Shutdown Hook!")
  }
}
