package aspectjassembler

import sbt._
import sbtassembly.{AssemblyKeys, MergeStrategy, PathList}

object AspectjAssemblerPlugin extends AutoPlugin {
  override def requires = sbtassembly.AssemblyPlugin
  override def trigger = AllRequirements

  private val aopMerge: MergeStrategy = new MergeStrategy {
    val name = "aopMerge"
    import scala.xml._
    import scala.xml.dtd._

    def apply(tempDir: File, path: String, files: Seq[File]): Either[String, Seq[(File, String)]] = {
      val dt = DocType("aspectj", PublicID("-//AspectJ//DTD//EN", "http://www.eclipse.org/aspectj/dtd/aspectj.dtd"), Nil)
      val file = MergeStrategy.createMergeTarget(tempDir, path)
      val xmls: Seq[Elem] = files.map(XML.loadFile)
      val aspectsChildren: Seq[Node] = xmls.flatMap(_ \\ "aspectj" \ "aspects" \ "_")
      val weaverChildren: Seq[Node] = xmls.flatMap(_ \\ "aspectj" \ "weaver" \ "_")
      val options: String = xmls.map(x ⇒ (x \\ "aspectj" \ "weaver" \ "@options").text).mkString(" ").trim
      val weaverAttr = if (options.isEmpty) Null else new UnprefixedAttribute("options", options, Null)
      val aspects = new Elem(null, "aspects", Null, TopScope, false, aspectsChildren: _*)
      val weaver = new Elem(null, "weaver", weaverAttr, TopScope, false, weaverChildren: _*)
      val aspectj = new Elem(null, "aspectj", Null, TopScope, false, aspects, weaver)
      XML.save(file.toString, aspectj, "UTF-8", xmlDecl = false, dt)
      IO.append(file, IO.Newline.getBytes(IO.defaultCharset))
      Right(Seq(file → path))
    }
  }

  override lazy val projectSettings: Seq[Def.Setting[_]] = {
    import AssemblyKeys.{assemblyMergeStrategy, assembly}

    Seq(
      assemblyMergeStrategy in assembly := {
        case PathList("META-INF", "aop.xml") ⇒ aopMerge
        case s ⇒ (assemblyMergeStrategy in assembly).value(s)
      }
    )
  }
}
