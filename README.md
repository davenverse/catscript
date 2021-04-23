# catscript - Cats Scripting [![Build Status](https://travis-ci.com/ChristopherDavenport/catscript.svg?branch=master)](https://travis-ci.com/ChristopherDavenport/catscript) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/catscript_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/catscript_2.12) ![Code of Consuct](https://img.shields.io/badge/Code%20of%20Conduct-Scala-blue.svg)

## [Head on over to the microsite](https://ChristopherDavenport.github.io/catscript)

## Quick Start

```sh
# Coursier and SBT are Pre-requisites
cs fetch io.chrisdavenport:catscript_2.13:0.0.1
cs bootstrap io.chrisdavenport:catscript_2.13:latest.release -o catscript
# Catscript is now an executable in this directory, place to a location on $PATH
# TODO Get a working cs install command so this can be automatic
```

Then write apps as simply as

```
#!/usr/bin/env catscript
// interpreter: IOApp.Simple
// scala: 3.0.0-RC2
// dependency: "org.http4s" %% "http4s-ember-client" % "1.0.0-M21"

import cats.effect._
import cats.effect.std.Console

def run: IO[Unit] = Console[IO].println("Hello world!!!")
```
