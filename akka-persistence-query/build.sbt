import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, Unidoc }
import com.typesafe.tools.mima.plugin.MimaKeys
import akka.MultiNode

AkkaBuild.defaultSettings

AkkaBuild.experimentalSettings

Formatting.formatSettings

Unidoc.scaladocSettings

Unidoc.javadocSettings

OSGi.persistenceQuery

Dependencies.persistenceQuery

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-persistence-query-experimental").value

fork in Test := true

javaOptions in Test := MultiNode.defaultMultiJvmOptions
