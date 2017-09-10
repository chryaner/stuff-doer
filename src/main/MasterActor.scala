package main

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Terminated}
import utils.Configuration

import scala.collection.mutable.ArrayBuffer

/**
  * Created by igor on 10/05/17.
  */
object MasterActor {

  //TODO: Add an implementation to fill the map
  // Maybe each actor will decide on the actions it handles and will reply to the master when it is
  // up with the action it handles.
  val actionsToActors = Map.empty[String, ActorRef]

  def props(config: Configuration): Props = Props(new MasterActor(config))
}

class MasterActor(config: Configuration) extends Actor with ActorLogging {

  val watched = ArrayBuffer.empty[ActorRef]

  val dataBase = context.actorOf(DatabaseActor.props(config.actionsFilesPath, config.actionsFilePrfx), "main.DatabaseActor")
  watched += dataBase

  val webServer = context.actorOf(WebServerActor.props(config.hostname, config.portNum, dataBase),"main.WebServerActor")
  watched += webServer

  // Watch all the child actors.
  watched.foreach(context.watch)

  override def preStart(): Unit = {
    log.info("Starting Master Actor...")
  }

  override def postStop(): Unit = {
    log.info("Stopping Master Actor...")
  }

  override def receive: Receive = {
    case Terminated(ref) =>
      watched -= ref
      log.info(s"Actor: ${ref.path} died.")
      controlledTermination()
    case someMessage => log.warning(s"Got the following message for some reason: $someMessage")
  }

  /**
    * Terminate the actor safely if, no more actors to watch, or the webServer actor is stopped.
    */
  def controlledTermination(): Unit = {
    // If webserver or database actor is not alive stop all the other actors and stop the application.
    if (!watched.contains(webServer) || !watched.contains(dataBase)) watched.foreach(_ ! PoisonPill)
    if (watched.isEmpty) context.stop(self)
  }
}
