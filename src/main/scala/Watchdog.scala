import akka.actor.{ Actor, ActorRef, Props }
import scala.concurrent.duration._
import scala.math.{min,max}

/// Watch a download. If its transfer speed goes zero, it will restart
/// the download
case object UpdateWatch

class Watchdog(download: scala.ref.WeakReference[Downloader]) extends Actor {

  var receivedBytesHist = List[Long]()
  val histSize = 5

  /// initialized in preStart
  lazy val updateClock = Main.system.scheduler.schedule(0.seconds, 5.second, self, UpdateWatch)(Main.system.dispatcher)

  override def preStart() {

    updateClock

  }

  /// IF the downloader is no longer active, stop watching it
  def checkDownloaderState() {
    download.get map { download =>
      if(download.isClosed) {
        updateClock.cancel()
      }
    }
    if(download.get == None)
      updateClock.cancel()
  }

  def receive = {

    case UpdateWatch => {
      checkDownloaderState()
      download.get map { download =>
        //println(s"Watching. Transmitted: ${download.receivedBytes}")
        receivedBytesHist :+= download.receivedBytes
        receivedBytesHist = receivedBytesHist.drop(max(0,receivedBytesHist.size - histSize))

        if(receivedBytesHist.size >= histSize
             && (receivedBytesHist.reduceLeft(min) == receivedBytesHist.reduceLeft(max))) {

          println("Cancelling download because it has stalled")
          download.close()

        }
      }
    }

  }

}
