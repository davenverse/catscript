package io.chrisdavenport.catscript

import cats.syntax.all._
import cats.effect._
// import scala.concurrent.duration._
import java.nio.file.Paths
import scala.sys.process
import scala.sys.process.ProcessLogger
import cats.ApplicativeThrow

object Main extends IOApp {
  val console = cats.effect.std.Console.make[IO]

  def run(args: List[String]): IO[ExitCode] = {
    for {
      args <- Arguments.fromBaseArgs[IO](args)
      path <- IO(Paths.get(args.file))
      fileContent <- fs2.io.file.Files[IO].readAll(path, 512)
        .through(fs2.text.utf8Decode)
        .compile
        .string
      parsed <- Parser.simpleParser(fileContent).liftTo[IO]
      config = Config.configFromHeaders(parsed._1)
      _ <- fs2.io.file.Files[IO].tempDirectory().use(tempFolder => 
        Files.createInFolder(tempFolder, config, parsed._2) >>
        Files.stageExecutable(tempFolder) >>
        Files.executeExecutable(tempFolder, args.scriptArgs)
      )      
    } yield ExitCode.Success
  }
}

case class Arguments(catsScriptArgs: List[String], file: String, scriptArgs: List[String])
object Arguments {
  // [-options] file [script options]
  // All options for catscript MUST start with -
  // The first argument without - will be interpreted as the file
  def fromBaseArgs[F[_]: ApplicativeThrow](args: List[String]): F[Arguments] = {
    if (args.isEmpty) new RuntimeException("No Arguments Provided - Valid File is Required").raiseError
    else {
      val split = args.takeWhile(_.startsWith("-"))
      val list = args.dropWhile(_.startsWith("-"))
      val nelO = list.toNel
      nelO match {
        case None => new RuntimeException(s"Initial Arguments Provided: $split - Valid File is Required").raiseError
        case Some(value) => 
          val file = value.head
          val otherArgs = value.tail
          Arguments(split, file, otherArgs).pure[F]
      }
    }
  }
}

// Optional #!
// Uninterrupted section of comments broken by newlines
// Rest is the body
object Parser {
  def simpleParser(inputText: String): Either[Throwable, (List[(String, String)], String)] = Either.catchNonFatal{
    val text = {
      val base = inputText
      if (base.startsWith("#!")) {
        val idx = base.indexOf("\n")+1
        val out = base.substring(idx)
        out
      } else base
    }
    val lines = fs2.Stream(text)
      .through(fs2.text.lines)
    val headersLines = lines
      .takeWhile(_.startsWith("//"))
      .filter(x => x.contains(":")) // Comments are allowed that dont follow x:z
      .compile
      .to(List)
    val headers = headersLines.map{
      s =>
        val idx = s.indexOf(":") // :space is required in for a valid header
        val header = s.slice(2, idx).trim()
        val value = s.slice(idx + 1, s.length())
        (header, value)
    }
    val restText = lines
      .dropWhile(_.startsWith("//"))
      .intersperse("\n")
      .compile
      .string
    (headers, restText)
  }


}

case class Config(
  scala: String,
  sbt: String,
  interpreter: Config.Interpreter,
  name: String,
  dependencies: List[String],
  scalacOptions: List[String],
  compilerPlugins: List[String],
  sbtPlugins: List[String]
)
object Config {
  sealed trait Interpreter
  object Interpreter {
    case object Raw extends Interpreter
    case object App extends Interpreter
    case object IOApp extends Interpreter
    case object IOAppSimple extends Interpreter
    def fromString(s: String): Option[Interpreter] = s match {
      case "IOApp.Simple" => IOAppSimple.some
      case "IOAppSimple" => IOAppSimple.some
      case "ioapp.simple" => IOAppSimple.some
      case "ioappsimple" => IOAppSimple.some
      case "IOApp" => IOApp.some
      case "ioapp" => IOApp.some
      case "App" => App.some
      case "app" => App.some
      case "Raw" => Raw.some
      case "raw" => Raw.some
      case _ => None
    }
  }

  // TODO Interpreter
  def configFromHeaders(headers: List[(String, String)]): Config = {
    val scala = headers.findLast(_._1.toLowerCase() === "scala").map(_._2.trim()).getOrElse("2.13.5")
    val sbt = headers.findLast(_._1.toLowerCase() === "sbt").map(_._2.trim()).getOrElse("1.5.0")
    val interpreter = headers.findLast(_._1.toLowerCase() === "interpreter")
      .map(_._2.trim())
      .flatMap(Config.Interpreter.fromString)
      .getOrElse(Config.Interpreter.IOAppSimple)
    val name = headers.findLast(_._1.toLowerCase() === "name").map(_._2.trim()).getOrElse("Example Script")
    val dependencies = 
      headers.filter(_._1.toLowerCase() == "dependency").map(_._2.trim())
    val scalacOptions = headers.filter(_._1.toLowerCase() == "scalac").map(_._2.trim())
    val compilerPlugins = headers.filter(_._1.toLowerCase() == "compiler-plugin").map(_._2.trim())
    val sbtPlugins = headers.filter(_._1.toLowerCase() == "sbt-plugin").map(_._2.trim())
    Config(scala, sbt, interpreter, name, dependencies, scalacOptions, compilerPlugins, sbtPlugins)
  }
}

object Files {

  // build.sbt
  def buildFile(config: Config): String = {
    val fs2 = """libraryDependencies += "co.fs2" %% "fs2-io" % "3.0.1""""
    val fs2Maybe = config.interpreter match {
      case Config.Interpreter.IOApp => fs2
      case Config.Interpreter.IOAppSimple => fs2
      case Config.Interpreter.App => ""
      case Config.Interpreter.Raw => ""
    }
    s"""
    |scalaVersion := "${config.scala}"
    |name := "script"
    |enablePlugins(JavaAppPackaging)
    |
    |${config.scalacOptions.map(s => s"scalacOptions += $s").intercalate("\n")}
    |${config.compilerPlugins.map(s => s"addCompilerPlugin($s)").intercalate("\n")}
    |$fs2Maybe
    |${config.dependencies.map(s => s"libraryDependencies += $s").intercalate("\n")}
    |""".stripMargin
  }
  // project/build.properties
  def buildProperties(config: Config) = s"sbt.version=${config.sbt}\n"

  // project/plugins.sbt
  def pluginsFile(config: Config) =
    s"""addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.6") // Used for script for args passing
    |
    |${config.sbtPlugins.map(s => s"addSbtPlugin($s)").intercalate("\n")}
    |""".stripMargin

  def main(config: Config, script: String): String = {
    val nameAsClass = config.name.replaceAll("\\s", "")
    config.interpreter match {
      case Config.Interpreter.IOAppSimple => 
        s"""
        |object $nameAsClass extends cats.effect.IOApp.Simple {
        |$script
        |}
        |""".stripMargin
      case Config.Interpreter.IOApp => 
        s"""object $nameAsClass extends cats.effect.IOApp {
        |$script
        |}
        |""".stripMargin
      case Config.Interpreter.App => 
          s"""object $nameAsClass {
          |def main(args: Array[String]): Unit = {
          |$script
          |} 
          |}
          |""".stripMargin
      case Config.Interpreter.Raw => script
    }
  }

  def writeFile(file: java.nio.file.Path, text: String): IO[Unit] = {
    fs2.Stream(text)
    .covary[IO]
    .through(fs2.text.utf8Encode)
    .through(
      fs2.io.file.Files[IO].writeAll(file) //List(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    ).compile.drain
  }

  def createInFolder(sbtFolder: java.nio.file.Path, config: Config, script: String): IO[Unit] = {
    val files = fs2.io.file.Files[IO]
    val buildFile = Files.buildFile(config)
    val buildProperties = Files.buildProperties(config)
    val plugins = Files.pluginsFile(config)
    val main = Files.main(config, script)
    for {
      _ <- files.exists(sbtFolder).ifM(
        IO.unit,
        files.createDirectory(sbtFolder)
      )
      _ <- writeFile(Paths.get(sbtFolder.toString(), "build.sbt"), buildFile)

      project = Paths.get(sbtFolder.toString(), "project")
      _ <- files.exists(project).ifM(
        IO.unit, 
        files.createDirectory(project)
      )
      _ <- writeFile(Paths.get(sbtFolder.toString(), "project", "build.properties"), buildProperties)
      _ <- writeFile(Paths.get(sbtFolder.toString(), "project", "plugins.sbt"), plugins)

      scala = Paths.get(sbtFolder.toString(), "src", "main", "scala")
      _ <- files.exists(scala).ifM(
        IO.unit,
        files.createDirectories(scala)
      )
      _ <- writeFile(Paths.get(sbtFolder.toString(), "src", "main", "scala", "script.scala"), main)
    } yield ()
  }

  def stageExecutable(sbtFolder: java.nio.file.Path): IO[Unit] = {
    val so = new scala.collection.mutable.ListBuffer[String]
    val logger = ProcessLogger(s => so.addOne(s), e => so.addOne(e))
    val stage = Resource.make(IO(process.Process(s"sbt stage", sbtFolder.toFile()).run(logger)))(s => IO(s.destroy())).use(i => IO(i.exitValue()))
    stage.flatMap{
      case 0 => IO.unit
      case _ => 
        val standardOut = so.toList
        standardOut.traverse_[IO, Unit](s => 
          cats.effect.std.Console.make[IO].println(s)
        ) >> IO.raiseError(new RuntimeException("sbt staging failed") with scala.util.control.NoStackTrace)
    }
  }

  def executeExecutable(sbtFolder: java.nio.file.Path, scriptArgs: List[String]): IO[Unit] = {
    def execute = Resource.make(IO(process.Process(s"$sbtFolder/target/universal/stage/bin/script ${scriptArgs.mkString(" ")}").run()))(s => IO(s.destroy())).use(i => IO(i.exitValue()))
    execute.void
  }
}