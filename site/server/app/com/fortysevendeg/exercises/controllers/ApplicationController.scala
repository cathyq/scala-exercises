/*
 * scala-exercises-server
 * Copyright (C) 2015-2016 47 Degrees, LLC. <http://www.47deg.com>
 */

package com.fortysevendeg.exercises.controllers

import java.util.UUID

import cats.data.Xor
import com.fortysevendeg.exercises.app._
import com.fortysevendeg.exercises.services.free.UserOps
import com.fortysevendeg.exercises.services.interpreters.ProdInterpreters
import com.fortysevendeg.exercises.utils.OAuth2
import com.fortysevendeg.shared.free.ExerciseOps
import doobie.imports._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc._
import play.api.routing.JavaScriptReverseRouter

import scala.concurrent.Future
import scalaz.concurrent.Task
import com.fortysevendeg.exercises.services.interpreters.FreeExtensions._

class ApplicationController(
    implicit
    exerciseOps: ExerciseOps[ExercisesApp],
    userOps:     UserOps[ExercisesApp],
    T:           Transactor[Task]
) extends Controller with ProdInterpreters {

  def index = Action.async { implicit request ⇒

    val (redirectUrl, state) = authStatus

    val ops = for {
      libraries ← exerciseOps.getLibraries
      user ← userOps.getUserByLogin(request.session.get("user").getOrElse(""))
    } yield (libraries, user, request.session.get("oauth-token"))

    ops.runTask match {
      case Xor.Right((libraries, user, Some(token))) ⇒ Future.successful(Ok(views.html.templates.home.index(user = user, libraries = libraries)))
      case Xor.Right((libraries, None, None))        ⇒ Future.successful(Ok(views.html.templates.home.index(user = None, libraries = libraries, redirectUrl = Option(redirectUrl))).withSession("oauth-state" → state))
      case Xor.Left(ex)                              ⇒ Future.successful(InternalServerError(ex.getMessage))
    }
  }

  def library(libraryName: String) = Action.async { implicit request ⇒
    Future {
      exerciseOps.getLibraries.map(_.find(_.name == libraryName)).runTask match {
        case Xor.Right(Some(library)) ⇒ Redirect(s"$libraryName/${library.sectionNames.head}")
        case _                        ⇒ Ok("Library not found")
      }
    }
  }

  def section(libraryName: String, sectionName: String) = Action.async { implicit request ⇒
    Future {
      val (redirectUrl, state) = authStatus
      val ops = for {
        libraries ← exerciseOps.getLibraries
        section ← exerciseOps.getSection(libraryName, sectionName)
        user ← userOps.getUserByLogin(request.session.get("user").getOrElse(""))
      } yield (libraries.find(_.name == libraryName), section, user, request.session.get("oauth-token"))
      ops.runTask match {
        case Xor.Right((Some(l), Some(s), user, Some(token))) ⇒ Ok(views.html.templates.library.index(l, s, user))
        case Xor.Right((Some(l), Some(s), user, None)) ⇒ Ok(views.html.templates.library.index(l, s, user, Option(redirectUrl))).withSession("oauth-state" → state)
        case Xor.Right((Some(l), None, _, _)) ⇒ Redirect(l.sectionNames.head)
        case _ ⇒ Ok("Section not found")
      }
    }
  }

  def javascriptRoutes = Action { implicit request ⇒
    import routes.javascript._
    Ok(
      JavaScriptReverseRouter("jsRoutes")(
        ExercisesController.evaluate,
        UserProgressController.fetchUserProgressBySection
      )
    ).as("text/javascript")
  }

  def authStatus(implicit req: Request[AnyContent]): (String, String) = {
    val scope = "user"
    val state = UUID.randomUUID().toString
    val callbackUrl = com.fortysevendeg.exercises.utils.routes.OAuth2Controller.callback(None, None).absoluteURL()
    val redirectUrl = OAuth2.getAuthorizationUrl(callbackUrl, scope, state)
    (redirectUrl, state)
  }

}
