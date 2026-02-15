ThisBuild / scalaVersion := "3.3.1"
ThisBuild / organization := "dev.composable"
ThisBuild / version      := "0.1.0-SNAPSHOT"

ThisBuild / homepage := Some(url("https://github.com/jpalmerr/composable-errors"))
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / developers := List(
  Developer(
    id    = "jpalmerr",
    name  = "James Palmer",
    email = "your.email@example.com",
    url   = url("https://github.com/jpalmerr")
  )
)

lazy val root = (project in file("."))
  .settings(
    name := "composable-errors",
    description := "Union-type error composition for Scala 3. Eliminates ADT boilerplate.",

    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),

    // Compiler options
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings"
    )
  )
