import akka._
import com.typesafe.sbt.pgp.PgpKeys.publishSigned

// Benchmark artifacts are never published, and the JMH dependency is non viral
whitesourceIgnore := true

enablePlugins(JmhPlugin, ScaladocNoVerificationOfDiagrams)
disablePlugins(Unidoc, MimaPlugin)

AkkaBuild.defaultSettings

AkkaBuild.dontPublishSettings
AkkaBuild.dontPublishDocsSettings
Dependencies.benchJmh
