import akka.actor.{ ActorRef, ActorSystem, Props, Actor, Inbox }
import scala.concurrent.duration._
import org.jibble.pircbot._
import collection.mutable
import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import java.net.InetSocketAddress
import java.io.File
import akka.actor.Terminated

case class Join(channel: String)
case class Download(server: String, channel:String, botname: String, pack: String)
case object PrintStatus

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
      println(f"$file%s: ${transfer.getProgressPercentage}%5.2f%% ${transfer.getTransferRate / 8192}%7dKib/s")
    }
  }

  override def postStop(): Unit = {
    close()
  }
}

class IrcServerConnection(server:String) extends Actor {
  import context.actorOf

  /// Contains botnames that are allowed to send us data
  var botnamesWhitelist = List[String]()

  val bot : PircBot = new PircBot {
    override def onPrivateMessage(sender:String, login:String, hostname:String, message:String) {
      Turboeel.dispatchEvent(Event.Chat(content = message,
                                    sender = sender,
                                    receiver = IRCBot.Box(bot)))
    }
    /// Called whenever someone (including this bot) joins a channel
    override def onJoin(channel:String, sender: String, login: String, hostname: String) {
      Turboeel.dispatchEvent(Event.Join(
                           channel = channel,
                           who = sender,
                           receiver =IRCBot.Box(bot)))
    }
    /// Called whenever a topic is sent to the bot
    override def onTopic(channel:String, topic: String, setBy: String, date: Long, changed: Boolean) {
      Turboeel.dispatchEvent(Event.Topic(
                           channel = channel,
                           topic = topic,
                           setBy = setBy,
                           receiver = IRCBot.Box(bot)))
    }

    override def onIncomingFileTransfer(transfer:DccFileTransfer) {
      Turboeel.dispatchEvent(Event.DccFileTransfer(transfer=transfer,receiver = IRCBot.Box(bot)))
      if(botnamesWhitelist contains transfer.getNick)
        actorOf(Props(classOf[Downloader], transfer), "download-"+transfer.getFile.getName)
      else
        println(s"Ignoring file transfer from ${transfer.getNick} as it was not requested (file is: ${transfer.getFile})")
    }

  }

  def isInChannel(channel:String) = bot.getChannels.map(_.toLowerCase) contains channel.toLowerCase

  override def preStart() {
    var tries = 0
    var nick = "eel"
    var fullNick = nick
    do {
      try {
        println(s"$server: connecting as $fullNick")
        bot.changeNick(fullNick)
        bot.connect(server)
        bot.changeNick(fullNick)
      }
      catch {
        case exception:Throwable =>
          println(s"$server: ${exception.getMessage}")
          // exception match {
             //TODO: case e:NickAlreadyInUseException => fullNick = nick + tries
          // }
      }
      tries += 1
    } while(!bot.isConnected && tries < 3)
    if(!bot.isConnected) {
      println(s"$server: sorry, couldn't connect")
      context stop self
    }
  }

  def receive = {
    case Join(channel)   =>
      if( !isInChannel(channel) ) {
        println(s"$server: joining $channel")
        bot.joinChannel(channel)
      }

    case Download(_, channel, botname, pack) =>
      botnamesWhitelist :+= botname
      //TODO: watch downloaders per IrcServerConnection and manage queues, avoid duplicate downloads
      self ! Join(channel) //TODO: completely remove channel from request and only join on error message from bot?
      bot.sendMessage(botname, s"xdcc get #$pack")
  }

  override def postStop(): Unit = {
    try{ bot.disconnect() } catch { case _:Throwable => }
    println(s"$server: disconnected")

    // free up any resources that the bot used (including threads)
    try{ bot.dispose() } catch { case _:Throwable => }
    println(s"$server: disposed")
  }
}

class ServerManager extends Actor {
  import context.actorOf
  import context.watch

  val commandServer = actorOf(Props(classOf[CommandServer], self), "commandserver")
  var ircServers = mutable.HashMap.empty[String, ActorRef]
  def newConnection(server:String):ActorRef = watch(actorOf(Props(classOf[IrcServerConnection], server), server))


  def receive = {
    case download@Download(server, _, _, _) =>
      ircServers.getOrElseUpdate(server, newConnection(server)) ! download
    case Terminated(serverConnection) =>
      ircServers = ircServers.filter(_._2 != serverConnection)
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
          println("\nYou want me to leave? Okay, okay...")
          println("Waiting for the remaining threads to die...")
          context.system.registerOnTermination {
            println("ActorSystem is down. The Program should exit soon.")
          }
          context.system.shutdown()

        case m => sender ! Write(ByteString(s"Yo what? $m\n"))

      }
      sender ! Close
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
      val handler = actorOf(Props(classOf[CommandClient], turboEel),"commandclient-"+remote.getPort)
      val connection = sender
      connection ! Register(handler)
  }
}

object Turboeel extends App {
  println("(running with one long slim leg.)")

  /// List containing EventHandler-like things that will be passed events
  var eventHandlers : List[EventHandler.Box[_]] = List.empty

  def dispatchEvent(event : Event) {
    eventHandlers map {_ match {case mhb@EventHandler.Box(t) => mhb.tcInst.handle(t, event)}}
  }

  val system = ActorSystem("world")
  val turboEel = system.actorOf(Props[ServerManager], "servermanager")

  /// Add a Event handler that simply prints out any chat message
  eventHandlers :+= EventHandler.Box(
    (event : Event) => event match {
      case Event.Chat(content, sender, receiver) => println(s"Received message from ${sender} to bot ${receiver.name}:\n${content}")
      case _ =>
  })
  /// Add some more message handlers
  eventHandlers :+= EventHandler.Box(PredefEventHandlers.handleJoinRequestMessage _)

  //turboEel ! Download("irc.freenode.net","#testchannel2", "joreji", "10")

  sys addShutdownHook { system.shutdown() }
}
