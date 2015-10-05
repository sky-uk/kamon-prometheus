val akkaVersion = "2.3.14"
val sprayVersion = "1.3.3"
val kamonVersion = "0.5.1"

// assembly support for AspectJ  -- from https://gist.github.com/colestanfield/fac042d3108b0c06e952
import sbtassembly.MergeStrategy

// Create a new MergeStrategy for aop.xml files
val aopMerge: MergeStrategy = new MergeStrategy {
  val name = "aopMerge"
  import scala.xml._
  import scala.xml.dtd._

  def apply(tempDir: File, path: String, files: Seq[File]): Either[String, Seq[(File, String)]] = {
    val dt = DocType("aspectj", PublicID("-//AspectJ//DTD//EN", "http://www.eclipse.org/aspectj/dtd/aspectj.dtd"), Nil)
    val file = MergeStrategy.createMergeTarget(tempDir, path)
    val xmls: Seq[Elem] = files.map(XML.loadFile)
    val aspectsChildren: Seq[Node] = xmls.flatMap(_ \\ "aspectj" \ "aspects" \ "_")
    val weaverChildren: Seq[Node] = xmls.flatMap(_ \\ "aspectj" \ "weaver" \ "_")
    val options: String = xmls.map(x => (x \\ "aspectj" \ "weaver" \ "@options").text).mkString(" ").trim
    val weaverAttr = if (options.isEmpty) Null else new UnprefixedAttribute("options", options, Null)
    val aspects = new Elem(null, "aspects", Null, TopScope, false, aspectsChildren: _*)
    val weaver = new Elem(null, "weaver", weaverAttr, TopScope, false, weaverChildren: _*)
    val aspectj = new Elem(null, "aspectj", Null, TopScope, false, aspects, weaver)
    XML.save(file.toString, aspectj, "UTF-8", xmlDecl = false, dt)
    IO.append(file, IO.Newline.getBytes(IO.defaultCharset))
    Right(Seq(file -> path))
  }
}

// Use defaultMergeStrategy with a case for aop.xml
// I like this better than the inline version mentioned in assembly's README
val customMergeStrategy: String => MergeStrategy = {
  case PathList("META-INF", "aop.xml") =>
    aopMerge
  case s =>
    MergeStrategy.defaultMergeStrategy(s)
}

// end assembly support for AspectJ

lazy val protoc = taskKey[Seq[File]]("Runs the protoc compiler")

lazy val commonSettings = Seq(
  organization := "com.monsanto.arch",
  licenses := Seq("BSD New" → url("http://opensource.org/licenses/BSD-3-Clause")),
  scalaVersion := "2.11.7",
  scalacOptions ++= Seq(
    "-deprecation",
    "-unchecked"
  ),
  resolvers += Resolver.jcenterRepo
)

val bintrayPublishing = Seq(
  bintrayOrganization := Some("monsanto"),
  bintrayPackageLabels := Seq("kamon", "prometheus", "metrics"),
  bintrayVcsUrl := Some("https://github.com/MonsantoCo/kamon-prometheus")
)

val noPublishing = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false
)

lazy val library = (project in file("library"))
  .disablePlugins(sbtassembly.AssemblyPlugin)
  .settings(commonSettings: _*)
  .settings(bintrayPublishing: _*)
  .settings(
    name := "kamon-prometheus",
    libraryDependencies ++= Seq(
      "io.kamon"               %% "kamon-core"               % kamonVersion,
      "io.spray"               %% "spray-routing"            % sprayVersion,
      "com.typesafe.akka"      %% "akka-actor"               % akkaVersion,
      "com.typesafe"            % "config"                   % "1.3.0",
      "com.google.protobuf"     % "protobuf-java"            % "2.6.1",
      "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.4" % "provided",
      // -- testing --
      "org.scalatest"     %% "scalatest"     % "2.2.5"      % "test",
      "com.typesafe.akka" %% "akka-testkit"  % akkaVersion  % "test",
      "io.spray"          %% "spray-testkit" % sprayVersion % "test",
      "org.scalacheck"    %% "scalacheck"    % "1.12.5"     % "test",
      "io.kamon"          %% "kamon-akka"    % kamonVersion % "test"
    ),
    dependencyOverrides ++= Set(
      "org.scala-lang"          % "scala-library" % scalaVersion.value,
      "org.scala-lang"          % "scala-reflect" % scalaVersion.value,
      "org.scala-lang.modules" %% "scala-xml"     % "1.0.4"
    ),

    protoc in Compile := {
      val inputDir = (sourceDirectory in Compile).value / "proto"
      val inputFile = inputDir / "metrics.proto"
      val outputDir = (sourceManaged in Compile).value
      val outputFile = outputDir / "com" / "monsanto" / "arch" / "kamon" / "prometheus" / "proto" / "Metrics.java"
      val cacheDir = (streams in Compile).value.cacheDirectory / "protoc"

      val cachedCompile = FileFunction.cached(cacheDir, FilesInfo.lastModified, FilesInfo.exists) {_ =>
        (streams in Compile).value.log.info("Generating protobuf Java sources...")
        val protocInvocation = Seq("protoc", s"--java_out=$outputDir", s"-I$inputDir", inputFile.toString)
        outputDir.mkdirs()
        Process(protocInvocation).run()
        Set(outputFile)
      }
      cachedCompile(Set(inputFile))
      Seq(outputFile)
    },
    sourceGenerators in Compile += (protoc in Compile).taskValue,
    // We have to ensure that Kamon starts/stops serially
    parallelExecution in Test := false
  )

lazy val demo = (project in file("demo"))
  .dependsOn(library)
  .enablePlugins(DockerPlugin)
  .settings(commonSettings: _*)
  .settings(Revolver.settings: _*)
  .settings(aspectjSettings: _*)
  .settings(noPublishing: _*)
  .settings(
    name := "kamon-prometheus-demo",
    libraryDependencies ++= Seq(
      "io.kamon"          %% "kamon-spray"          % kamonVersion,
      "io.kamon"          %% "kamon-system-metrics" % kamonVersion,
      "io.spray"          %% "spray-can"            % sprayVersion,
      "com.monsanto.arch" %% "spray-kamon-metrics"  % "0.1.2"
    ),
    fork in run := true,
    javaOptions in run <++= AspectjKeys.weaverOptions in Aspectj,
    javaOptions in Revolver.reStart <++= AspectjKeys.weaverOptions in Aspectj,
    assemblyJarName in assembly <<= (name, version) map { (name, version) ⇒ s"$name-$version.jar" },
    assemblyMergeStrategy in assembly := customMergeStrategy,
    docker <<= docker.dependsOn(assembly),
    dockerfile in docker := {
      import sbtdocker.Instructions._

      val prometheusVersion = "0.15.1"
      val grafanaVersion = "2.1.3"

      val dockerSources = (sourceDirectory in Compile).value / "docker"
      val supervisordConf = dockerSources / "supervisord.conf"
      val prometheusYaml = dockerSources / "prometheus.yml"
      val grafanaRules = dockerSources / "grafana.rules"
      val grafanaIni = dockerSources / "grafana.ini"
      val grafanaDb = dockerSources / "grafana.db"
      val demoAssembly = (assemblyOutputPath in assembly).value
      val weaverAgent = (AspectjKeys.weaver in Aspectj).value.get
      val grafanaPluginsHash = "27f1398b497650f5b10b983ab9507665095a71b3"

      val instructions = Seq(
        From("java:8-jre"),
        WorkDir("/tmp"),
        Raw("RUN", Seq(
          // install supervisor
          "apt-get update && apt-get -y install supervisor",
          // install Prometheus
          s"curl -L https://github.com/prometheus/prometheus/releases/download/$prometheusVersion/prometheus-$prometheusVersion.linux-amd64.tar.gz | tar xz",
          "mv prometheus /usr/bin",
          "mkdir -p /etc/prometheus",
          "mv ./consoles ./console_libraries /etc/prometheus",
          "mkdir -p /var/lib/prometheus",
          // install Grafana
          "apt-get install -y adduser libfontconfig",
          s"curl -L -o grafana.deb https://grafanarel.s3.amazonaws.com/builds/grafana_${grafanaVersion}_amd64.deb",
          "dpkg -i grafana.deb",
          s"curl -L https://github.com/grafana/grafana-plugins/archive/$grafanaPluginsHash.tar.gz | tar xz",
          s"mv grafana-plugins-$grafanaPluginsHash/datasources/prometheus /usr/share/grafana/public/app/plugins/datasource",
          // clean up
          "rm -rf /tmp/* /var/lib/apt/lists/*"
        ).mkString(" && ")),
        // configure and use supervisor
        Copy(CopyFile(supervisordConf), "/etc/supervisor/conf.d/prometheus-demo.conf"),
        EntryPoint.exec(Seq("/usr/bin/supervisord", "-c", "/etc/supervisor/supervisord.conf")),
        // install the demo application
        Copy(CopyFile(demoAssembly), "/usr/share/kamon-prometheus-demo/demo.jar"),
        Copy(CopyFile(weaverAgent), "/usr/share/kamon-prometheus-demo/weaverAgent.jar"),
        // configure Prometheus
        Copy(Seq(CopyFile(prometheusYaml), CopyFile(grafanaRules)), "/etc/prometheus/"),
        // configure Grafana
        Copy(CopyFile(grafanaIni), "/etc/grafana/grafana.ini"),
        Copy(CopyFile(grafanaDb), "/var/lib/grafana/grafana.db"),
        // expose ports
        Expose(Seq(80, 3000, 9090))
      )
      sbtdocker.immutable.Dockerfile(instructions)
    }
  )

lazy val `kamon-prometheus` = (project in file("."))
  .disablePlugins(sbtassembly.AssemblyPlugin)
  .aggregate(library, demo)
  .settings(commonSettings: _*)
  .settings(noPublishing: _*)
