package rvcore

import chisel3._
import chipsalliance.rocketchip.config.{Config, Parameters}
import chisel3.util.{Valid, ValidIO}
import freechips.rocketchip.diplomacy.{BundleBridgeSink, LazyModule, LazyModuleImp, LazyModuleImpLike}
import freechips.rocketchip.interrupts._
import freechips.rocketchip.tile.{BusErrorUnit, BusErrorUnitParams, BusErrors}
import freechips.rocketchip.tilelink.{BankBinder, TLBuffer, TLIdentityNode, TLNode, TLTempNode, TLXbar}
import huancun.debug.TLLogger
import huancun.{HCCacheParamsKey, HuanCun}
import system.HasSoCParameter
import top.BusPerfMonitor
import utils.{ResetGen, TLClientsMerger, TLEdgeBuffer, IntBuffer}

class L1BusErrorUnitInfo(implicit val p: Parameters) extends Bundle with HasSoCParameter {
  val ecc_error = Valid(UInt(soc.PAddrBits.W))
}

class RVCOREL1BusErrors()(implicit val p: Parameters) extends BusErrors {
  val icache = new L1BusErrorUnitInfo
  val dcache = new L1BusErrorUnitInfo
  val l2 = new L1BusErrorUnitInfo

  override def toErrorList: List[Option[(ValidIO[UInt], String, String)]] =
    List(
      Some(icache.ecc_error, "I_ECC", "Icache ecc error"),
      Some(dcache.ecc_error, "D_ECC", "Dcache ecc error"),
      Some(l2.ecc_error, "L2_ECC", "L2Cache ecc error")
    )
}

/**
  *   RVCORETileMisc contains every module except Core and L2 Cache
  */
class RVCORETileMisc()(implicit p: Parameters) extends LazyModule
  with HasRVCOREParameter
  with HasSoCParameter
{
  val mmio_xbar = TLXbar()
  val mmio_port = TLIdentityNode() // to L3
  val beu = LazyModule(new BusErrorUnit(
    new RVCOREL1BusErrors(), BusErrorUnitParams(0x38010000)
  ))

  val memory_port = TLIdentityNode()
  val i_mmio_port = TLTempNode()
  val d_mmio_port = TLTempNode()

  val l2_binder = coreParams.L2CacheParamsOpt.map(_ => BankBinder(coreParams.L2NBanks, 64))
  val l1_xbar = TLXbar()

  l2_binder match {
    case Some(binder) =>
      memory_port := TLBuffer.chainNode(2) := TLClientsMerger() := TLXbar() :=* binder
    case None =>
      memory_port := l1_xbar
  }

  mmio_xbar := TLBuffer.chainNode(2) := i_mmio_port
  mmio_xbar := TLBuffer.chainNode(2) := d_mmio_port
  beu.node := TLBuffer.chainNode(3) := mmio_xbar
  mmio_port := TLBuffer.chainNode(3) := mmio_xbar

  lazy val module = new LazyModuleImp(this){
    val beu_errors = IO(Input(chiselTypeOf(beu.module.io.errors)))
    beu.module.io.errors <> beu_errors
  }
}

class RVCORETile()(implicit p: Parameters) extends LazyModule
  with HasRVCOREParameter
  with HasSoCParameter
{
  private val core = LazyModule(new RVCORECore())
  private val misc = LazyModule(new RVCORETileMisc())
  private val l2cache = coreParams.L2CacheParamsOpt.map(l2param =>
    LazyModule(new HuanCun()(new Config((_, _, _) => {
      case HCCacheParamsKey => l2param
    })))
  )

  // public ports
  val memory_port = misc.memory_port
  val uncache = misc.mmio_port
  val clint_int_sink = IntIdentityNode()
  val plic_int_sink = IntIdentityNode()
  val debug_int_sink = IntIdentityNode()
  val beu_int_source = IntIdentityNode()
  val core_reset_sink = BundleBridgeSink(Some(() => Bool()))

  core.clint_int_sink :*= IntBuffer() :*= IntBuffer() :*= clint_int_sink
  core.plic_int_sink :*= IntBuffer() :*= IntBuffer() :*= plic_int_sink
  core.debug_int_sink :*= IntBuffer() :*= IntBuffer() :*= debug_int_sink
  beu_int_source :*= IntBuffer() :*= IntBuffer() :*= misc.beu.intNode




  l2cache match {
    case Some(l2) =>
      misc.l2_binder.get :*= l2.node :*= core.l1_xbar
    case None =>  misc.l1_xbar :*= core.l1_xbar
  }

  misc.i_mmio_port := core.frontend.instrUncache.clientNode
  misc.d_mmio_port := core.memBlock.uncache.clientNode

  lazy val module = new LazyModuleImp(this){
    val io = IO(new Bundle {
      val hartId = Input(UInt(64.W))
      val cpu_halt = Output(Bool())
    })

    dontTouch(io.hartId)

    val core_soft_rst = core_reset_sink.in.head._1

    core.module.io.hartId := io.hartId
    io.cpu_halt := core.module.io.cpu_halt
    if(l2cache.isDefined){
      core.module.io.perfEvents.zip(l2cache.get.module.io.perfEvents.flatten).foreach(x => x._1.value := x._2)
    }
    else {
      core.module.io.perfEvents <> DontCare
    }

    misc.module.beu_errors.icache <> core.module.io.beu_errors.icache
    misc.module.beu_errors.dcache <> core.module.io.beu_errors.dcache
    if(l2cache.isDefined){
      misc.module.beu_errors.l2.ecc_error.valid := l2cache.get.module.io.ecc_error.valid
      misc.module.beu_errors.l2.ecc_error.bits := l2cache.get.module.io.ecc_error.bits
    } else {
      misc.module.beu_errors.l2 <> 0.U.asTypeOf(misc.module.beu_errors.l2)
    }

    // Modules are reset one by one
    // io_reset ----
    //             |
    //             v
    // reset ----> OR_SYNC --> {Misc, L2 Cache, Cores}
    val resetChain = Seq(
      Seq(misc.module, core.module) ++
        l2cache.map(_.module)
    )
    ResetGen(resetChain, reset.asBool || core_soft_rst, !debugOpts.FPGAPlatform)
  }
}
