/*
 * Copyright 2014, by Vladimir Kostyukov and Contributors.
 *
 * This file is a part of Finch library that may be found at
 *
 *      https://github.com/vkostyukov/finch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributor(s): -
 */

package io

import com.twitter.util.Future
import com.twitter.finagle.{Service, Filter}
import com.twitter.finagle.http.service.RoutingService
import com.twitter.finagle.http.path.Path
import com.twitter.finagle.builder.ServerBuilder
import scala.util.parsing.json.{JSONType, JSONArray, JSONObject}
import java.net.InetSocketAddress
import org.jboss.netty.handler.codec.http.{HttpResponseStatus, HttpMethod}
import scala.util.Random
import com.twitter.finagle.http.{Http, Status, Version, Response, Request, RichHttp}

/***
 * Hi! I'm Finch - a super-tiny library atop of Finagle that makes the
 * development of RESTFul API services more pleasant and slick.
 *
 * I'm built around three very simple building-blocks:
 *   1. ''HttpServiceOf[A]'' that maps ''HttpRequest'' to some response
 *      (both are just a special cases of Finagle's ''Service'')
 *   2. ''Facet[+A, -B]'' that transforms service's response ''A'' to ''B''
 *      (just a special case of Finagle's 'Filter')
 *   3. ''Resource'' that provides route information about a particular resource
 *      (just a special case of ''PartialFunction'' from route to ''HttpService'')
 *
 * I'm trying to follow the principles of my elder brother and keep the things
 * as composable as possible.
 *
 *   (a) In order to mark the difference between filters and facets and show the
 *       direction of a data-flow, the facets are composed with ''afterThat'' operator
 *       within reversed order:
 *
 *         '''val s = service afterThat facetA afterThat facetB'''

 *   (b) Resources might be treated as partial functions, so they may be composed
 *       together with ''orElse'' operator:
 *
 *         '''val r = userResource orElse orderResource'''

 *   (c) Another useful resource operator is ''andThen'' that takes a function from
 *       ''HttpService'' to ''HttpService'' and returns a new resource with function
 *       applied to its every service.
 *
 *   (d) Resources may also be composed with filters by using the ''andThen'' operator
 *       in a familiar way:
 *
 *         '''val r = authorize andThen resource'''
 *
 * I support the only single format - JSON. There are also two predefined facets
 * available for JSON data-types.
 *
 *   1. ''TurnJsonToHttp'' simply coverts the JSON data to HttpResponse
 *   2. ''TurnJsonToHttpWithStatus(statusTag)'' checks whether the received json
 *      response contains the specified ''statusTag'' and if so copies it to the
 *      ''HttpResponse''. Otherwise status ''200'' (HTTP OK) is used.
 *
 * Have fun writing a reusable and scalable code with me!
 *
 * - https://github.com/vkostyukov/finch
 * - http://vkostyukov.ru
 */
package object finch {
  type HttpRequest = Request
  type HttpResponse = Response
  type JsonResponse = JSONType

  /**
   * Alters any object with ''toFuture'' method.
   *
   * @param any an object to be altered
   * @tparam A an object type
   */
  implicit class AnyToFuture[A](any: A) {
    def toFuture: Future[A] = Future.value(any)
  }

  /**
   * An HttpService with specified response type.
   *
   * @tparam Rep the response type
   */
  trait HttpServiceOf[+Rep] extends Service[HttpRequest, Rep]

  /**
   * A pure HttpService.
   */
  trait HttpService extends HttpServiceOf[HttpResponse]

  /**
   * Facet implements Filter interface but has a different meaning. Facets are
   * converts services responses from ''RepIn'' to ''RepOut''.
   *
   * @tparam RepIn the input response type
   * @tparam RepOut the output response type
   */
  trait Facet[-RepIn, +RepOut] extends Filter[HttpRequest, RepOut, HttpRequest, RepIn] {

    /**
     * Converts given ''rep'' from ''RepIn'' to ''RepOut'' type.
     *
     * @param rep the response to convert
     * @return a converted response
     */
    def apply(rep: RepIn): Future[RepOut]

    def apply(req: HttpRequest, service: Service[HttpRequest, RepIn]) =
      service(req) flatMap apply
  }

  object JsonObject {
    def apply(args: (String, Any)*) = JSONObject(args.toMap)
    def empty = JSONObject(Map.empty[String, Any])
  }

  object JsonArray {
    def apply(args: JSONObject*) = JSONArray(args.toList)
    def empty = JSONArray(List.empty[Any])
  }

  /**
   * A facet that turns a ''JsonResponse'' to an ''HttpResponse''.
   */
  object TurnJsonIntoHttp extends Facet[JsonResponse, HttpResponse] {
    def apply(rep: JsonResponse) = {
      val reply = Response(Version.Http11, Status.Ok)
      reply.setContentTypeJson()
      reply.setContentString(rep.toString())

      reply.toFuture
    }
  }

  /**
   * A facet that turns a ''JsonResponse'' to an ''HttpResponse'' with http-status
   * copied with JSON's field tagged with ''statusTag''.
   *
   * @param statusTag the status tag identifier
   */
  class TurnJsonIntoHttpWithStatusFromTag(statusTag: String) extends Facet[JsonResponse, HttpResponse] {
    def apply(rep: JsonResponse) = {
      val status = rep match {
        case JSONObject(map) =>
          map.get(statusTag) match {
            case Some(code: Int) => HttpResponseStatus.valueOf(code)
            case _ => Status.Ok
          }
        case _ => Status.Ok
      }

      val reply = Response(Version.Http11, status)
      reply.setContentTypeJson()
      reply.setContentString(rep.toString())

      reply.toFuture
    }
  }

  /**
   * A REST API resource that primary defines a ''route''.
   */
  trait RestResource[Rep] { self =>

    /**
     * @return a route of this resource
     */
    def route: PartialFunction[(HttpMethod, Path), Service[HttpRequest, Rep]]

    /**
     * Combines ''this'' resource with ''that'' resource. A new resource
     * contains routes of both ''this'' and ''that'' resources.
     *
     * @param that the resource to be combined with
     *
     * @return a new resource
     */
    def orElse(that: RestResource[Rep]) = new RestResource[Rep] {
      def route = self.route orElse that.route
    }

    /**
     * Applies given function ''fn'' to every route's endpoints of this resource.
     *
     * @param fn the function to be applied
     *
     * @return a new resource
     */
    def andThen[A](fn: Service[HttpRequest, Rep] => Service[HttpRequest, A]) =
      new RestResource[A] {
        def route = self.route andThen fn
      }

    /**
     * Applies given ''facet'' to this resource.
     *
     * @param facet a facet to apply
     * @tparam A a response type of new resource
     *
     * @return a new resource
     */
    def afterThat[A](facet: Facet[Rep, A]) =
      andThen { facet andThen _ }

    protected[this] implicit class AfterThatService[RepIn](service: Service[HttpRequest, RepIn]) {
      def afterThat[A](thatFacet: Facet[RepIn, A]) =
        thatFacet andThen service
    }

    protected[this] implicit class AfterThatFacet[RepIn, RepOut](facet: Facet[RepIn, RepOut]) {
      def afterThat[A](thatFacet: Facet[RepOut, A]) =
        thatFacet andThen facet
    }
  }

  /**
   * A base class for ''RestApi'' backend.
   */
  abstract class RestApi[Rep] extends App {

    protected[this] implicit class FilterAndThenResource(
        filter: Filter[HttpRequest, Rep, HttpRequest, Rep]) {

      def andThen(resource: => RestResource[Rep]) = resource andThen { filter andThen _ }
    }

    /**
     * @return a resource of this API
     */
    def resource: RestResource[Rep]

    /**
     * Loopbacks given ''HttpRequest'' to a resource
     *
     * @param req the ''HttpRequest'' to loopback
     * @return a response wrapped with ''Future''
     */
    def loopback(req: HttpRequest): Future[Rep] =
      resource.route(req.method -> Path(req.path))(req)

    /**
     * @return a name of this Finch instance
     */
    def name = "FinchInstance-" + new Random().alphanumeric.take(20).mkString

    /**
     * Exposes given ''resource'' at specified ''port'' and serves the requests.
     *
     * @param port the socket port number to listen
     * @param fn the function that transforms a resource type to ''HttpResponse''
     *
     * @return nothing
     */
    def exposeAt(port: Int)(fn: RestResource[Rep] => RestResource[HttpResponse]): Unit = {

      val httpResource = fn(resource)

      val service = new RoutingService[HttpRequest](
        new PartialFunction[HttpRequest, Service[HttpRequest, HttpResponse]] {
          def apply(req: HttpRequest) = httpResource.route(req.method -> Path(req.path))
          def isDefinedAt(req: HttpRequest) = httpResource.route.isDefinedAt(req.method -> Path(req.path))
        })

      ServerBuilder()
        .codec(RichHttp[HttpRequest](Http()))
        .bindTo(new InetSocketAddress(port))
        .name(name)
        .build(service)
    }
  }
}
