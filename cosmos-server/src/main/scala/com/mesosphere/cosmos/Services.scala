package com.mesosphere.cosmos

import com.mesosphere.cosmos.Uris._
import com.mesosphere.cosmos.error.CosmosException
import com.mesosphere.cosmos.error.Forbidden
import com.mesosphere.cosmos.error.ServiceUnavailable
import com.mesosphere.cosmos.error.Unauthorized
import com.netaporter.uri.Uri
import com.twitter.finagle.ChannelException
import com.twitter.finagle.Filter
import com.twitter.finagle.Http
import com.twitter.finagle.NoBrokersAvailableException
import com.twitter.finagle.RequestException
import com.twitter.finagle.Service
import com.twitter.finagle.SimpleFilter
import com.twitter.finagle.http.Request
import com.twitter.finagle.http.Response
import com.twitter.finagle.http.Status
import com.twitter.finagle.http.filter.LoggingFilter
import com.twitter.finagle.http.param.MaxResponseSize
import com.twitter.util.Future
import com.twitter.util.StorageUnit
import com.twitter.util.Try

object Services {
  def adminRouterClient(
    uri: Uri,
    maxResponseSize: StorageUnit
  ): Try[Service[Request, Response]] = {
    httpClient("adminRouter", uri, maxResponseSize)
  }

  def marathonClient(
    uri: Uri,
    maxResponseSize: StorageUnit
  ): Try[Service[Request, Response]] = {
    httpClient("marathon", uri, maxResponseSize)
  }

  def mesosClient(
    uri: Uri,
    maxResponseSize: StorageUnit
  ): Try[Service[Request, Response]] = {
    httpClient("mesos", uri, maxResponseSize)
  }

  def httpClient(
    serviceName: String,
    uri: Uri,
    maxResponseSize: StorageUnit,
    postAuthFilter: SimpleFilter[Request, Response] = Filter.identity
  ): Try[Service[Request, Response]] = {
    extractHostAndPort(uri) map { case ConnectionDetails(hostname, port, tls) =>
      val cBuilder = tls match {
        case false =>
          Http.client
        case true =>
          Http.client.withTls(hostname)
      }

      LoggingFilter.andThen(
        new ConnectionExceptionHandler(serviceName).andThen(
          new AuthFilter(serviceName).andThen(
            postAuthFilter.andThen(
              cBuilder
                .configured(MaxResponseSize(maxResponseSize))
                .newService(s"$hostname:$port", serviceName)
            )
          )
        )
      )
    }
  }

  private[this] class ConnectionExceptionHandler(serviceName: String) extends SimpleFilter[Request, Response] {
    override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
      service(request)
        .rescue {
          case ce: ChannelException =>
            Future.exception(CosmosException.apply(
              ServiceUnavailable(serviceName),
              ce,
              Status.ServiceUnavailable)
            )
          case e: NoBrokersAvailableException =>
            Future.exception(CosmosException.apply(
              ServiceUnavailable(serviceName),
              e,
              Status.ServiceUnavailable)
            )
          case e: RequestException =>
            Future.exception(CosmosException.apply(
              ServiceUnavailable(serviceName),
              e,
              Status.ServiceUnavailable)
            )
        }
    }
  }

  private[this] class AuthFilter(serviceName: String) extends SimpleFilter[Request, Response] {
    override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
      service(request)
        .map { response =>
          response.status match {
            case Status.Unauthorized =>
              throw Unauthorized(serviceName, response.headerMap.get("WWW-Authenticate")).exception
            case Status.Forbidden =>
              throw Forbidden(serviceName).exception
            case _ =>
              response
          }
        }
    }
  }

}
