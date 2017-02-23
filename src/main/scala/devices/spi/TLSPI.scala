// See LICENSE for license details.
package sifive.blocks.devices.spi

import Chisel._
import config._
import uncore.tilelink2._
import diplomacy._
import regmapper._
import junctions._
import rocketchip.PeripheryBusConfig
import sifive.blocks.util.{NonBlockingEnqueue, NonBlockingDequeue}

trait SPIConfigBase {
  val rAddress: BigInt
  val rSize: BigInt
  val rxDepth: Int
  val txDepth: Int

  val csWidth: Int
  val frameBits: Int
  val delayBits: Int
  val divisorBits: Int

  val sampleDelay: Int

  lazy val csIdBits = log2Up(csWidth)
  lazy val lengthBits = log2Floor(frameBits) + 1
  lazy val countBits = math.max(lengthBits, delayBits)

  lazy val txDepthBits = log2Floor(txDepth) + 1
  lazy val rxDepthBits = log2Floor(rxDepth) + 1

}

case class SPIConfig(
    rAddress: BigInt,
    rSize: BigInt = 0x1000,
    rxDepth: Int = 8,
    txDepth: Int = 8,
    csWidth: Int = 1,
    frameBits: Int = 8,
    delayBits: Int = 8,
    divisorBits: Int = 12,
    sampleDelay: Int = 2)
  extends SPIConfigBase {

  require(frameBits >= 4)
  require(sampleDelay >= 0)
}

class SPITopBundle(val i: util.HeterogeneousBag[Vec[Bool]], val r: util.HeterogeneousBag[TLBundle]) extends Bundle

class SPITopModule[B <: SPITopBundle](c: SPIConfigBase, bundle: => B, outer: TLSPIBase)
  extends LazyModuleImp(outer) {

  val io = new Bundle {
    val port = new SPIPortIO(c)
    val tl = bundle
  }

  val ctrl = Reg(init = SPIControl.init(c))

  val fifo = Module(new SPIFIFO(c))
  val mac = Module(new SPIMedia(c))
  io.port <> mac.io.port

  fifo.io.ctrl.fmt := ctrl.fmt
  fifo.io.ctrl.cs <> ctrl.cs
  fifo.io.ctrl.wm := ctrl.wm
  mac.io.ctrl.sck := ctrl.sck
  mac.io.ctrl.dla := ctrl.dla
  mac.io.ctrl.cs <> ctrl.cs

  val ie = Reg(init = new SPIInterrupts().fromBits(Bits(0)))
  val ip = fifo.io.ip
  io.tl.i(0)(0) := (ip.txwm && ie.txwm) || (ip.rxwm && ie.rxwm)

  protected val regmapBase = Seq(
    SPICRs.sckdiv -> Seq(RegField(c.divisorBits, ctrl.sck.div)),
    SPICRs.sckmode -> Seq(
      RegField(1, ctrl.sck.pha),
      RegField(1, ctrl.sck.pol)),
    SPICRs.csid -> Seq(RegField(c.csIdBits, ctrl.cs.id)),
    SPICRs.csdef -> ctrl.cs.dflt.map(x => RegField(1, x)),
    SPICRs.csmode -> Seq(RegField(SPICSMode.width, ctrl.cs.mode)),
    SPICRs.dcssck -> Seq(RegField(c.delayBits, ctrl.dla.cssck)),
    SPICRs.dsckcs -> Seq(RegField(c.delayBits, ctrl.dla.sckcs)),
    SPICRs.dintercs -> Seq(RegField(c.delayBits, ctrl.dla.intercs)),
    SPICRs.dinterxfr -> Seq(RegField(c.delayBits, ctrl.dla.interxfr)),

    SPICRs.fmt -> Seq(
      RegField(SPIProtocol.width, ctrl.fmt.proto),
      RegField(SPIEndian.width, ctrl.fmt.endian),
      RegField(SPIDirection.width, ctrl.fmt.iodir)),
    SPICRs.len -> Seq(RegField(c.lengthBits, ctrl.fmt.len)),

    SPICRs.txfifo -> NonBlockingEnqueue(fifo.io.tx),
    SPICRs.rxfifo -> NonBlockingDequeue(fifo.io.rx),

    SPICRs.txmark -> Seq(RegField(c.txDepthBits, ctrl.wm.tx)),
    SPICRs.rxmark -> Seq(RegField(c.rxDepthBits, ctrl.wm.rx)),

    SPICRs.ie -> Seq(
      RegField(1, ie.txwm),
      RegField(1, ie.rxwm)),
    SPICRs.ip -> Seq(
      RegField.r(1, ip.txwm),
      RegField.r(1, ip.rxwm)))
}

abstract class TLSPIBase(c: SPIConfigBase)(implicit p: Parameters) extends LazyModule {
  require(isPow2(c.rSize))
  val rnode = TLRegisterNode(address = AddressSet(c.rAddress, c.rSize-1), beatBytes = p(PeripheryBusConfig).beatBytes)
  val intnode = IntSourceNode(1)
}

class TLSPI(c: SPIConfig)(implicit p: Parameters) extends TLSPIBase(c)(p) {
  lazy val module = new SPITopModule(c, new SPITopBundle(intnode.bundleOut, rnode.bundleIn), this) {
    mac.io.link <> fifo.io.link
    rnode.regmap(regmapBase:_*)
  }
}
