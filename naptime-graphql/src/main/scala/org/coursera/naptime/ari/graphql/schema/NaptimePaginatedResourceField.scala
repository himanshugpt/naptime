/*
 * Copyright 2016 Coursera Inc.
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

package org.coursera.naptime.ari.graphql.schema

import com.linkedin.data.DataMap
import org.coursera.naptime.ResourceName
import org.coursera.naptime.ari.graphql.SangriaGraphQlContext
import org.coursera.naptime.schema.Handler
import org.coursera.naptime.schema.HandlerKind
import org.coursera.naptime.schema.RelationType
import org.coursera.naptime.schema.Resource
import org.coursera.naptime.schema.ReverseRelationAnnotation
import sangria.schema.Context
import sangria.schema.Field
import sangria.schema.ListType
import sangria.schema.ObjectType
import sangria.schema.Value

import scala.collection.JavaConverters._

object NaptimePaginatedResourceField {

  val COMPLEXITY_COST = 10.0D

  sealed trait FieldRelation

  case class ForwardRelation(resourceName: String) extends FieldRelation
  case class ReverseRelation(annotation: ReverseRelationAnnotation) extends FieldRelation

  def build(
      schemaMetadata: SchemaMetadata,
      resourceName: ResourceName,
      fieldName: String,
      handlerOverride: Option[Handler] = None,
      fieldRelation: Option[FieldRelation]): Either[SchemaError, Field[SangriaGraphQlContext, DataMap]] = {

    (for {
      resource <- schemaMetadata.getResourceOpt(resourceName)
      _ <- schemaMetadata.getSchema(resource)
    } yield {
      val handlerOpt = (handlerOverride, fieldRelation) match {
        case (Some(handler), _) =>
          Right(handler)
        case (None, Some(ForwardRelation(_))) =>
          resource.handlers.find(_.kind == HandlerKind.MULTI_GET).toRight {
            HasForwardRelationButMissingMultiGet(resourceName, fieldName)
          }
        case (None, Some(ReverseRelation(annotation))) =>
          annotation.relationType match {
            case RelationType.FINDER =>
              annotation.arguments.get("q").flatMap { finderName =>
                resource.handlers.find(_.name == finderName)
              }.toRight {
                MissingQParameterOnFinderRelation(resourceName, fieldName)
              }

            case RelationType.MULTI_GET =>
              resource.handlers.find(_.kind == HandlerKind.MULTI_GET).toRight {
                HasForwardRelationButMissingMultiGet(resourceName, fieldName)
              }

            case RelationType.GET | RelationType.SINGLE_ELEMENT_FINDER | RelationType.$UNKNOWN =>
              Left(UnhandledSchemaError(resourceName,
                s"Cannot use a paginated field for a single-element relationship: $fieldName"))
          }
        case _ =>
          resource.handlers.find(_.kind == HandlerKind.MULTI_GET).toRight {
            HasForwardRelationButMissingMultiGet(resourceName, fieldName)
          }
      }

      handlerOpt.right.map { handler =>

        val reverseRelationSpecifiedArguments = fieldRelation match {
          case Some(ReverseRelation(annotation)) => annotation.arguments.keySet
          case _ => Set[String]()
        }

        val arguments = NaptimeResourceUtils
          .generateHandlerArguments(handler, includePagination = true)
          .filterNot(_.name == "ids")
          .filterNot(arg => reverseRelationSpecifiedArguments.contains(arg.name))
        Field.apply[SangriaGraphQlContext, DataMap, Any, Any](
          name = fieldName,
          fieldType = getType(schemaMetadata, resourceName, fieldName),
          resolve = context => ParentContext(context),
          complexity = Some(
            (ctx, args, childScore) => {
              // API calls should count 10x, and we take limit into account because there could be
              // N child API calls for each response here
              val limit = args.arg(NaptimePaginationField.limitArgument)
              Math.max(limit / 10, 1) * COMPLEXITY_COST * childScore
            }),
          arguments = arguments)
      }
    }).getOrElse(Left(SchemaNotFound(resourceName)))
  }

  //TODO(bryan): add arguments for pagination in here
  private[this] def getType(
      schemaMetadata: SchemaMetadata,
      resourceName: ResourceName,
      fieldName: String): ObjectType[SangriaGraphQlContext, ParentContext] = {

    val resource = schemaMetadata.getResourceOpt(resourceName).getOrElse {
      throw SchemaGenerationException(s"Cannot find schema for $resourceName")
    }
    schemaMetadata.getSchema(resource).getOrElse {
      throw SchemaGenerationException(s"Cannot find schema for $resourceName")
    }

    ObjectType[SangriaGraphQlContext, ParentContext](
      name = formatPaginatedResourceName(resource),
      fieldsFn = () => {
        NaptimeResourceField.getType(schemaMetadata, resourceName).right.toOption.map { elementType =>
          val listType = ListType(elementType)
          List(
            Field.apply[SangriaGraphQlContext, ParentContext, Any, Any](
              name = "elements",
              fieldType = listType,
              resolve = getResolver(resourceName, fieldName)),
            Field.apply[SangriaGraphQlContext, ParentContext, Any, Any](
              name = "paging",
              fieldType = NaptimePaginationField.getField(resourceName, fieldName),
              resolve = context => context.value
            ))
        }.getOrElse(List.empty)
      })
  }

  private[this] def getResolver(
      resourceName: ResourceName,
      fieldName: String): Context[SangriaGraphQlContext, ParentContext] => Value[SangriaGraphQlContext, Any] = {
    (context: Context[SangriaGraphQlContext, ParentContext]) => {

      val connection = context.ctx.response.data.get(resourceName).map { objects =>
        val ids = if (context.value.parentContext.value.isEmpty) {
          // Top-Level Request
          context.ctx.response.topLevelResponses.find { case (topLevelRequest, _) =>
            topLevelRequest.resource == resourceName &&
              topLevelRequest.selection.alias ==
                context.value.parentContext.astFields.headOption.flatMap(_.alias) &&
              context.value.parentContext.astFields.headOption.map(_.name)
                .contains(topLevelRequest.selection.name)
          }.flatMap(r => Option(r._2.ids).map(_.asScala)).getOrElse(List.empty)
        } else {
          Option(context.value.parentContext.value).map { parentElement =>
            // Nested Request
            val alias = context.value.parentContext.astFields.headOption.flatMap(_.alias)
            val aliasedFieldName = alias.getOrElse(fieldName)
            val allIds = Option(parentElement.getDataList(aliasedFieldName))
              .map(_.asScala)
              .getOrElse(List.empty)
            val startOption = context.value.parentContext.arg(NaptimePaginationField.startArgument)
            val limit = context.value.parentContext.arg(NaptimePaginationField.limitArgument)
            val idsWithStart = startOption
              .map(s => allIds.dropWhile(_.toString != s))
              .getOrElse(allIds)
            idsWithStart.take(limit)
          }.getOrElse(List.empty)
        }
        ids.flatMap(id => objects.get(id))
      }.getOrElse(List.empty)

      Value[SangriaGraphQlContext, Any](connection)
    }
  }

  /**
    * Converts a resource name to a GraphQL compatible name. (i.e. 'courses.v1' to 'CoursesV1')
    *
    * @param resource Naptime resource
    * @return GraphQL-safe resource name
    */
  private[this] def formatPaginatedResourceName(resource: Resource): String = {
    s"${resource.name.capitalize}V${resource.version.getOrElse(0)}Connection"
  }

}
