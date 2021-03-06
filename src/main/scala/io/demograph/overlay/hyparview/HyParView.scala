/*
 * Copyright 2017 Merlijn Boogerd
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

package io.demograph.overlay.hyparview

import eu.timepit.refined.auto._
import io.demograph.overlay.hyparview.HyParView._
import io.demograph.overlay.hyparview.Messages._
import io.reactors.protocol._
import io.reactors.{ Channel, Proto, Reactor, ReactorSystem }

class HyParView(
  config: HyParViewConfig,
  initActiveView: PartialView[Neighbour],
  initPassiveView: PartialView[PassiveProtocol]) extends Reactor[Server.Req[Inspect.type, HyParViewState]] {

  self =>

  private[this] var activeView = initActiveView
  private[this] var passiveView = initPassiveView
  private[this] val controlConnector = system.channels.open[ControlMessage]
  private[this] val joinConnector = system.channels.open[Join]
  private[this] lazy val promotePeerServer: Server[PromotionRequest, PromotionReply] =
    system.server[PromotionRequest, PromotionReply]((_, _) => PromotionRejected(selfProtocol))
  private[this] lazy val selfProtocol: PassiveProtocol = PassiveProtocol(joinConnector.channel, promotePeerServer)

  main.events.onEvent {
    case (Inspect, resp) => resp ! HyParViewState(activeView, passiveView, controlConnector.channel, selfProtocol)
  }

  controlConnector.events.onEvent {
    case InitiateJoin(bootstrap) =>
      bootstrap ! Join(introduceSelf)
    case InitiateShuffle =>
  }

  joinConnector.events.onEvent {
    case Join(neighbour) =>
      val forwardJoin = ForwardJoin(neighbour.passive, config.activeRWL)
      activeView.foreach(_.active.forwardJoinChannel ! forwardJoin)
  }

  def introduceSelf: Neighbour = Neighbour(selfProtocol, ActiveProtocol(
    self.system.channels.twoWayServer[ShuffleRequest, ShuffleReply].serveTwoWay,
    system.channels.open[ForwardJoin].channel,
    system.channels.open[Disconnect.type].channel))
}

object HyParView {

  def apply(config: HyParViewConfig)(
    initActiveView: PartialView[Neighbour] = PartialView.empty(config.maxActiveViewSize),
    initPassiveView: PartialView[PassiveProtocol] = PartialView.empty(config.maxPassiveViewSize))(
    implicit
    system: ReactorSystem): Channel[(Inspect.type, Channel[HyParViewState])] = {
    system.spawn(proto(config)(initActiveView, initPassiveView))
  }

  def proto(config: HyParViewConfig)(
    initActiveView: PartialView[Neighbour] = PartialView.empty(config.maxActiveViewSize),
    initPassiveView: PartialView[PassiveProtocol] = PartialView.empty(config.maxPassiveViewSize)): Proto[HyParView] = {
    Proto[HyParView](config, initActiveView, initPassiveView)
  }

  private[hyparview] case object Inspect

  private[hyparview] sealed trait ControlMessage

  private[hyparview] case object InitiateShuffle extends ControlMessage

  private[hyparview] case class InitiateJoin(bootstrap: Channel[Join]) extends ControlMessage

}