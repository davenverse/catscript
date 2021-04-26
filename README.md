# catscript - Cats Scripting [![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/catscript_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/catscript_2.12) ![Code of Consuct](https://img.shields.io/badge/Code%20of%20Conduct-Scala-blue.svg)



## Quick Start

- [Install Coursier if you don't already have it](https://get-coursier.io/docs/cli-installation.html#native-launcher)
- [Install SBT if you don't already have it](https://www.scala-sbt.org/release/docs/Installing-sbt-on-Mac.html) or via `cs install sbt-launcher`

```sh
# Coursier and SBT are Pre-requisites
cs install --channel https://git.io/JODPU catscript
# Longer Form 
cs install --channel https://gist.githubusercontent.com/ChristopherDavenport/82b779ed43f779d7db05d4382677bec5/raw/catscript.json catscript


# Manually
cs fetch io.chrisdavenport:catscript_2.13:latest.release
cs bootstrap io.chrisdavenport:catscript_2.13:latest.release -o catscript
# Catscript is now an executable in this directory, place to a location on $PATH


# Example Full Installation From Scratch
curl -fLo cs https://git.io/coursier-cli-"$(uname | tr LD ld)"
chmod +x cs
./cs install cs
cs update cs
cs install sbt-launcher
cs install --channel https://git.io/JODPU catscript
```

Then write apps as simply as

```scala
#!/usr/bin/env catscript
// interpreter: IOApp.Simple
// scala: 3.0.0-RC2
// dependency: "org.http4s" %% "http4s-ember-client" % "1.0.0-M21"

import cats.effect._
import cats.effect.std.Console

def run: IO[Unit] = Console[IO].println("Hello world!!!")
```

SheBangs are optional, but make it so you can execute the files, rather than invoke
the interpreter on the file, which I find useful.

### Available Interpreters

Defaults to `IOApp.Simple`. Headers section is terminated by the first line which does not start with `//` excluding the `#!`
#### IOApp.Simple

Scala and Interpreter can both be left absent, in which case they default to
`2.13.5` and `IOApp.Simple`.

```scala
#!/usr/bin/env catscript
import cats.effect._
import cats.effect.std.Console

def run: IO[Unit] = Console[IO].println("Hello world!!!")
```

#### IOApp

Args are whatever you invoke the file with and should work correctly.

```scala
#!./usr/bin/env catscript
// interpreter: IOApp
// scala: 3.0.0-RC2

import cats.effect._
import cats.effect.std.Console

def run(args: List[String]): IO[ExitCode] = 
  Console[IO].println(s"Received $args- Hello from IOApp")
    .as(ExitCode.Success)
```

#### App

Works like a worksheet, entire script is within `def main(args: Array[String]): Unit`

```scala
#!./usr/bin/env catscript
// interpreter: App
// scala: 3.0.0-RC2

println("Hi There!")
```

#### Raw

Takes your code and places it there with no enhancements, you're responsible
for initiating your own MainClass that has a `main`. Top-Level Declarations
depend on the version of scala whether or not those are allowed.

```scala
#!./usr/bin/env catscript
// interpreter: Raw
// scala: 3.0.0-RC2

object Main {
  def main(args: Array[String]): Unit = {
    println("Your code, as requested")
  }
}
```

### Script Headers

- `scala`: Sets the Scala Version, last header wins. `scala: 3.0.0-RC2`
- `sbt`: Sets the SBT Version, last header wins `sbt: 1.5.0`
- `interpreter`: Sets which interpreter to use, last header wins. `interpreter: IOApp.Simple`
- `dependency`: Repeating Header, allows you to set libraryDependencies for the script. `dependency: "org.http4s" %% "http4s-ember-client" % "1.0.0-M21"`
- `scalac`: Repeating Header, allows you to set scalaOptions for the script. `scalac: -language:higherKinds`
- `compiler-plugin`: Repeating Header, allows you to add compiler plugins. `compiler-plugin: "org.typelevel" % "kind-projector" % "0.11.3" cross CrossVersion.full`
- `sbt-plugin`: Repeating Header, allows you to add sbt plugins. `sbt-plugin: "io.github.davidgregory084" % "sbt-tpolecat" % "0.1.16"`

### Commands

- `catscript help` - Outputs help text
- `catscript clear-cache` - Clears all cached artifacts
- `catscript file` - Runs catscript against the provided file

### Options 

- `--verbose` - turns on some logging of internal catscript processes.
- `--no-cache` - Disables caching will recreate every time.
- `--compile-only` - Does not execute the resulting executable. (Useful to confirm compilation)
- `--sbt-output` - Puts the produced sbt project in this location rather than a temporary directory. (This is editor compliant and should allow you to debug with editor support and then bring your findings back to a script.)

### VSCode Highlighting

Note the `.catscript` extension is entirely arbitrary. Any file will work, but having an extension
makes recognizing files that use this format easier, and will allow syntax highlighting.

settings.json
```
"files.associations": {
    "*.catscript": "scala",
}
```