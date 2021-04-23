package io.chrisdavenport.catscript

package io.chrisdavenport.superansi

import cats.syntax.all._
import cats.effect._
// import scala.concurrent.duration._
import java.nio.file.Paths
import scala.sys.process
import scala.sys.process.ProcessLogger

object Main extends IOApp {
  val console = cats.effect.std.Console.make[IO]
  def run(args: List[String]): IO[ExitCode] = {
    for {
      // _ <- IO(println(s"Args: $args"))
      path <- IO(Paths.get(args.last))
      fileContent <- fs2.io.file.Files[IO].readAll(path, 512)
        .through(fs2.text.utf8Decode)
        .compile
        .string
      parsed <- Parser.simpleParser(fileContent).liftTo[IO]
      config = Config.configFromHeaders(parsed._1)
      _ <- fs2.io.file.Files[IO].tempDirectory().use(tempFolder => 
        Files.createInFolder(tempFolder, config, parsed._2) >>
        Files.stageExecutable(tempFolder) >>
        Files.executeExecutable(tempFolder)
      )

      
    } yield ExitCode.Success
  }

}
// Optional #!
// Uninterrupted section of comments broken by newlines
// Terminated by /n/n or /r/n/r/n
// name/scala/sbt/interpreter/dependency headers defined
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
    // println(s"Text: $text")
    val (startText, restText) = {
      val idx = text.indexOf("\n\n")
      if (idx == -1) throw new Throwable("No Headers Found Require name/scala/interpreter")
      else text.splitAt(idx)
    }
    val headersLines = fs2.Stream(startText)
      .through(fs2.text.lines)
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
    (headers, restText.drop(2))
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
    case object IOApp extends Interpreter
    case object IOAppSimple extends Interpreter
    def fromString(s: String): Option[Interpreter] = s match {
      case "IOApp.Simple" => IOAppSimple.some
      case "IOAppSimple" => IOAppSimple.some
      case "ioapp.simple" => IOAppSimple.some
      case "ioappsimple" => IOAppSimple.some
      case "IOApp" => IOApp.some
      case "ioapp" => IOApp.some
      case _ => None
    }
  }

  // TODO Interpreter
  def configFromHeaders(headers: List[(String, String)]): Config = {
    val scala = headers.findLast(_._1.toLowerCase() === "scala").map(_._2.trim()).getOrElse("2.13.2")
    val sbt = headers.findLast(_._1.toLowerCase() === "sbt").map(_._2.trim()).getOrElse("1.5.0")
    val interpreter = headers.findLast(_._1.toLowerCase() === "interpreter")
      .map(_._2.trim())
      .flatMap(Config.Interpreter.fromString)
      .getOrElse(Config.Interpreter.IOAppSimple)
    val name = headers.findLast(_._1.toLowerCase() === "name").map(_._2.trim()).getOrElse("Example Script")
    val dependencies = 
      """"co.fs2" %% "fs2-io" % "3.0.1"""" ::
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
    s"""
    |scalaVersion := "${config.scala}"
    |name := "script"
    |enablePlugins(JavaAppPackaging)
    |
    |${config.scalacOptions.map(s => s"scalacOptions += $s").intercalate("\n")}
    |
    |${config.compilerPlugins.map(s => s"addCompilerPlugin($s)").intercalate("\n")}
    |
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

  def createInFolder(folder: java.nio.file.Path, config: Config, script: String): IO[Unit] = {
    val files = fs2.io.file.Files[IO]
    val buildFile = Files.buildFile(config)
    val buildProperties = Files.buildProperties(config)
    val plugins = Files.pluginsFile(config)
    val main = Files.main(config, script)
    for {
      _ <- files.exists(folder).ifM(
        IO.unit,
        files.createDirectory(folder)
      )
      _ <- writeFile(Paths.get(folder.toString(), "build.sbt"), buildFile)

      project = Paths.get(folder.toString(), "project")
      _ <- files.exists(project).ifM(
        IO.unit, 
        files.createDirectory(project)
      )
      _ <- writeFile(Paths.get(folder.toString(), "project", "build.properties"), buildProperties)
      _ <- writeFile(Paths.get(folder.toString(), "project", "plugins.sbt"), plugins)

      scala = Paths.get(folder.toString(), "src", "main", "scala")
      _ <- files.exists(scala).ifM(
        IO.unit,
        files.createDirectories(scala)
      )
      _ <- writeFile(Paths.get(folder.toString(), "src", "main", "scala", "script.scala"), main)
    } yield ()
  }

  def stageExecutable(sbtFolder: java.nio.file.Path): IO[Unit] = {
    val so = new scala.collection.mutable.ListBuffer[String]
    val logger = ProcessLogger(s => so.addOne(s), _ => ())
    val stage = Resource.make(IO(process.Process(s"sbt stage", sbtFolder.toFile()).run(logger)))(s => IO(s.destroy())).use(i => IO(i.exitValue()))
    stage.flatMap{
      case 0 => IO.unit
      case otherwise => IO.raiseError(new RuntimeException(s"SBT Staging failed ExitCode: $otherwise\nSBT StdErr: ${so.toList}"))
    }
  }

  def executeExecutable(sbtFolder: java.nio.file.Path): IO[Unit] = {
    def execute = Resource.make(IO(process.Process(s"$sbtFolder/target/universal/stage/bin/script").run()))(s => IO(s.destroy())).use(i => IO(i.exitValue()))
    execute.void
  }
}