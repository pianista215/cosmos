package com.mesosphere.cosmos.render

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import com.mesosphere.cosmos.circe.Decoders.parse
import com.mesosphere.cosmos.thirdparty.marathon.model.AppId
import com.mesosphere.universe.v3.model._
import io.circe.{Json, JsonObject}

import scala.io.Source

object Main extends App {


  val s = classpathJsonString("/crossdata/config.json")
  val schema = parse(s).asObject.get

  val mustache = classpathJsonString("/crossdata/marathon.json.mustache")
  val mustacheBytes = ByteBuffer.wrap(mustache.getBytes(StandardCharsets.UTF_8))

  val p = classpathJsonString("/crossdata/packages.json")
  val packages = parse(p).asObject.get

  val r = classpathJsonString("/crossdata/resources.json")
  val resources = parse(r).asObject.get

  val i = classpathJsonString("/crossdata/datio_input.json")
  val inputDatioConfig = parse(i).asObject.get //Ensure is valid json

  val appId = AppId("/crossdata-1")

  import com.netaporter.uri.dsl._

  val badPkg = V2Package(
    name = packages("name").get.asString.get,
    version = Version(packages("version").get.asString.get),
    releaseVersion = ReleaseVersion(0),
    maintainer = packages("maintainer").get.asString.get,
    description = packages("description").get.asString.get,
    marathon = Marathon(mustacheBytes),
    config = Some(schema),
    resource = Some(PackageConverter.stringToResourceDefinition(r))
  )

  val render: JsonObject = PackageDefinitionRenderer.renderMarathonV2App(
    "http://someplace",
    badPkg,
    Some(inputDatioConfig),
    Some(appId)
  ).get

  println(Json.fromJsonObject(render))


  private[this] def classpathJsonString(resourceName: String): String = {
    Option(this.getClass.getResourceAsStream(resourceName)) match {
      case Some(is) => Source.fromInputStream(is).mkString
      case _ => throw new IllegalStateException(s"Unable to load classpath resource: $resourceName")
    }
  }

}
