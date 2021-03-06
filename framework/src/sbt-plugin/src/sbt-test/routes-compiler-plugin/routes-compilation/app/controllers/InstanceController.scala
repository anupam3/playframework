/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package controllers

import play.api.mvc._
import javax.inject.Inject

class InstanceController @Inject() (c: ControllerComponents) extends AbstractController(c) {
  def index = Action {
    Ok
  }
}
