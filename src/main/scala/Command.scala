package turboeel

import akka.actor.{ ActorRef, ActorSystem, Props, Actor, Inbox }
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import java.net.InetSocketAddress

/// Parses data incoming through the socket. Use a program such as
/// netcat (e.g. echo "command" | netcat localhost 3532) to send commands.
///
/// Currently implemented commands are:
///
/// 1. get <server> <channel> <botname> <pack>
///
///    Will start downloading an xdcc packet from the specified bot
///    inside the channel at the server
class CommandClient(serverManager:ActorRef) extends Actor {
  import Tcp._
  import PircBotMessages._

  def receive = {
    // interpret lines as space separated commands
    case Received(data) =>
      data.utf8String.trim.split(" ").toList match {
        case "get" :: server :: channel :: botname :: pack :: Nil =>
          serverManager ! Download(server, channel, botname, pack)

        case "join" :: server :: channel :: Nil =>
          serverManager ! Join(server, channel)

        case "shutdown" :: Nil =>
          println("\nYou want me to leave? Okay, okay...")
          println("Waiting for the remaining threads to die...")
          context.system.registerOnTermination {
            println("ActorSystem is down. The Program should exit soon.")
          }
          context.system.shutdown()

        case m => sender ! Write(ByteString(s"Yo what? $m\n"))

      }

      // close connection after each command
      sender ! Close

    case PeerClosed     => context stop self
  }
}

class CommandServer(serverManager:ActorRef) extends Actor {
  import Tcp._
  import context.actorOf
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", 3532))

  def receive = {
    case b @ Bound(localAddress) =>
    case CommandFailed(_: Bind) => context stop self
    case c @ Connected(remote, local) =>
      val handler = actorOf(Props(classOf[CommandClient], serverManager),
        "commandclient-"+remote.getPort)
      sender ! Register(handler)
  }
}
