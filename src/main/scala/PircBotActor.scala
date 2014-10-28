package turboeel

import akka.actor.{ ActorRef, ActorSystem, Props, Actor, Inbox }
import org.jibble.pircbot.{DccFileTransfer, PircBot}


object PircBotMessages {
  case class JoinChannel(channel: String)
  case class OnPrivateMessage(sender:String, login:String, hostname:String, message:String)
  case class OnJoin(channel:String, sender:String, login:String, hostname:String)
  case class OnTopic(channel:String, topic:String, setBy:String, date:Long, changed:Boolean)
  case class OnKick(channel:String, kickerNick:String, kickerLogin:String, kickerHostname:String, recipientNick:String, reason:String)
  case class OnIncomingFileTransfer(transfer:DccFileTransfer)
  case class OnNotice(sender:String, message:String)
}

trait PircBotActor extends Actor {
  import PircBotMessages._

  def server:String

  val bot : PircBot = new PircBot {
    /// This method is called whenever a private message is sent to the PircBot
    override def onPrivateMessage(sender:String, login:String, hostname:String, message:String) {
      self ! OnPrivateMessage(sender, login, hostname, message)
    }
    /// Called whenever someone (including this bot) joins a channel
    override def onJoin(channel:String, sender: String, login: String, hostname: String) {
      self ! OnJoin(channel, sender, login, hostname)
    }
    /// Called whenever a topic is sent to the bot
    override def onTopic(channel:String, topic: String, setBy: String, date: Long, changed: Boolean) {
      self ! OnTopic(channel, topic, setBy, date, changed)
    }
    /// This method is called whenever someone (possibly us) is kicked from any of the channels that we are in.
    override def onKick(channel:String, kickerNick:String, kickerLogin:String, kickerHostname:String, recipientNick:String, reason:String) {
      self ! OnKick(channel, kickerNick, kickerLogin, kickerHostname, recipientNick, reason)
    }
    /// This method is called whenever a DCC SEND request is sent to the PircBot
    override def onIncomingFileTransfer(transfer:DccFileTransfer) {
      self ! OnIncomingFileTransfer(transfer)
    }
    /// This method is called whenever a private message is sent to the PircBot
    override def onNotice(sender:String, login:String, hostname:String, target:String, message:String) {
      self ! OnNotice(sender, message)
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

  val defaultBotActions:Receive = {
    case JoinChannel(channel)   =>
      if( !isInChannel(channel) ) {
        // println("channels: " + bot.getChannels.mkString(", "))
        println(s"$server: joining $channel")
        bot.joinChannel(channel)
      }
  }

  override def postStop(): Unit = {
    try{ bot.disconnect() } catch { case _:Throwable => }
    println(s"$server: disconnected")

    // free up any resources that the bot used (including threads)
    try{ bot.dispose() } catch { case _:Throwable => }
    println(s"$server: disposed")
  }
}

