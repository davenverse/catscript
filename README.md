# catscript - Cats Scripting [![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/catscript_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/catscript_2.12) ![Code of Consuct](https://img.shields.io/badge/Code%20of%20Conduct-Scala-blue.svg)



## Quick Start

- [Install Coursier if you don't already have it](https://get-coursier.io/docs/cli-installation.html#native-launcher)
- [Install SBT if you don't already have it](https://www.scala-sbt.org/release/docs/Installing-sbt-on-Mac.html) or via `cs install sbt-launcher`

```sh
# Coursier and SBT are Pre-requisites
cs fetch io.chrisdavenport:catscript_2.13:latest.release
cs bootstrap io.chrisdavenport:catscript_2.13:latest.release -o catscript
# Catscript is now an executable in this directory, place to a location on $PATH
# TODO Get a working cs install command so this can be automatic
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

### Available Interpreters

- `IOApp.Simple` - `def run: IO[Unit]`
- `IOApp` - `def run(args: List[String]): IO[ExitCode]`
- `App` - Works like a worksheet, entire script is within `def main(args: Array[String]): Unit`
- `Raw` - Takes your code and places it there with no enhancements, your code must have a MainClass

Defaults to `IOApp.Simple`.

### VSCode Highlighting

Note the catscript extension is entirely arbitrary. Any file will work, but having an extension
makes recognizing files that use this format easier, and will allow syntax highlighting.

settings.json
```
"files.associations": {
    "*.catscript": "scala",
}
```