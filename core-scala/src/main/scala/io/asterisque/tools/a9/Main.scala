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
      addSubject(init, "node01", "node")
      addDays(init, 360, "node")
      init.addArgument("dir")
        .required(true)
        .help("node directory to initialize")
    }

    locally {
      val parser = subparsers.addParser("key", true)
        .description("Create a new private key.")
        .help("Create a new private key.")
      addForce(parser)
      parser.addArgument("key")
        .required(true)
        .help("the destination file for the newly created private key")
    }

    locally {
      val parser = subparsers.addParser("csr", true)
        .description("Create a new CSR from private key.")
        .help("Create a new CSR from private key.")
      addForce(parser)
      addSubject(parser, "node01", "node")
      addDays(parser, 360, "node")
      parser.addArgument("key")
        .required(true)
        .help("the private key used to create the CSR")
      parser.addArgument("csr")
        .required(true)
        .help("the destination file for the newly created CSR")
    }

    locally {
      val parser = subparsers.addParser("keystore", true)
        .description("")
        .help("")
      addForce(parser)
      parser.addArgument("keyy")
        .required(true)
        .help("private key")
      parser.addArgument("cert")
        .required(true)
        .help("certificate")
      parser.addArgument("cacert")
        .required(true)
        .help("CA certificate path")
      parser.addArgument("passphrase")
        .required(true)
        .help("passphrase")
      parser.addArgument("-a", "--alias")
        .required(false)
        .help("Alias that identifies the private key and certificate in the keystore.")
      parser.addArgument("key-store")
        .required(true)
        .metavar("keystore")
        .help("PKCS#12 keystore file that stores private keys and certificates.")
    }

    locally {
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
          .description("create a new local private CA")
          .help("create a new local private CA")
        addForce(caInit)
        addSubject(caInit, "ca.asterisque.io", "CA")
        addDays(caInit, 360, "CA")
        caInit.addArgument("cadir")
          .required(true)
          .help("the CA directory to initialize")
      }

      locally {
        val parser = caSubparsers.addParser("approve", true)
          .description("")
          .help("")
        addForce(parser)
        addDays(parser, 0, "node").required(false)
        addSubject(parser, "node01", "node").required(false)
        parser.addArgument("cadir")
          .required(true)
          .help("ca directory")
        parser.addArgument("csr")
          .required(true)
          .help("CSR file")
        parser.addArgument("cert")
          .required(true)
          .help("cert file")
      }
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
      case "key" =>
        val force = ns.getBoolean("force")
        val key = new File(ns.getString("key"))
        Commands.newKey(key, force)
      case "csr" =>
        val force = ns.getBoolean("force")
        val subject = ns.getString("subject")
        val days = ns.getInt("days")
        val key = new File(ns.getString("key"))
        val csr = new File(ns.getString("csr"))
        Commands.newCSR(key, csr, subject, days, force)
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
            val ca = new File(ns.getString("cadir"))
            val csr = new File(ns.getString("csr"))
            val cert = new File(ns.getString("cert"))
            val subject = Option(ns.getString("subject"))
            val days = Option(ns.getInt("days")).map(_.intValue())
            Commands.ca.approve(ca, csr, cert, subject, days, force)
        }
      case "keystore" =>
        ns.getString("key-store") match {
          case "put" =>
            val force = ns.getBoolean("force")
            val privatekey = new File(ns.getString("key"))
            val certificate = new File(ns.getString("cert"))
            val caCert = new File(ns.getString("cacert"))
            val keyStore = new File(ns.getString("key-store"))
            val alias = Option(ns.getString("alias")).getOrElse("")
            val passphrase = ns.getString("passphrase")
            Commands.keyStore.put(keyStore, privatekey, certificate, caCert, alias, passphrase, force)
        }
    }
  }

  private[this] def addForce(parser:ArgumentParser):Argument = {
    parser.addArgument("-f", "--force")
      .action(Arguments.storeTrue())
      .help("Force overwrite if files exists.")
  }

  private[this] def addSubject(parser:ArgumentParser, cn:String, target:String):Argument = {
    parser.addArgument("-s", "--subject")
      .required(true)
      .help(s"the subject of $target certificate (e.g., /C=JP/ST=Tokyo/L=Sumida/O=asterisque/OU=dev/CN=$cn")
  }

  private[this] def addDays(parser:ArgumentParser, days:Int, target:String):Argument = {
    val daysArg = parser.addArgument("-d", "--days")
      .`type`(classOf[java.lang.Integer])
      .help(s"the number of days of $target certificate expiration")
      .setDefault2(Integer.valueOf(days))
    daysArg
  }

  private[this] implicit class _Argument(arg:Argument) {
    def setDefault2(value:Object):Argument = {
      classOf[Argument].getMethod("setDefault", classOf[Object]).invoke(arg, value)
      arg
    }
  }

}
