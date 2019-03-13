/*
 * Copyright (c) 2012-2019 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich.common
package enrichments.registry

import java.io.File
import java.net.{InetAddress, URI, UnknownHostException}

import com.snowplowanalytics.iglu.client.{SchemaCriterion, SchemaKey}
import com.snowplowanalytics.iglu.client.validation.ProcessingMessageMethods._
import com.snowplowanalytics.iab.spidersandrobotsclient.IabClient
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import org.joda.time.DateTime
import scalaz._
import Scalaz._

import utils.{ConversionUtils, ScalazCirceUtils}

/** Companion object. Lets us create an IabEnrichment instance from a Json. */
object IabEnrichment extends ParseableEnrichment {

  val supportedSchema = SchemaCriterion(
    "com.snowplowanalytics.snowplow.enrichments",
    "iab_spiders_and_robots_enrichment",
    "jsonschema",
    1,
    0)

  /**
   * Creates an IabEnrichment instance from a Json.
   * @param config    The iab_spiders_and_robots_enrichment JSON
   * @param schemaKey provided for the enrichment, must be supported by this enrichment
   * @param localMode Whether to use the local IAB database file, enabled for tests
   * @return a configured IabEnrichment instance
   */
  def parse(
    config: Json,
    schemaKey: SchemaKey,
    localMode: Boolean
  ): ValidatedNelMessage[IabEnrichment] =
    isParseable(config, schemaKey).flatMap { conf =>
      def uri(name: String) = getIabDbFromName(conf, name)
      (uri("ipFile") |@| uri("excludeUseragentFile") |@| uri("includeUseragentFile")) {
        case (ip, exclude, include) => IabEnrichment(ip.some, exclude.some, include.some, localMode)
      }
    }

  /**
   * Creates IabDatabase instances used in the IabEnrichment case class.
   * @param config The iab_spiders_and_robots_enrichment JSON
   * @param name of the field, e.g. "ipFile", "excluseUseragentFile", "includeUseragentFile"
   * @return None if the field does not exist, Some(Failure) if the URI is invalid, Some(Success) if
   * it is found
   */
  private def getIabDbFromName(
    config: Json,
    name: String
  ): ValidatedNelMessage[IabDatabase] = {
    val uri = ScalazCirceUtils.extract[String](config, "parameters", name, "uri")
    val db = ScalazCirceUtils.extract[String](config, "parameters", name, "database")

    (uri.toValidationNel |@| db.toValidationNel) { (uri, db) =>
      getDatabaseUri(uri, db).toValidationNel.map[IabDatabase](u => IabDatabase(name, u, db))
    }.flatMap(identity)
  }

  /**
   * Convert the path to the IAB file from a String to a Validation[URI].
   * @param uri URI to the IAB database file
   * @param database Name of the IAB database
   * @return a Validation-boxed URI
   */
  private def getDatabaseUri(uri: String, database: String): ValidatedMessage[URI] =
    ConversionUtils
      .stringToUri(uri + (if (uri.endsWith("/")) "" else "/") + database)
      .flatMap {
        case Some(u) => u.success
        case None => "URI to IAB file must be provided".fail
      }
      .toProcessingMessage
}

/**
 * Contains enrichments based on IAB Spiders&Robots lookup.
 * @param ipFile (Full URI to the IAB excluded IP list, database name)
 * @param excludeUaFile (Full URI to the IAB excluded user agent list, database name)
 * @param includeUaFile (Full URI to the IAB included user agent list, database name)
 * @param localMode Whether to use the local database file. Enabled for tests.
 */
case class IabEnrichment(
  ipFile: Option[IabDatabase],
  excludeUaFile: Option[IabDatabase],
  includeUaFile: Option[IabDatabase],
  localMode: Boolean
) extends Enrichment {
  private type DbEntry = Option[(Option[URI], String)]

  private val schemaUri = "iglu:com.iab.snowplow/spiders_and_robots/jsonschema/1-0-0"

  // Construct a Tuple3 of all IAB files
  private val dbs: (DbEntry, DbEntry, DbEntry) = {
    def db(iabDb: Option[IabDatabase]): DbEntry = iabDb.map {
      case IabDatabase(name, uri, db) =>
        if (localMode) {
          (None, getClass.getResource(db).toURI.getPath)
        } else {
          (Some(uri), "./iab_" + name)
        }
    }

    (db(ipFile), db(excludeUaFile), db(includeUaFile))
  }

  // Collect a cache of IAB files for local download
  override val filesToCache: List[(URI, String)] =
    (dbs._1 ++ dbs._2 ++ dbs._3).collect {
      case (Some(uri), path) => (uri, path)
    }.toList

  // Create an IAB client based on the IAB files list
  private lazy val iabClient = {
    def file(db: DbEntry): File = new File(db.get._2)
    new IabClient(file(dbs._1), file(dbs._2), file(dbs._3))
  }

  /**
   * Get the IAB response containing information about whether an event is a spider or robot using
   * the IAB client library.
   * @param userAgent User agent used to perform the check
   * @param ipAddress IP address used to perform the check
   * @param accurateAt Date of the event, used to determine whether entries in the IAB list are
   * relevant or outdated
   * @return an IabResponse object
   */
  private[enrichments] def performCheck(
    userAgent: String,
    ipAddress: String,
    accurateAt: DateTime
  ): Validation[String, IabEnrichmentResponse] =
    try {
      val result = iabClient.checkAt(userAgent, InetAddress.getByName(ipAddress), accurateAt.toDate)
      IabEnrichmentResponse(
        result.isSpiderOrRobot,
        result.getCategory.toString,
        result.getReason.toString,
        result.getPrimaryImpact.toString).success
    } catch {
      case exc: UnknownHostException => s"IP address $ipAddress was invald".failure
    }

  /**
   * Get the IAB response as a JSON context for a specific event
   * @param userAgent enriched event optional user agent
   * @param ipAddress enriched event optional IP address
   * @param accurateAt enriched event optional datetime
   * @return IAB response as a self-describing JSON object
   */
  def getIabContext(
    userAgent: Option[String],
    ipAddress: Option[String],
    accurateAt: Option[DateTime]
  ): Validation[String, Json] = getIab(userAgent, ipAddress, accurateAt).map(addSchema)

  /**
   * Get IAB check response received from the client library and extracted as a JSON object
   * @param userAgent enriched event optional user agent
   * @param ipAddress enriched event optional IP address
   * @param time enriched event optional datetime
   * @return IAB response as JSON object
   */
  private def getIab(
    userAgent: Option[String],
    ipAddress: Option[String],
    time: Option[DateTime]
  ): Validation[String, Json] =
    (userAgent, ipAddress, time) match {
      case (Some(ua), Some(ip), Some(t)) => performCheck(ua, ip, t).map(_.asJson)
      case _ =>
        ("One of required event fields missing. " +
          s"user agent: $userAgent, ip address: $ipAddress, time: $time").failure
    }

  /**
   * Add Iglu URI to JSON Object
   * @param context IAB context as JSON Object
   * @return JSON Object wrapped as Self-describing JSON
   */
  private def addSchema(context: Json): Json =
    Json.obj(
      "schema" := schemaUri,
      "data" := context
    )
}

/** Case class copy of `com.snowplowanalytics.iab.spidersandrobotsclient.IabResponse` */
private[enrichments] final case class IabEnrichmentResponse(
  spiderOrRobot: Boolean,
  category: String,
  reason: String,
  primaryImpact: String
)

/** Case class representing an IAB database location */
final case class IabDatabase(name: String, uri: URI, db: String)
