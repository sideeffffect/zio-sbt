/*
 * Copyright 2022 dev.zio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.sbt

import java.nio.file.{Files, Paths}

import scala.annotation.nowarn

import io.circe.syntax.*
import io.circe.yaml.Printer.{LineBreak, YamlVersion}
import sbt.File

import zio.*
import zio.sbt.WebsiteUtils.DocsVersioning.SemanticVersioning
import zio.sbt.githubactions.*

@nowarn("msg=detected an interpolated expression")
object WebsiteUtils {

  import java.nio.charset.StandardCharsets

  def removeYamlHeader(markdown: String): String =
    markdown
      .split("\n")
      .dropWhile(_ == "---")
      .dropWhile(_ != "---")
      .dropWhile(_ == "---")
      .mkString("\n")

  def readFile(pathname: String): Task[String] =
    ZIO.attemptBlocking {
      val source = scala.io.Source.fromFile(new File(pathname))
      val result = source.getLines().mkString("\n")
      source.close()
      result
    }

  def githubBadge(githubUser: String, githubRepo: String, projectName: String): String = {
    val githubBadge = s"https://img.shields.io/github/stars/$githubUser/$githubRepo?style=social"
    val repoUrl     = s"https://github.com/$githubUser/$githubRepo"
    s"[![$projectName]($githubBadge)]($repoUrl)"
  }

  def discord =
    "[![Chat on Discord!](https://img.shields.io/discord/629491597070827530?logo=discord)](https://discord.gg/2ccFBr4)"

  def ciBadge(githubUser: String, githubRepo: String): String =
    s"![CI Badge](https://github.com/$githubUser/$githubRepo/workflows/CI/badge.svg)"

  def snapshotBadge(groupId: String, artifact: String): String = {
    val badge = s"https://img.shields.io/nexus/s/https/oss.sonatype.org/$groupId/$artifact.svg"
    val link  = s"https://oss.sonatype.org/content/repositories/snapshots/${groupId.replace('.', '/')}/$artifact/"
    s"[![Sonatype Snapshots]($badge)]($link)"
  }

  def releaseBadge(groupId: String, artifact: String): String = {
    val badge = s"https://img.shields.io/nexus/r/https/oss.sonatype.org/$groupId/$artifact.svg"
    val link  = s"https://oss.sonatype.org/content/repositories/releases/${groupId.replace('.', '/')}/$artifact/"
    s"[![Sonatype Releases]($badge)]($link)"
  }

  sealed abstract class ProjectStage(val name: String, color: String) {
    val stagePage: String =
      "https://github.com/zio/zio/wiki/Project-Stages"
    def badge: String =
      s"https://img.shields.io/badge/Project%20Stage-${name.replace(" ", "%20") + '-' + color}.svg"
  }
  object ProjectStage {
    final case object ProductionReady extends ProjectStage(name = "Production Ready", "brightgreen")
    final case object Development     extends ProjectStage(name = "Development", "green")
    final case object Experimental    extends ProjectStage(name = "Experimental", "yellowgreen")
    final case object Research        extends ProjectStage(name = "Research", "yellow")
    final case object Concept         extends ProjectStage(name = "Concept", "orange")
    final case object Deprecated      extends ProjectStage(name = "Deprecated", "red")
  }

  def projectStageBadge(stage: ProjectStage): String =
    s"[![${stage.name}](${stage.badge})](${stage.stagePage})"

  def generateProjectBadges(
    projectStage: ProjectStage,
    groupId: String,
    artifact: String,
    githubUser: String,
    githubRepo: String,
    projectName: String
  ): String = {
    val stage    = projectStageBadge(projectStage)
    val ci       = ciBadge(githubUser, githubRepo)
    val release  = releaseBadge(groupId, artifact)
    val snapshot = snapshotBadge(groupId, artifact)
    val github   = githubBadge(githubUser, githubRepo, projectName)
    s"""||Project Stage | CI | Release | Snapshot | Discord | Github |
        ||--------------|----|---------|----------|---------|--------|
        ||$stage        |$ci |$release |$snapshot |$discord |$github |
        |""".stripMargin
  }

  def generateReadme(
    projectName: String,
    introduction: String,
    documentation: String,
    codeOfConduct: String,
    contribution: String,
    support: String,
    license: String,
    acknowledgement: String
  ): Task[Unit] = {
    val acknowledgementSection = {
      if (acknowledgement.nonEmpty)
        s"""|## Acknowledgement
            |
            |$acknowledgement""".stripMargin
      else ""
    }
    for {
      _ <- ZIO.unit
      comment =
        """|[//]: # (This file was autogenerated using `zio-sbt-website` plugin via `sbt generateReadme` command.)
           |[//]: # (So please do not edit it manually. Instead, change "docs/index.md" file or sbt setting keys)
           |[//]: # (e.g. "readmeDocumentation" and "readmeSupport".)
           |""".stripMargin
      readme =
        s"""|$comment
            |
            |# $projectName
            |
            |$introduction
            |
            |## Documentation
            |
            |$documentation
            |
            |## Contributing
            |
            |$contribution
            |
            |## Code of Conduct
            |
            |$codeOfConduct
            |
            |## Support
            |
            |$support
            |
            |## License
            |
            |$license
            |
            |$acknowledgementSection
            |""".stripMargin
      _ <- ZIO.attemptBlocking(
             Files.write(
               Paths.get("README.md"),
               readme.getBytes(StandardCharsets.UTF_8)
             )
           )
    } yield ()
  }

  abstract class DocsVersioning(val npmCommand: String)
  object DocsVersioning {
    object SemanticVersioning extends DocsVersioning("publishToNpm")
    object HashVersioning     extends DocsVersioning("publishHashverToNpm")
  }

  @nowarn("msg=detected an interpolated expression")
  def websiteWorkflow(
    docsPublishBranch: String,
    sbtBuildOptions: List[String] = List.empty,
    versioning: DocsVersioning = SemanticVersioning
  ): String =
    io.circe.yaml
      .Printer(
        preserveOrder = true,
        dropNullKeys = true,
        splitLines = true,
        lineBreak = LineBreak.Unix,
        version = YamlVersion.Auto
      )
      .pretty(
        Workflow(
          name = "Website",
          triggers = Seq(
            Trigger.Release(Seq("published")),
            Trigger.Push(branches = Seq(Branch.Named(docsPublishBranch)))
          ),
          jobs = Seq(
            Job(
              id = "publish-docs",
              name = "Publish Docs",
              condition = Some(
                Condition.Expression("github.event_name == 'release'") &&
                  Condition.Expression("github.event.action == 'published'")
              ),
              steps = Seq(
                Step.StepSequence(
                  Seq(
                    Step.SingleStep(
                      name = "Git Checkout",
                      uses = Some(ActionRef("actions/checkout@v3.1.0")),
                      parameters = Map("fetch-depth" -> "0".asJson)
                    ),
                    Step.SingleStep(
                      name = "Setup Scala",
                      uses = Some(ActionRef("actions/setup-java@v3.6.0")),
                      parameters = Map(
                        "distribution" -> "temurin".asJson,
                        "java-version" -> 17.asJson,
                        "check-latest" -> true.asJson
                      )
                    ),
                    Step.SingleStep(
                      name = "Setup NodeJs",
                      uses = Some(ActionRef("actions/setup-node@v3")),
                      parameters = Map(
                        "node-version" -> "16.x".asJson,
                        "registry-url" -> "https://registry.npmjs.org".asJson
                      )
                    ),
                    Step.SingleStep(
                      name = "Publish Docs to NPM Registry",
                      run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} docs/${versioning.npmCommand}"),
                      env = Map(
                        "NODE_AUTH_TOKEN" -> "${{ secrets.NPM_TOKEN }}"
                      )
                    )
                  )
                )
              )
            ),
            Job(
              id = "generate-readme",
              name = "Generate README",
              steps = Seq(
                Step.SingleStep(
                  name = "Git Checkout",
                  uses = Some(ActionRef("actions/checkout@v3.1.0")),
                  parameters = Map(
                    "ref"         -> "${{ github.head_ref }}".asJson,
                    "fetch-depth" -> "0".asJson
                  )
                ),
                Step.SingleStep(
                  name = "Setup Scala",
                  uses = Some(ActionRef("actions/setup-java@v3.6.0")),
                  parameters = Map(
                    "distribution" -> "temurin".asJson,
                    "java-version" -> 17.asJson,
                    "check-latest" -> true.asJson
                  )
                ),
                Step.SingleStep(
                  name = "Generate Readme",
                  run = Some(s"sbt ${sbtBuildOptions.mkString(" ")} docs/generateReadme")
                ),
                Step.SingleStep(
                  name = "Commit Changes",
                  run = Some("""|git config --local user.email "github-actions[bot]@users.noreply.github.com"
                                |git config --local user.name "github-actions[bot]"
                                |git add README.md
                                |git commit -m "Update README.md" || echo "No changes to commit"
                                |""".stripMargin)
                ),
                Step.SingleStep(
                  name = "Create Pull Request",
                  uses = Some(ActionRef("peter-evans/create-pull-request@v4")),
                  parameters = Map(
                    "title"          -> "Update README.md".asJson,
                    "commit-message" -> "Update README.md".asJson,
                    "branch"         -> "zio-sbt-website/update-readme".asJson,
                    "branch-suffix"  -> "short-commit-hash".asJson,
                    "body" ->
                      """|Autogenerated changes after running the `sbt docs/generateReadme` command of the [zio-sbt-website](https://zio.dev/zio-sbt) plugin.
                         |
                         |I will automatically update the README.md file whenever there is new change for README.md, e.g.
                         |  - After each release, I will update the version in the installation section.
                         |  - After any changes to the "docs/index.md" file, I will update the README.md file accordingly.
                         |""".stripMargin.asJson
                  )
                )
              )
            )
          )
        ).asJson
      )
}
