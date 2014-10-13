package turboeel

import akka.actor.{ ActorRef, ActorSystem, Props, Actor, Inbox }
import scala.concurrent.duration._
import org.jibble.pircbot._
import collection.mutable
import java.io.File
import akka.actor.Terminated

object Turboeel extends App {
  println("(running with one long slim leg.)")

  val system = ActorSystem("deep-ocean")
  val servermanager = system.actorOf(Props[ServerManager], "servermanager")

  sys addShutdownHook { system.shutdown() }
}


case class Download(server: String, channel:String, botname: String, pack: String)
case object PrintStatus
case class Join(server: String, channel:String)


class IrcServerConnection(val server:String) extends PircBotActor {
  import PircBotMessages._
  import context.actorOf

  /// Contains botnames that are allowed to send us data
  var dccReqestedBots = Set.empty[String]


  def receive:Receive = defaultBotActions orElse {
    case OnPrivateMessage(sender, login, hostname, message) =>
      println(s"$server: Private message from ${sender}:\n  ${message}")
      handleJoinRequestMessage(message)

    case OnTopic(channel, topic, setBy, date, changed) =>
      // println(s"$server: $channel Topic:\n  $topic")
      handleJoinRequestMessage(topic)

    case OnKick(channel, kickerNick, kickerLogin, kickerHostname, recipientNick, reason) =>
      println(s"$server: Kicked from $channel, reason: $reason")


    case OnIncomingFileTransfer(transfer) =>
      if(dccReqestedBots contains transfer.getNick)
        actorOf(Props(classOf[Downloader], transfer),
          "download-"+transfer.getFile.getName)
      else
        println(s"$server: Ignoring file transfer from ${transfer.getNick} as it was not requested (file is: ${transfer.getFile})")

    case Download(`server`, channel, botname, pack) =>
      dccReqestedBots += botname
      //TODO: watch downloaders per IrcServerConnection and manage queues, avoid duplicate downloads
      self ! JoinChannel(channel) //TODO: completely remove channel from request and only join on error message from bot?
      bot.sendMessage(botname, s"xdcc get #$pack")
  }


  // this handler will make sure the bot joins the requested channel,
  // so that downloads won't be stopped/rejected
  def handleJoinRequestMessage(message : String) {
    //TODO: avoid help channels
    val channelReg = "#[0-9A-Za-z_-]+".r
    // TODO: check whether invite is legit?
    channelReg.findAllIn(message).matchData foreach { m =>
      self ! JoinChannel(m.matched)
    }
  }
}


class Downloader(transfer:DccFileTransfer) extends Actor {
  import context.dispatcher
  val file = transfer.getFile();
  lazy val printStatusScheduler = context.system.scheduler.schedule(0.seconds, 5.second, self, PrintStatus)

  def receivedBytes : Long = {
    transfer.getProgress
  }

  def totalBytes : Long = {
    transfer.getSize
  }

  private[this] var _closed = false
  def close() {
    if(!isClosed) {
      _closed = true
      try{ transfer.close() } catch { case _:Throwable => }
      printStatusScheduler.cancel()
      println(s"$file: closed")
    }
  }
  def isClosed = _closed

  override def preStart() {
    transfer.receive(file, true);
    printStatusScheduler

    // start a watchdog
    context.actorOf(Props(classOf[Watchdog], scala.ref.WeakReference(this)), "watchdog")
  }

  /// Check transfer completeness and disable printStatusScheduler if done
  def checkStatus() {
    if(receivedBytes == totalBytes) {
      close()
    }
  }

  def receive = {
    case PrintStatus => {
      checkStatus()
      // TODO: correct speed calculation
      println(f"$file%s: ${transfer.getProgressPercentage}%5.2f%% ${transfer.getTransferRate / 8192}%7dKib/s")
    }
  }

  override def postStop(): Unit = {
    close()
  }
}



class ServerManager extends Actor {
  import context.actorOf
  import context.watch
  import PircBotMessages._

  val commandServer = actorOf(Props(classOf[CommandServer], self), "commandserver")

  // reusable connections
  var ircServers = mutable.HashMap.empty[String, ActorRef]

  def newConnection(server:String):ActorRef = watch(actorOf(Props(classOf[IrcServerConnection], server), server))


  def receive = {
    case download@Download(server, _, _, _) =>
      // only create a new connection if none exists yet
      ircServers.getOrElseUpdate(server, newConnection(server)) ! download

    case Join(server, channel) => ircServers(server) ! JoinChannel(channel)

    case Terminated(serverConnection) =>
      ircServers = ircServers.filter(_._2 != serverConnection)
  }
}
