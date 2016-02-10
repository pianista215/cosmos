package com.mesosphere.cosmos.handler

import com.twitter.finagle.http.Status
import com.twitter.util.Future
import io.circe.Encoder
import io.finch.DecodeRequest

import com.mesosphere.cosmos._
import com.mesosphere.cosmos.http.MediaTypes
import com.mesosphere.cosmos.model.thirdparty.marathon.MarathonApp
import com.mesosphere.cosmos.model.{AppId, UninstallRequest, UninstallResponse, UninstallResult}
import com.mesosphere.cosmos.repository.PackageCollection
import com.mesosphere.universe.PackageDetailsVersion

private[cosmos] final class UninstallHandler(
  adminRouter: AdminRouter,
  packageCache: PackageCollection
)(implicit
  bodyDecoder: DecodeRequest[UninstallRequest],
  encoder: Encoder[UninstallResponse]
) extends EndpointHandler[UninstallRequest, UninstallResponse] {

  val accepts = MediaTypes.UninstallRequest
  val produces = MediaTypes.UninstallResponse

  private type FwIds = List[String]

  private def lookupFrameworkIds(fwName: String): Future[FwIds] = {
    adminRouter.getMasterState(fwName).map { masterState =>
      masterState.frameworks
        .filter(_.name == fwName)
        .map(_.id)
    }
  }
  private def destroyMarathonAppsAndTearDownFrameworkIfPresent(
    uninstallOperations: List[UninstallOperation]
  ): Future[Seq[UninstallDetails]] = {
    val fs = for {
      op <- uninstallOperations
      appId = op.appId
    } yield {
      destroyMarathonApp(appId) flatMap { _ =>
        op.frameworkName match {
          case Some(fwName) =>
            lookupFrameworkIds(fwName).flatMap {
              case Nil =>
                Future.value(UninstallDetails.from(op))
              case fwId :: Nil =>
                adminRouter.tearDownFramework(fwId)
                  .map { _ =>
                    UninstallDetails.from(op).copy(frameworkId = Some(fwId))
                  }
              case all =>
                throw MultipleFrameworkIds(op.packageName, op.packageVersion, fwName, all)
            }
              .handle {
                case su: ServiceUnavailable =>
                  throw IncompleteUninstall(op.packageName, su)
              }
          case None =>
            Future.value(UninstallDetails.from(op))
        }
      }
    }
    Future.collect(fs)
  }
  private def destroyMarathonApp(appId: AppId): Future[MarathonAppDeleteSuccess] = {
    adminRouter.deleteApp(appId, force = true) map { resp =>
      resp.status match {
        case Status.Ok => MarathonAppDeleteSuccess()
        case a => throw MarathonAppDeleteError(appId)
      }
    }
  }

  override def apply(req: UninstallRequest): Future[UninstallResponse] = {
    // the following implementation is based on what the current CLI implementation does.
    // I've decided to follow it as close as possible so that we reduce any possible behavioral
    // changes that could have unforeseen consequences.
    //
    // In the future this will probably be revisited once Cosmos is the actual authority on services
    val f = req.appId match {
      case Some(appId) =>
        adminRouter.getAppOption(appId)
            .map {
              case Some(appResponse) =>
                createUninstallOperations(req.packageName, List(appResponse.app))
              case None =>
                throw new UninstallNonExistentAppForPackage(req.packageName, appId)
            }
      case None =>
        adminRouter.listApps()
          .map { marathonApps =>
            createUninstallOperations(req.packageName, marathonApps.apps)
          }
    }

    f.map { uninstallOperations =>
      req.all match {
        case Some(true) =>
          uninstallOperations
        case _ if uninstallOperations.size > 1 =>
          throw AmbiguousAppId(req.packageName, uninstallOperations.map(_.appId))
        case _ => // we've only got one package installed with the specified name, continue with it
          uninstallOperations
      }
    }
      .flatMap(destroyMarathonAppsAndTearDownFrameworkIfPresent)
      .flatMap { uninstallDetails =>
        Future.collect(
          uninstallDetails.map { detail =>
            packageCache.getPackageByPackageVersion(detail.packageName, None).map { packageFiles =>
              detail -> packageFiles
            }
          }
        )
      }
      .map { detailsAndPackageFiles =>
        val results = detailsAndPackageFiles.map { case (detail, packageFiles) =>
          UninstallResult(
            detail.packageName,
            detail.appId,
            detail.packageVersion,
            packageFiles.packageJson.postUninstallNotes
          )
        }
        UninstallResponse(results.toList)
      }
  }

  private[this] def createUninstallOperations(requestedPackageName: String, apps: List[MarathonApp]): List[UninstallOperation] = {
    val uninstallOperations = for {
      app <- apps
      labels = app.labels
      packageName <- labels.get("DCOS_PACKAGE_NAME")
      if packageName == requestedPackageName
    } yield {
      UninstallOperation(
        appId = app.id,
        packageName = packageName,
        packageVersion = app.packageVersion,
        frameworkName = labels.get("DCOS_PACKAGE_FRAMEWORK_NAME")
      )
    }

    if (uninstallOperations.isEmpty) {
      throw new PackageNotInstalled(requestedPackageName)
    }
    uninstallOperations
  }

  private case class MarathonAppDeleteSuccess()
  private case class UninstallOperation(
    appId: AppId,
    packageName: String,
    packageVersion: Option[PackageDetailsVersion],
    frameworkName: Option[String]
  )
  private case class UninstallDetails(
    appId: AppId,
    packageName: String,
    packageVersion: Option[PackageDetailsVersion],
    frameworkName: Option[String] = None,
    frameworkId: Option[String] = None
  )
  private case object UninstallDetails {
    def from(uninstallOperation: UninstallOperation): UninstallDetails = {
      UninstallDetails(
        appId = uninstallOperation.appId,
        packageName = uninstallOperation.packageName,
        packageVersion = uninstallOperation.packageVersion,
        frameworkName = uninstallOperation.frameworkName
      )
    }
  }
}

