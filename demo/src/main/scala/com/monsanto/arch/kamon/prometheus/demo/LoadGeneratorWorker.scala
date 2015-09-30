package com.monsanto.arch.kamon.prometheus.demo

import akka.actor.{Actor, ActorRef, Props}
import akka.io.IO
import spray.can.Http
import spray.http.{HttpResponse, Uri}
import spray.httpx.RequestBuilding.{Get, Post}

/** Does the real work of generating load.
  *
  * @param baseUri the base URI where the server is listening
  *
  * @author Daniel Solano Gómez
  */
class LoadGeneratorWorker(baseUri: Uri, master: ActorRef) extends Actor {
  import LoadGenerator.LoadType._
  import context.system

  override def receive = {
    case t: LoadGenerator.LoadType ⇒
      t match {
        case IncrementCounter(x) ⇒
          IO(Http) ! Post(baseUri.withPath(Uri.Path("/counter") / x.toString).toString())
        case GetRejection ⇒
          IO(Http) ! Post(baseUri.withPath(Uri.Path("/counter/2000")).toString())
        case GetTimeout ⇒
          IO(Http) ! Get(baseUri.withPath(Uri.Path("/timeout")).toString())
        case GetError ⇒
          IO(Http) ! Get(baseUri.withPath(Uri.Path("/error")).toString())
        case UpdateHistogram ⇒
          IO(Http) ! Post(baseUri.withPath(Uri.Path("/histogram")).toString())
        case UpdateMinMaxCounter ⇒
          IO(Http) ! Post(baseUri.withPath(Uri.Path("/min-max-counter")).toString())
      }
    case _: HttpResponse ⇒
      LoadGenerator.bomb()
      master ! LoadGenerator.Message.LoadFinished
  }
}

object LoadGeneratorWorker {
  def props(baseUri: Uri, master: ActorRef): Props = Props(new LoadGeneratorWorker(baseUri, master))
}
