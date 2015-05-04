name := "akka-supervision-java-lambda"

version := "15v01p01-ktoso"

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a")

libraryDependencies ++= Seq(
  TypesafeLibrary.akkaActor.value,
  TypesafeLibrary.akkaTestkit.value % "test",
  "junit"         %           "junit" % "4.11"         % "test",
  "com.novocode"  % "junit-interface" % "0.10"         % "test")
