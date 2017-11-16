package com.mesosphere.cosmos.render

import com.mesosphere.universe
import io.circe.jawn.parse

object PackageConverter {

  def stringToPackageDefinition(
                                 packageDefinition: String
                               ): universe.v4.model.PackageDefinition = {
    parse(packageDefinition).toOption.flatMap { json =>
      json.hcursor.as[universe.v4.model.PackageDefinition].toOption
    }.get
  }

  def stringToResourceDefinition(
                                 resourceDefinition: String
                               ): universe.v3.model.V2Resource = {
    parse(resourceDefinition).toOption.flatMap { json =>
      json.hcursor.as[universe.v3.model.V2Resource].toOption
    }.get
  }

}
