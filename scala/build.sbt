ThisBuild / scalaVersion := "3.3.7" // LTS; matches the hydrozoa repo
ThisBuild / organization := "com.hydrozoa"

// scalafix reads SemanticDB emitted by the Scala 3 compiler; -Wunused:all backs
// OrganizeImports' removeUnused.
ThisBuild / semanticdbEnabled := true

lazy val root = (project in file("."))
  .settings(
    name := "daml-scratch-scala",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all",
    ),
  )
