/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka

import se.scalablesolutions.akka.comet.BootableCometActorService
import se.scalablesolutions.akka.remote.{RemoteNode,BootableRemoteActorService}
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.actor.{ActorRegistry,BootableActorLoaderService}

/**
 * The Akka Kernel. 
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Kernel extends Logging {
  import Config._

  // FIXME add API to shut server down gracefully
  @volatile private var hasBooted = false
  
  private val startTime = System.currentTimeMillis
  
  object Bundles extends BootableActorLoaderService with BootableRemoteActorService with BootableCometActorService

  def main(args: Array[String]) = boot

  /**
   * Boots up the Kernel. 
   */   
  def boot: Unit = boot(true)

  /**
   * Boots up the Kernel. 
   * If you pass in false as parameter then the Akka banner is not printed out.
   */   
  def boot(withBanner: Boolean): Unit = synchronized {
    if (!hasBooted) {
      if (withBanner) printBanner
      log.info("Starting Akka...")
      Bundles.onLoad
      Thread.currentThread.setContextClassLoader(getClass.getClassLoader)
      log.info("Akka started successfully")
      hasBooted = true
    }
  }

  // TODO document Kernel.shutdown
  def shutdown = synchronized {
    if (hasBooted) {
      log.info("Shutting down Akka...")
      Bundles.onUnload  
      log.info("Akka succesfully shut down")
    }
  }

  def startRemoteService = Bundles.startRemoteService

  private def printBanner = {
    log.info(
"""
==============================
          __    __
 _____  |  | _|  | _______
 \__  \ |  |/ /  |/ /\__  \
  / __ \|    <|    <  / __ \_
 (____  /__|_ \__|_ \(____  /
      \/     \/    \/     \/
""")
    log.info("     Running version %s", VERSION)
    log.info("==============================")
  }
}