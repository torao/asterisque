package io.asterisque.tools.a9

import java.io.File

import net.sourceforge.argparse4j.ArgumentParsers
import net.sourceforge.argparse4j.impl.Arguments
import net.sourceforge.argparse4j.inf.{Argument, ArgumentParser, ArgumentParserException}
import org.slf4j.LoggerFactory

/**
  * {{{
  * java io.asterisque.tools.a9.Main [COMMAND] [OPTIONS*]
  * }}}
  */
object Main {
  private[this] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

  private[this] val PROG = "a9" // getClass.getName.dropRight(1)

  def main(args:Array[String]):Unit = {
    val parser = ArgumentParsers.newFor(PROG).build()
      .defaultHelp(true)
      .description("command-line management tool fot asterisque.")
    val subparsers = parser.addSubparsers()
      .dest("command")
      .title("commands")
      .description("valid management commands.")
      .help("management command")

    locally {
      val init = subparsers.addParser("init", true)
        .description("create and initialize node directory.")
        .help("create and initialize node directory")
      init.addArgument("-k", "--key")
      addForce(init)
      addSubject(init, "node01")
      addDays(init, 360)
      init.addArgument("dir")
        .required(true)
        .help("node directory to initialize")
    }

    val ca = subparsers.addParser("ca", true)
      .description("Local CA management commands")
      .help("sub-commands to maintain local CA")
    val caSubparsers = ca.addSubparsers()
      .dest("ca")
      .title("Local CA")
      .description("Local CA management commands")
      .help("sub-commands to maintain local CA")

    locally {
      val caInit = caSubparsers.addParser("init", true)
        .description("")
        .help("")
      addForce(caInit)
      addSubject(caInit, "ca")
      addDays(caInit, 360)
      caInit.addArgument("cadir")
        .required(true)
        .help("ca directory")
    }

    locally {
      val parser = caSubparsers.addParser("approve", true)
        .description("")
        .help("")
      addForce(parser)
      addDays(parser, 0).required(false)
      addSubject(parser, "node01").required(false)
      parser.addArgument("cadir")
        .required(true)
        .help("ca directory")
      parser.addArgument("node")
        .required(true)
        .help("node directory")
    }

    val ns = try {
      parser.parseArgs(args)
    } catch {
      case ex:ArgumentParserException =>
        parser.handleError(ex)
        System.exit(1).asInstanceOf[Nothing]
    }
    logger.debug(ns.toString)

    ns.getString("command") match {
      case "init" =>
        val force = ns.getBoolean("force")
        val key = Option(ns.getString("key")).map(f => new File(f))
        val dir = ns.getString("dir")
        val subject = ns.getString("subject")
        val days = ns.getInt("days")
        Commands.init(new File(dir), subject, days, key, force)
      case "ca" =>
        ns.getString("ca") match {
          case "init" =>
            val force = ns.getBoolean("force")
            val days = ns.getInt("days")
            val dir = ns.getString("cadir")
            val subject = ns.getString("subject")
            Commands.ca.init(new File(dir), subject, days, force)
          case "approve" =>
            val force = ns.getBoolean("force")
            val ca = ns.getString("cadir")
            val node = ns.getString("node")
            val subject = Option(ns.getString("subject"))
            val days = Option(ns.getInt("days")).map(_.intValue())
            Commands.ca.approve(new File(ca), new File(node), subject, days, force)
        }
    }
  }

  private[this] def addForce(parser:ArgumentParser):Argument = {
    parser.addArgument("-f", "--force")
      .action(Arguments.storeTrue())
      .help("Force overwrite if files exists.")
  }

  private[this] def addSubject(parser:ArgumentParser, cn:String):Argument = {
    parser.addArgument("-s", "--subject")
      .required(true)
      .help(s"The subject of certificate (e.g., /C=JP/ST=Tokyo/L=Sumida/O=asterisque/OU=dev/CN=$cn")
  }

  private[this] def addDays(parser:ArgumentParser, days:Int):Argument = {
    val daysArg = parser.addArgument("-d", "--days")
      .`type`(classOf[java.lang.Integer])
      .help("The number of days of certificate expiration")
    daysArg.getClass.getMethod("setDefault", classOf[Object]).invoke(daysArg, Integer.valueOf(days))
    daysArg
  }

}
