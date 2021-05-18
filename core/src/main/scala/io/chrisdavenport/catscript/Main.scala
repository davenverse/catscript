package io.chrisdavenport.catscript

import cats._
import cats.syntax.all._
import cats.effect._
// import scala.concurrent.duration._
import java.nio.file.Paths
import scala.sys.process
import scala.sys.process.ProcessLogger
import cats.ApplicativeThrow
import java.nio.file.Path
import scodec.bits.ByteVector
import scala.util.control.NoStackTrace

object Main extends IOApp {
  val console = cats.effect.std.Console.make[IO]

  def run(args: List[String]): IO[ExitCode] = {
    for {
      args <- Arguments.fromBaseArgs[IO](args)
      _ <- if (args.verbose) console.println(s"Interpreted: $args") else IO.unit
      command = Command.fromArgs(args)
      _ <- if (args.verbose) console.println(s"Found Command: $command") else IO.unit
      _ <- Command.app[IO](command, args)
    } yield ExitCode.Success
  }
}
sealed trait Command
object Command {
  case object ClearCache extends Command
  case object Script extends Command
  case object Help extends Command

  def fromArgs(args: Arguments): Command = args.fileOrCommand match {
    case "clear-cache" => ClearCache
    case "help" => Help
    case _ => 
      if (args.catsScriptArgs.exists(_ === "-h") || args.catsScriptArgs.exists(_ === "--help")) Help
      else Script
  }

  def app[F[_]: Async](command: Command, args: Arguments): F[Unit] = {
    val console = cats.effect.std.Console.make[F]
    command match {
    case ClearCache => Cache.clearCache(args.verbose)
    case Help => 
      cats.effect.std.Console.make[F].println{
        """catscript: catscript [--no-cache] [--compile-only] [--verbose] (file | help | clear-cache) [script-args]
        |Cats Scripting
        |
        |Options:
        | --no-cache: Bypasses caching mechanism creating full project each run
        | --compile-only: Does not run script, just compiles and caches stdout: cache-location4
        | --sbt-output: Puts the produced sbt project in this location rather than a temporary directory
        | --verbose: Verbose
        |
        |Commands:
        | help: Display this help text
        | clear-cache: Clears all cached artifacts
        | file: Script to run
        |""".stripMargin
      }
    case Script => for {
      filePath <- Sync[F].delay(Paths.get(args.fileOrCommand))
      fileContent <- fs2.io.file.Files[F].readAll(filePath, 512)
        .through(fs2.text.utf8Decode)
        .compile
        .string
      parsed <- Parser.simpleParser(fileContent).liftTo[F]
      config = Config.configFromHeaders(parsed._1)
      _ <- if (args.verbose) console.println(s"Config Loaded: $config") else Applicative[F].unit
      cacheStrategy <- Cache.determineCacheStrategy[F](args, filePath, fileContent)
      _ <- if (args.verbose) console.println(s"Cache Strategy: $cacheStrategy") else Applicative[F].unit
      _ <- cacheStrategy match {
        case Cache.NoCache => 
          args.sbtOutput.fold(fs2.io.file.Files[F].tempDirectory())(path => 
            Resource.eval(Sync[F].delay(Paths.get(path)))
          ).use{tempFolder =>
            {
              if (args.verbose) console.println(s"SBT Project Output: $tempFolder") 
              else Applicative[F].unit
            } >>
              Files.createInFolder(tempFolder, config, parsed._2, args.sbtFile.map(Paths.get(_))) >>
            Files.stageExecutable(tempFolder) >> {
              if (args.compileOnly) Applicative[F].unit
              else
                Files.executeExecutable(
                  tempFolder.resolve("target").resolve("universal").resolve("stage"),
                  args.scriptArgs
                )
            }
          }
        case Cache.ReuseExecutable(cachedExecutableDirectory) => 
          if (args.compileOnly) console.println(cachedExecutableDirectory)
          else Files.executeExecutable(cachedExecutableDirectory, args.scriptArgs)
        case Cache.NewCachedValue(cachedExecutableDirectory, fileContentSha) => 
          args.sbtOutput.fold(fs2.io.file.Files[F].tempDirectory())(path => 
            Resource.eval(Sync[F].delay(Paths.get(path)))
          ).use{tempFolder => 
            val stageDir = tempFolder.resolve("target").resolve("universal").resolve("stage")

            {
              if (args.verbose) console.println(s"SBT Project Output: $tempFolder") 
              else Applicative[F].unit
            } >>
              Files.createInFolder(tempFolder, config, parsed._2, args.sbtFile.map(Paths.get(_))) >>
            Files.stageExecutable[F](tempFolder) >>
            fs2.Stream(fileContentSha).through(fs2.text.utf8Encode)
              .through(fs2.io.file.Files[F].writeAll(stageDir.resolve("script_sha")))
              .compile
              .drain >>
            fs2.io.file.Files[F].exists(cachedExecutableDirectory).ifM(
              fs2.io.file.Files[F].deleteDirectoryRecursively(cachedExecutableDirectory),
              Applicative[F].unit
            ) >>
            fs2.io.file.Files[F].createDirectories(cachedExecutableDirectory.getParent()) >>
            fs2.io.file.Files[F].move(stageDir, cachedExecutableDirectory) >> {
              if (args.compileOnly) console.println(cachedExecutableDirectory)
              else 
                Files.executeExecutable(
                  cachedExecutableDirectory,
                  args.scriptArgs
                )
            }
          }
        }
      } yield ()
    }
  }
}

case class Arguments(catsScriptArgs: List[String], fileOrCommand: String, scriptArgs: List[String]){
  val verbose: Boolean = catsScriptArgs.exists(_ == "--verbose")
  val noCache: Boolean = catsScriptArgs.exists(_ == "--no-cache")
  val compileOnly: Boolean = catsScriptArgs.exists(_ == "--compile-only")
  private val sbtOutputPattern: scala.util.matching.Regex = "--sbt-output=(.*)".r
  val sbtOutput: Option[String] = catsScriptArgs.collectFirstSome(s => sbtOutputPattern.findFirstMatchIn(s).map(_.group(1)))
  private val sbtBuildFilePattern: scala.util.matching.Regex = "--sbt-file=(.*)".r
  val sbtFile: Option[String] = catsScriptArgs.collectFirstSome(s => sbtBuildFilePattern.findFirstMatchIn(s).map(_.group(1)))

  override def toString: String = s"Arguments(catscriptArgs=$catsScriptArgs, fileOrCommand=$fileOrCommand, scriptArgs=$scriptArgs)"
}
object Arguments {
  // [-options] file [script options]
  // All options for catscript MUST start with -
  // The first argument without - will be interpreted as the file
  def fromBaseArgs[F[_]: ApplicativeThrow](args: List[String]): F[Arguments] = {
    if (args.isEmpty) new RuntimeException("No Arguments Provided - Valid File is Required").raiseError
    else {
      val split = args.takeWhile(_.startsWith("-")).flatMap(s => s.split(" ").toList)
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
    |${config.scalacOptions.map(s => s"""scalacOptions += "$s" """).intercalate("\n")}
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
        s"""object $nameAsClass extends cats.effect.IOApp.Simple {
        |$script
        |}
        |""".stripMargin
      case Config.Interpreter.IOApp => 
        s"""object $nameAsClass extends cats.effect.IOApp {
        |$script
        |}
        |""".stripMargin
      case Config.Interpreter.App => 
          s"""object $nameAsClass {;def main(args: Array[String]): Unit = {
          |$script
          |} 
          |}
          |""".stripMargin
      case Config.Interpreter.Raw => script
    }
  }

  def writeFile[F[_]: Async](file: java.nio.file.Path, text: String): F[Unit] = {
    fs2.io.file.Files[F].deleteIfExists(file) >>
    fs2.Stream(text)
    .covary[F]
    .through(fs2.text.utf8Encode)
    .through(
      fs2.io.file.Files[F].writeAll(file) //List(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    ).compile.drain
  }

  def copyFile[F[_]: Async](from: java.nio.file.Path, to: java.nio.file.Path): F[Unit] = {
    fs2.io.file.Files[F].deleteIfExists(to) >>
      fs2.io.file.Files[F].copy(from, to)
        .void
  }
  def createInFolder[F[_]: Async](sbtFolder: java.nio.file.Path, config: Config, script: String, buildFilePath: Option[Path]): F[Unit] = {
    val files = fs2.io.file.Files[F]
    val buildFile = Files.buildFile(config)
    val buildProperties = Files.buildProperties(config)
    val plugins = Files.pluginsFile(config)
    val main = Files.main(config, script)
    val sbtBuildPath = Paths.get(sbtFolder.toString, "build.sbt")
    for {
      _ <- files.exists(sbtFolder).ifM(
        Applicative[F].unit,
        files.createDirectory(sbtFolder).void
      )
      _ <- buildFilePath match {
        case Some(p) => files.exists(p).ifM(
          copyFile(p, sbtBuildPath),
          (new RuntimeException("existing build.sbt file specified to use does not exist") with scala.util.control.NoStackTrace).raiseError
        )
        case None => writeFile(sbtBuildPath, buildFile)
      }
      project = Paths.get(sbtFolder.toString(), "project")
      _ <- files.exists(project).ifM(
        Applicative[F].unit, 
        files.createDirectory(project).void
      )
      _ <- writeFile(Paths.get(sbtFolder.toString(), "project", "build.properties"), buildProperties)
      _ <- writeFile(Paths.get(sbtFolder.toString(), "project", "plugins.sbt"), plugins)

      scala = Paths.get(sbtFolder.toString(), "src", "main", "scala")
      _ <- files.exists(scala).ifM(
        Applicative[F].unit,
        files.createDirectories(scala).void
      )
      _ <- writeFile(Paths.get(sbtFolder.toString(), "src", "main", "scala", "script.scala"), main)
    } yield ()
  }

  def stageExecutable[F[_]: Async](sbtFolder: java.nio.file.Path): F[Unit] = {
    val soCompile = new scala.collection.mutable.ListBuffer[String]
    val loggerCompile = ProcessLogger(s => soCompile.addOne(s), e => soCompile.addOne(e))
    val compile = Resource.make(Sync[F].delay(process.Process(s"sbt compile", sbtFolder.toFile()).run(loggerCompile)))(s => Sync[F].delay(s.destroy())).use(i => Async[F].delay(i.exitValue()))
    
    val soStage = new scala.collection.mutable.ListBuffer[String]
    val loggerStage = ProcessLogger(s => soStage.addOne(s), e => soStage.addOne(e))
    val stage = Resource.make(Sync[F].delay(process.Process(s"sbt stage", sbtFolder.toFile()).run(loggerStage)))(s => Sync[F].delay(s.destroy())).use(i => Async[F].delay(i.exitValue()))

    compile.flatMap{
      case 0 => stage.flatMap{
        case 0 => Applicative[F].unit
        case _ => 
          val standardOut = soStage.toList
          standardOut.traverse_[F, Unit](s => 
            cats.effect.std.Console.make[F].println(s)
          ) >> (new RuntimeException("sbt stage failed, is enablePlugins(JavaAppPackaging) in your build.sbt file?") with scala.util.control.NoStackTrace).raiseError
      }
      case _ => 
        val standardOut = soCompile.toList
        standardOut.traverse_[F, Unit](s => 
          cats.effect.std.Console.make[F].println(s)
        ) >> (new RuntimeException("sbt compile failed") with scala.util.control.NoStackTrace).raiseError
    }
  }

  def executeExecutable[F[_]: Async](stageDirectory: java.nio.file.Path, scriptArgs: List[String]): F[Unit] = {
    val p = stageDirectory.resolve("bin").resolve("script")
    def execute = Resource.make(Sync[F].delay(process.Process(s"${p.toString} ${scriptArgs.mkString(" ")}").run()))(s => Sync[F].delay(s.destroy())).use(i => Sync[F].delay(i.exitValue()))
    execute.void
  }
}

object Cache {
  // Cache Protocol
  // --nocache disables caching entirely, run only in temp
  // Cache Location determined by Operating System Specific if unable to determine falls back to nocache
  // TODO Cache Flag, Environment Variable CATSCRIPT_CACHE
  // Provided file is resolved to absolute location // TODO including resolving symlinks
  // That absolute path is hashed through SHA-1 (this means we have 1 cache per file)
  // We check the SHA-1 of the script body to `script_sha`, 
  // if they match then the current executable present is reused
  // otherwise create the temp directory create the project
  // then copy the staged output into the location and write the current SHA-1 to script_sha
  sealed trait CacheStrategy extends Product with Serializable
  case object NoCache extends CacheStrategy
  case class ReuseExecutable(cachedExecutableDirectory: Path) extends CacheStrategy
  case class NewCachedValue(cachedExecutableDirectory: Path, fileContentSha: String) extends CacheStrategy
  
  def determineCacheStrategy[F[_]: Async](args: Arguments, filePath: Path, fileContent: String): F[CacheStrategy] = {
    if (args.catsScriptArgs.exists(_ == "--no-cache")) NoCache.pure[F].widen
    else getOS.flatMap{
      case None => NoCache.pure[F].widen
      case Some(os) => cacheLocation(os).flatMap{ path => 
        import java.security.MessageDigest

        Sync[F].delay{
          val SHA1 = MessageDigest.getInstance("SHA-1")
          val absolute = filePath.toAbsolutePath().toString()
          val absolutePathSha = ByteVector.view(SHA1.digest(absolute.getBytes())).toHex
          path.resolve(absolutePathSha)
        }.flatMap{ cachedExecutableDirectory =>
          val shaFile = cachedExecutableDirectory.resolve("script_sha")
          fs2.io.file.Files[F].exists(shaFile).ifM(
            {
              for {
                scriptSha <- fs2.io.file.Files[F].readAll(shaFile, 4096).through(fs2.text.utf8Decode).compile.string
                testSha <- Sync[F].delay{
                  val SHA1 = MessageDigest.getInstance("SHA-1")
                  ByteVector.view(SHA1.digest(fileContent.getBytes())).toHex
                }
              } yield if (scriptSha === testSha) ReuseExecutable(cachedExecutableDirectory) else  NewCachedValue(cachedExecutableDirectory, testSha)
            },
            Sync[F].delay{
              val SHA1 = MessageDigest.getInstance("SHA-1")
              ByteVector.view(SHA1.digest(fileContent.getBytes())).toHex
            }.map(NewCachedValue(cachedExecutableDirectory, _)).widen[CacheStrategy]
          )
        }
      }
    } 
  }

  def clearCache[F[_]: Async](verbose: Boolean): F[Unit] = {
    val c = cats.effect.std.Console.make[F]
    getOS.flatMap{
      case None => 
        (new RuntimeException("clear-cache cannot determine OS") with NoStackTrace).raiseError[F, Unit]
      case Some(os) =>
        cacheLocation(os).flatMap{ case cacheDirectory => 
          fs2.io.file.Files[F].exists(cacheDirectory).ifM(
            fs2.io.file.Files[F].walk(cacheDirectory, 1).drop(1).evalMap(path =>
              fs2.io.file.Files[F].deleteDirectoryRecursively(path) >> 
              { if (verbose) c.println(s"Deleted: $path") else Applicative[F].unit }
            ).compile.drain,
            Applicative[F].unit
          )
        }
    }
  }

  sealed trait OS
  case object Linux extends OS
  case object OSX extends OS
  case object Windows extends OS
  case object Solaris extends OS

  // Can't determine OS, don't cache
  private def getOS[F[_]: Sync]: F[Option[OS]] = {
    Sync[F].delay(System.getProperty("os.name").toLowerCase).map{ 
      // Linux, Unix, Aix
      case linux if linux.contains("nux") || linux.contains("nix") || linux.contains("aix") => Linux.some
      case mac if mac.contains("mac") => OSX.some 
      case windows if windows.contains("win") => Windows.some
      case solaris if solaris.contains("sunos") => Solaris.some
      case _ => None
    }
  }

  // Mirroring Coursier Semantics
  // on Linux, ~/.cache/catscript/v0. This also applies to Linux-based CI environments, and FreeBSD too.
  // on OS X, ~/Library/Caches/Catscript/v0.
  // on Windows, %LOCALAPPDATA%\Catscript\Cache\v0, which, for user Chris, typically corresponds to C:\Users\Chris\AppData\Local\Catscript\Cache\v1.
  private def cacheLocation[F[_]: Sync](os: OS): F[java.nio.file.Path] = Sync[F].delay(os match {
    case Linux | Solaris => 
      val home = System.getProperty("user.home")
      Paths.get(s"$home/.cache/catscript/v0").toAbsolutePath
    case OSX =>
      val home = System.getProperty("user.home") 
      Paths.get(s"$home/Library/Caches/Catscript/v0").toAbsolutePath
    case Windows => 
      val home = System.getProperty("user.home") 
      Paths.get(home ++ """\Catscript\Cache\v0""").toAbsolutePath
  })

}