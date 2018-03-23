/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt.{Def, _}
import sbt.Keys._

/**
 * Generate version.conf and akka/Version.scala files based on the version setting.
 */
object VersionGenerator {

  object Keys {
    val versionPackage = settingKey[String]("key to set in version.conf and akka/Version.scala")
    val versionScalaFileLocation = settingKey[File ⇒ File]("key to set in akka/.../Version.scala")
  }
  import Keys._

  def settings(packageName: String, scalaFile: File ⇒ File): Seq[Def.Setting[_]] =
    inConfig(Compile)(Seq(
      versionPackage := packageName,
      versionScalaFileLocation := scalaFile,
      // ----

      resourceGenerators += generateVersion(resourceManaged, _ / "version.conf",
        s"""|${versionPackage.value}.version = "%s"
            |"""),
      sourceGenerators += generateVersion(sourceManaged, versionScalaFileLocation.value,
        s"""|package ${versionPackage.value}
            |
         |object Version {
            |  val current: String = "%s"
            |}
            |""")
    ))


  def generateVersion(dir: SettingKey[File], locate: File ⇒ File, template: String) = Def.task[Seq[File]] {
    val file = locate(dir.value)
    val content = template.stripMargin.format(version.value)
    if (!file.exists || IO.read(file) != content) IO.write(file, content)
    Seq(file)
  }

}
