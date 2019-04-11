package io.asterisque.node

import java.io.File
import java.nio.file.StandardWatchEventKinds._
import java.nio.file.{FileSystems, Path, WatchEvent}
import java.util.concurrent.ConcurrentHashMap

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.joran.spi.JoranException
import ch.qos.logback.core.util.StatusPrinter
import io.asterisque.node.Context.{ConfigTransformer, logger}
import io.asterisque.security.TrustContext
import io.asterisque.utils
import io.asterisque.utils.{Cache, Config, KeyValueStore}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

class Context(root:File) extends AutoCloseable {
  log.init()

  logger.debug(s"initializing context on: $root")

  val cache = KeyValueStore(new File(root, ".cache"))

  def config():Config = conf.config.get(conf.dir)

  def trustContext():TrustContext = conf.trustContextCache.get(conf.dir)

  def close():Unit = {
    watcher.close()
    cache.close()
    logger.debug(s"context closed")
  }

  object log {
    def security(msg:String):Unit = {
      logger.info(s"[SECURITY] $msg")
    }

    private[Context] def init():Unit = {
      val logConfigFile = new File(conf.dir, "logback.xml")
      if(logConfigFile.isFile) {
        val context = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
        try {
          val configurator = new JoranConfigurator()
          configurator.setContext(context)
          configurator.doConfigure(logConfigFile)
        } catch {
          case ex:JoranException =>
            logger.error(s"fail to read log configuration: $logConfigFile", ex)
        }
        StatusPrinter.printInCaseOfErrorsOrWarnings(context)
      }
    }
  }

  private[this] object conf {
    val dir:File = new File(root, "conf")

    val config = new Cache[Config](ConfigTransformer)

    val trustContextCache = new Cache[TrustContext](new utils.Cache.DirTransformer[TrustContext]() {

      override def transform(files:Seq[File]):TrustContext = loadTrustContext()

      override def defaultValue(target:File):TrustContext = loadTrustContext()
    })

    private[this] def loadTrustContext():TrustContext = {
      val conf = Context.this.config()
      val alias = conf.getOrElse("keystore.alias", "")
      val passphrase = conf.getOrElse("keystore.passphrase", "")
      TrustContext(new File(dir, "security"), alias, passphrase)
    }

  }

  private[this] object watcher extends Thread("ContextWatcher") with AutoCloseable {
    private[this] val watcher = FileSystems.getDefault.newWatchService()
    private[this] val loaders = new ConcurrentHashMap[Path, File => Unit]()

    def register(dir:File)(init:File => Unit):Unit = {
      init(dir)
      loaders.put(dir.toPath, init)
      dir.toPath.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
      logger.debug(s"directory watcher service registered: $dir")
    }

    def close():Unit = {
      logger.debug(s"shutting-down directory watcher service")
      watcher.close()
    }

    override def run():Unit = try {
      while(true) {
        val watchKey = watcher.take()
        for(e <- watchKey.pollEvents().asScala) {
          logger.debug(s"WatcherEvent(kind=${e.kind()}, context=${e.context()}, count=${e.count()})")
          if(e.kind() != OVERFLOW) {
            val path = e.asInstanceOf[WatchEvent[Path]].context()
            logger.debug(s"file modification detected on directory: $path")
            loaders.get(path).apply(path.toFile)
          }
        }
        watchKey.reset()
      }
    } catch {
      case _:InterruptedException => None
    }
  }

}

object Context {
  private[Context] val logger = LoggerFactory.getLogger(classOf[Context])

  private[Context] object ConfigTransformer extends Cache.DirTransformer[Config](filter = _.getName.endsWith(".conf"), recurse = false) {
    override def defaultValue(target:File):Config = Config.getDefault

    override def transform(files:Seq[File]):Config = if(files.isEmpty) Config.getDefault else {
      val fs = files.sortBy(_.getName)
      Config(fs.head, fs.tail:_*)
    }
  }

}