val Scala213 = "2.13.5"

ThisBuild / crossScalaVersions := Seq(Scala213, "3.0.0")
ThisBuild / scalaVersion := Scala213

val catsV = "2.6.1"
val catsEffectV = "3.5.2"
val fs2V = "3.0.6"

val munitCatsEffectV = "1.0.5"

// Projects
lazy val `catscript` = project.in(file("."))
  .disablePlugins(MimaPlugin)
  .enablePlugins(NoPublishPlugin)
  .aggregate(core)

lazy val core = project.in(file("core"))
  .settings(commonSettings)
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "catscript",
  )

lazy val site = project.in(file("site"))
  .disablePlugins(MimaPlugin)
  .enablePlugins(DavenverseMicrositePlugin)
  .settings(commonSettings)
  .dependsOn(core)
  .settings{
    import microsites._
    Seq(
      micrositeDescription := "Cats Scripting",
      micrositeAuthor := "Christopher Davenport",
    )
  }

// General Settings
lazy val commonSettings = Seq(
  testFrameworks += new TestFramework("munit.Framework"),

  libraryDependencies ++= Seq(
    "org.typelevel"               %% "cats-core"                  % catsV,
    "org.typelevel"               %% "alleycats-core"             % catsV,

    "org.typelevel"               %% "cats-effect"                % catsEffectV,

    "co.fs2"                      %% "fs2-core"                   % fs2V,
    "co.fs2"                      %% "fs2-io"                     % fs2V,

    "org.typelevel"               %% "munit-cats-effect-3"        % munitCatsEffectV         % Test,
  )
)