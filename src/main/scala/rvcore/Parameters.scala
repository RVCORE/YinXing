/***************************************************************************************
* Copyright (c) 2020-2022 Beijing Vcore Technology Co.,Ltd.
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* YinXing is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package rvcore

import chipsalliance.rocketchip.config.{Field, Parameters}
import chisel3._
import chisel3.util._
import rvcore.backend.exu._
import rvcore.backend.dispatch.DispatchParameters
import rvcore.cache.DCacheParameters
import rvcore.cache.prefetch._
import rvcore.frontend.{BIM, BasePredictor, BranchPredictionResp, FTB, FakePredictor, MicroBTB, RAS, Tage, ITTage, Tage_SC}
import rvcore.frontend.icache.ICacheParameters
import rvcore.cache.mmu.{L2TLBParameters, TLBParameters}
import freechips.rocketchip.diplomacy.AddressSet
import system.SoCParamsKey
import huancun._
import huancun.debug._
import scala.math.min

case object RVCORETileKey extends Field[Seq[RVCORECoreParameters]]

case object RVCORECoreParamsKey extends Field[RVCORECoreParameters]

case class RVCORECoreParameters
(
  HasPrefetch: Boolean = false,
  HartId: Int = 0,
  XLEN: Int = 64,
  HasMExtension: Boolean = true,
  HasCExtension: Boolean = true,
  HasDiv: Boolean = true,
  HasICache: Boolean = true,
  HasDCache: Boolean = true,
  AddrBits: Int = 64,
  VAddrBits: Int = 39,
  HasFPU: Boolean = true,
  HasCustomCSRCacheOp: Boolean = true,
  FetchWidth: Int = 4,
  AsidLength: Int = 16,
  EnableBPU: Boolean = true,
  EnableBPD: Boolean = true,
  EnableRAS: Boolean = true,
  EnableLB: Boolean = false,
  EnableLoop: Boolean = true,
  EnableSC: Boolean = true,
  EnbaleTlbDebug: Boolean = false,
  EnableJal: Boolean = false,
  EnableUBTB: Boolean = true,
  UbtbGHRLength: Int = 4,
  // HistoryLength: Int = 512,
  EnableGHistDiff: Boolean = true,
  UbtbSize: Int = 256,
  FtbSize: Int = 2048,
  RasSize: Int = 32,
  CacheLineSize: Int = 512,
  FtbWays: Int = 4,
  TageTableInfos: Seq[Tuple3[Int,Int,Int]] =
  //       Sets  Hist   Tag
    // Seq(( 2048,    2,    8),
    //     ( 2048,    9,    8),
    //     ( 2048,   13,    8),
    //     ( 2048,   20,    8),
    //     ( 2048,   26,    8),
    //     ( 2048,   44,    8),
    //     ( 2048,   73,    8),
    //     ( 2048,  256,    8)),
    //Seq(( 1024,    8,    8),  //minimal default
    //    ( 1024,   13,    8),
    //    ( 1024,   32,    8),
    //    ( 1024,  119,    8)),


    //Seq((1024, 6   , 10),  //512kb
    //    (1024, 9   , 10),
    //    (1024, 12  , 10),
    //    (1024, 18  , 10),
    //    (1024, 26  , 10),
    //    (1024, 37  , 10),
    //    (1024, 54  , 10),
    //    (1024, 78  , 10),
    //    (1024, 112 , 10),
    //    (1024, 161 , 10),
    //    (1024, 232 , 10),
    //    (1024, 335 , 10),
    //    (1024, 482 , 10),
    //    (1024, 695 , 10),
    //    (1024, 1002, 10),
    //    (1024, 1444, 10),
    //    (1024, 2081, 10),
    //    (1024, 3000, 10)),
    //Seq(( 1024,    6,    7),  //256kb
    //    ( 1024,   10,    9),
    //    ( 1024,   18,    9),
    //    ( 1024,   25,    9),
    //    ( 1024,   35,    10),
    //    ( 1024,   55,    11),
    //    ( 1024,   69,    11),
    //    ( 1024,   105,   12),
    //    ( 1024,   155,   12),
    //    ( 512,    230,   12),
    //    ( 512,    354,   13),
    //    ( 512,    479,   14),
    //    ( 256,    642,   15),
    //    ( 128,    1012,  15),
    //    ( 128,    1347,  15)),
    //Seq(( 256,    4,    8),  //64kb
    //    ( 256,    6,    8),
    //    ( 256,    9,    8),
    //    ( 256,   13,    8),
    //    ( 256,   19,    8),
    //    ( 256,   29,    12),
    //    ( 256,   43,    12),
    //    ( 256,   63,    12),
    //    ( 256,   94,    12),
    //    ( 256,  139,    12),
    //    ( 256,  206,    12),
    //    ( 256,  306,    12),
    //    ( 256,  454,    12),
    //    ( 256,  674,    12),
    //    ( 256, 1000,    12)),
     Seq(( 256,    4,    7),   //32Kb
         ( 256,    9,    7),
         ( 256,   13,    7),
         ( 256,   24,    8),
         ( 256,   37,    9),
         ( 128,   53,    10),
         ( 256,   91,    10),
         ( 128,   145,   11),
         ( 64,    226,   13),
         ( 64,    359,   13)),
  ITTageTableInfos: Seq[Tuple3[Int,Int,Int]] =
  //      Sets  Hist   Tag
    Seq(( 128,    4,    9),
        ( 128,    8,    9),
        ( 128,   13,    9),
        ( 128,   16,    9),
        ( 128,   32,    9)),
  SCNRows: Int = 512,
  SCNTables: Int = 4,
  SCCtrBits: Int = 6,
  SCHistLens: Seq[Int] = Seq(0, 4, 10, 16),
  numBr: Int = 2,
  branchPredictor: Function2[BranchPredictionResp, Parameters, Tuple2[Seq[BasePredictor], BranchPredictionResp]] =
    ((resp_in: BranchPredictionResp, p: Parameters) => {
      // val loop = Module(new LoopPredictor)
      // val tage = (if(EnableBPD) { if (EnableSC) Module(new Tage_SC)
      //                             else          Module(new Tage) }
      //             else          { Module(new FakeTage) })
      val ftb = Module(new FTB()(p))
      val ubtb = Module(new MicroBTB()(p))
      // val bim = Module(new BIM()(p))
      val tage = Module(new Tage_SC()(p))
      val ras = Module(new RAS()(p))
      val ittage = Module(new ITTage()(p))
      // val tage = Module(new Tage()(p))
      // val fake = Module(new FakePredictor()(p))

      // val preds = Seq(loop, tage, btb, ubtb, bim)
      val preds = Seq(ubtb, tage, ftb, ittage, ras)
      preds.map(_.io := DontCare)

      // ubtb.io.resp_in(0)  := resp_in
      // bim.io.resp_in(0)   := ubtb.io.resp
      // btb.io.resp_in(0)   := bim.io.resp
      // tage.io.resp_in(0)  := btb.io.resp
      // loop.io.resp_in(0)  := tage.io.resp
      ubtb.io.in.bits.resp_in(0) := resp_in
      tage.io.in.bits.resp_in(0) := ubtb.io.out.resp
      ftb.io.in.bits.resp_in(0)  := tage.io.out.resp
      ittage.io.in.bits.resp_in(0)  := ftb.io.out.resp
      ras.io.in.bits.resp_in(0) := ittage.io.out.resp

      (preds, ras.io.out.resp)
    }),
  IBufSize: Int = 24,
  DecodeWidth: Int = 4,
  RenameWidth: Int = 4,
  CommitWidth: Int = 4,
  FtqSize: Int = 64,
  EnableLoadFastWakeUp: Boolean = true, // NOTE: not supported now, make it false
  IssQueSize: Int = 8,
  FMACIssQueSize: Int = 8,
  FMISCIssQueSize: Int = 8,
  CSRIssQueSize: Int = 8,
  ALUIssQueSize: Int = 8,
  MDUIssQueSize: Int = 12,
  STAIssQueSize: Int = 12,
  STDIssQueSize: Int = 12,
  LDIssQueSize: Int = 12,
  NRPhyRegs: Int = 96,
  LoadQueueSize: Int = 48,
  StoreQueueSize: Int = 32,
  RobSize: Int = 128,
  dpParams: DispatchParameters = DispatchParameters(
    IntDqSize = 16,
    FpDqSize = 16,
    LsDqSize = 16,
    IntDqDeqWidth = 4,
    FpDqDeqWidth = 4,
    LsDqDeqWidth = 4
  ),
  exuParameters: ExuParameters = ExuParameters(
    JmpCnt = 1,
    AluCnt = 4,
    MulCnt = 0,
    MduCnt = 1,
    FmacCnt = 2,
    FmiscCnt = 1,
    FmiscDivSqrtCnt = 0,
    LduCnt = 2,
    StuCnt = 2
  ),
  LoadPipelineWidth: Int = 2,
  StorePipelineWidth: Int = 2,
  StoreBufferSize: Int = 12,
  StoreBufferThreshold: Int = 7,
  EnsbufferWidth: Int = 2,
  EnableLoadToLoadForward: Boolean = false,
  EnableFastForward: Boolean = false,
  EnableLdVioCheckAfterReset: Boolean = true,
  EnableSoftPrefetchAfterReset: Boolean = true,
  EnableCacheErrorAfterReset: Boolean = true,
  RefillSize: Int = 512,
  MMUAsidLen: Int = 16, // max is 16, 0 is not supported now
  itlbParameters: TLBParameters = TLBParameters(
    name = "itlb",
    fetchi = true,
    useDmode = false,
    sameCycle = false,
    missSameCycle = true,
    normalNWays = 32,
    normalReplacer = Some("plru"),
    superNWays = 4,
    superReplacer = Some("plru"),
    shouldBlock = true
  ),
  ldtlbParameters: TLBParameters = TLBParameters(
    name = "ldtlb",
    normalNSets = 128,
    normalNWays = 1,
    normalAssociative = "sa",
    normalReplacer = Some("setplru"),
    superNWays = 8,
    normalAsVictim = true,
    outReplace = true,
    partialStaticPMP = true,
    saveLevel = true
  ),
  sttlbParameters: TLBParameters = TLBParameters(
    name = "sttlb",
    normalNSets = 128,
    normalNWays = 1,
    normalAssociative = "sa",
    normalReplacer = Some("setplru"),
    superNWays = 8,
    normalAsVictim = true,
    outReplace = true,
    partialStaticPMP = true,
    saveLevel = true
  ),
  refillBothTlb: Boolean = false,
  btlbParameters: TLBParameters = TLBParameters(
    name = "btlb",
    normalNSets = 1,
    normalNWays = 64,
    superNWays = 4,
  ),
  l2tlbParameters: L2TLBParameters = L2TLBParameters(),
  NumPerfCounters: Int = 16,
  icacheParameters: ICacheParameters = ICacheParameters(
    tagECC = Some("parity"),
    dataECC = Some("parity"),
    replacer = Some("setplru"),
    nMissEntries = 2,
    nProbeEntries = 2,
    nPrefetchEntries = 2,
    hasPrefetch = true,
  ),
  dcacheParametersOpt: Option[DCacheParameters] = Some(DCacheParameters(
    tagECC = Some("secded"),
    dataECC = Some("secded"),
    replacer = Some("setplru"),
    nMissEntries = 8,
    nProbeEntries = 4,
    nReleaseEntries = 10
  )),
  L2CacheParamsOpt: Option[HCCacheParameters] = Some(HCCacheParameters(
    name = "l2",
    level = 2,
    ways = 8,
    sets = 1024, // default 512KB L2
    prefetch = Some(huancun.prefetch.BOPParameters())
  )),
  L2NBanks: Int = 1,
  usePTWRepeater: Boolean = false,
  softPTW: Boolean = false // dpi-c debug only
){
  val allHistLens = SCHistLens ++ ITTageTableInfos.map(_._2) ++ TageTableInfos.map(_._2) :+ UbtbGHRLength
  val HistoryLength = allHistLens.max + numBr * FtqSize + 9 // 256 for the predictor configs now

  val loadExuConfigs = Seq.fill(exuParameters.LduCnt)(LdExeUnitCfg)
  val storeExuConfigs = Seq.fill(exuParameters.StuCnt)(StaExeUnitCfg) ++ Seq.fill(exuParameters.StuCnt)(StdExeUnitCfg)

  val intExuConfigs = (Seq.fill(exuParameters.AluCnt)(AluExeUnitCfg) ++
    Seq.fill(exuParameters.MduCnt)(MulDivExeUnitCfg) :+ JumpCSRExeUnitCfg)

  val fpExuConfigs =
    Seq.fill(exuParameters.FmacCnt)(FmacExeUnitCfg) ++
      Seq.fill(exuParameters.FmiscCnt)(FmiscExeUnitCfg)

  val exuConfigs: Seq[ExuConfig] = intExuConfigs ++ fpExuConfigs ++ loadExuConfigs ++ storeExuConfigs
}

case object DebugOptionsKey extends Field[DebugOptions]

case class DebugOptions
(
  FPGAPlatform: Boolean = false,
  EnableDifftest: Boolean = false,
  AlwaysBasicDiff: Boolean = true,
  EnableDebug: Boolean = false,
  EnablePerfDebug: Boolean = true,
  UseDRAMSim: Boolean = false
)

trait HasRVCOREParameter {

  implicit val p: Parameters

  val PAddrBits = p(SoCParamsKey).PAddrBits // PAddrBits is Phyical Memory addr bits

  val coreParams = p(RVCORECoreParamsKey)
  val env = p(DebugOptionsKey)

  val XLEN = coreParams.XLEN
  val minFLen = 32
  val fLen = 64
  def xLen = XLEN

  val HasMExtension = coreParams.HasMExtension
  val HasCExtension = coreParams.HasCExtension
  val HasDiv = coreParams.HasDiv
  val HasIcache = coreParams.HasICache
  val HasDcache = coreParams.HasDCache
  val AddrBits = coreParams.AddrBits // AddrBits is used in some cases
  val VAddrBits = coreParams.VAddrBits // VAddrBits is Virtual Memory addr bits
  val AsidLength = coreParams.AsidLength
  val AddrBytes = AddrBits / 8 // unused
  val DataBits = XLEN
  val DataBytes = DataBits / 8
  val HasFPU = coreParams.HasFPU
  val HasCustomCSRCacheOp = coreParams.HasCustomCSRCacheOp
  val FetchWidth = coreParams.FetchWidth
  val PredictWidth = FetchWidth * (if (HasCExtension) 2 else 1)
  val EnableBPU = coreParams.EnableBPU
  val EnableBPD = coreParams.EnableBPD // enable backing predictor(like Tage) in BPUStage3
  val EnableRAS = coreParams.EnableRAS
  val EnableLB = coreParams.EnableLB
  val EnableLoop = coreParams.EnableLoop
  val EnableSC = coreParams.EnableSC
  val EnbaleTlbDebug = coreParams.EnbaleTlbDebug
  val HistoryLength = coreParams.HistoryLength
  val EnableGHistDiff = coreParams.EnableGHistDiff
  val UbtbGHRLength = coreParams.UbtbGHRLength
  val UbtbSize = coreParams.UbtbSize
  val FtbSize = coreParams.FtbSize
  val FtbWays = coreParams.FtbWays
  val RasSize = coreParams.RasSize

  def getBPDComponents(resp_in: BranchPredictionResp, p: Parameters) = {
    coreParams.branchPredictor(resp_in, p)
  }
  val numBr = coreParams.numBr
  val TageTableInfos = coreParams.TageTableInfos
  val TageBanks = coreParams.numBr
  val SCNRows = coreParams.SCNRows
  val SCCtrBits = coreParams.SCCtrBits
  val SCHistLens = coreParams.SCHistLens
  val SCNTables = coreParams.SCNTables

  val SCTableInfos = Seq.fill(SCNTables)((SCNRows, SCCtrBits)) zip SCHistLens map {
    case ((n, cb), h) => (n, cb, h)
  }
  val ITTageTableInfos = coreParams.ITTageTableInfos
  type FoldedHistoryInfo = Tuple2[Int, Int]
  val foldedGHistInfos =
    (TageTableInfos.map{ case (nRows, h, t) =>
      if (h > 0)
        Set((h, min(log2Ceil(nRows/numBr), h)), (h, min(h, t)), (h, min(h, t-1)))
      else
        Set[FoldedHistoryInfo]()
    }.reduce(_++_).toSet ++
    SCTableInfos.map{ case (nRows, _, h) =>
      if (h > 0)
        Set((h, min(log2Ceil(nRows/TageBanks), h)))
      else
        Set[FoldedHistoryInfo]()
    }.reduce(_++_).toSet ++
    ITTageTableInfos.map{ case (nRows, h, t) =>
      if (h > 0)
        Set((h, min(log2Ceil(nRows), h)), (h, min(h, t)), (h, min(h, t-1)))
      else
        Set[FoldedHistoryInfo]()
    }.reduce(_++_) ++
      Set[FoldedHistoryInfo]((UbtbGHRLength, log2Ceil(UbtbSize)))
    ).toList
  


  val CacheLineSize = coreParams.CacheLineSize
  val CacheLineHalfWord = CacheLineSize / 16
  val ExtHistoryLength = HistoryLength + 64
  val IBufSize = coreParams.IBufSize
  val DecodeWidth = coreParams.DecodeWidth
  val RenameWidth = coreParams.RenameWidth
  val CommitWidth = coreParams.CommitWidth
  val FtqSize = coreParams.FtqSize
  val IssQueSize = coreParams.IssQueSize
  val FMACIssQueSize  = coreParams.FMACIssQueSize
  val FMISCIssQueSize = coreParams.FMISCIssQueSize
  val CSRIssQueSize   = coreParams.CSRIssQueSize
  val ALUIssQueSize   = coreParams.ALUIssQueSize
  val MDUIssQueSize   = coreParams.MDUIssQueSize
  val STAIssQueSize   = coreParams.STAIssQueSize
  val STDIssQueSize   = coreParams.STDIssQueSize
  val LDIssQueSize    = coreParams.LDIssQueSize
  val EnableLoadFastWakeUp = coreParams.EnableLoadFastWakeUp
  val NRPhyRegs = coreParams.NRPhyRegs
  val PhyRegIdxWidth = log2Up(NRPhyRegs)
  val RobSize = coreParams.RobSize
  val IntRefCounterWidth = log2Ceil(RobSize)
  val LoadQueueSize = coreParams.LoadQueueSize
  val StoreQueueSize = coreParams.StoreQueueSize
  val dpParams = coreParams.dpParams
  val exuParameters = coreParams.exuParameters
  val NRMemReadPorts = exuParameters.LduCnt + 2 * exuParameters.StuCnt
  val NRIntReadPorts = 2 * exuParameters.AluCnt + NRMemReadPorts
  val NRIntWritePorts = exuParameters.AluCnt + exuParameters.MduCnt + exuParameters.LduCnt
  val NRFpReadPorts = 3 * exuParameters.FmacCnt + exuParameters.StuCnt
  val NRFpWritePorts = exuParameters.FpExuCnt + exuParameters.LduCnt
  val LoadPipelineWidth = coreParams.LoadPipelineWidth
  val StorePipelineWidth = coreParams.StorePipelineWidth
  val StoreBufferSize = coreParams.StoreBufferSize
  val StoreBufferThreshold = coreParams.StoreBufferThreshold
  val EnsbufferWidth = coreParams.EnsbufferWidth
  val EnableLoadToLoadForward = coreParams.EnableLoadToLoadForward
  val EnableFastForward = coreParams.EnableFastForward
  val EnableLdVioCheckAfterReset = coreParams.EnableLdVioCheckAfterReset
  val EnableSoftPrefetchAfterReset = coreParams.EnableSoftPrefetchAfterReset
  val EnableCacheErrorAfterReset = coreParams.EnableCacheErrorAfterReset
  val RefillSize = coreParams.RefillSize
  val asidLen = coreParams.MMUAsidLen
  val BTLBWidth = coreParams.LoadPipelineWidth + coreParams.StorePipelineWidth
  val refillBothTlb = coreParams.refillBothTlb
  val itlbParams = coreParams.itlbParameters
  val ldtlbParams = coreParams.ldtlbParameters
  val sttlbParams = coreParams.sttlbParameters
  val btlbParams = coreParams.btlbParameters
  val l2tlbParams = coreParams.l2tlbParameters
  val NumPerfCounters = coreParams.NumPerfCounters

  val NumRs = (exuParameters.JmpCnt+1)/2 + (exuParameters.AluCnt+1)/2 + (exuParameters.MulCnt+1)/2 +
              (exuParameters.MduCnt+1)/2 + (exuParameters.FmacCnt+1)/2 +  + (exuParameters.FmiscCnt+1)/2 +
              (exuParameters.FmiscDivSqrtCnt+1)/2 + (exuParameters.LduCnt+1)/2 +
              (exuParameters.StuCnt+1)/2 + (exuParameters.StuCnt+1)/2

  val instBytes = if (HasCExtension) 2 else 4
  val instOffsetBits = log2Ceil(instBytes)

  val icacheParameters = coreParams.icacheParameters
  val dcacheParameters = coreParams.dcacheParametersOpt.getOrElse(DCacheParameters())

  // dcache block cacheline when lr for LRSCCycles - LRSCBackOff cycles
  // for constrained LR/SC loop 
  val LRSCCycles = 64
  // for lr storm
  val LRSCBackOff = 8

  // cache hierarchy configurations
  val l1BusDataWidth = 256

  // load violation predict
  val ResetTimeMax2Pow = 20 //1078576
  val ResetTimeMin2Pow = 10 //1024
  // wait table parameters
  val WaitTableSize = 1024
  val MemPredPCWidth = log2Up(WaitTableSize)
  val LWTUse2BitCounter = true
  // store set parameters
  val SSITSize = WaitTableSize
  val LFSTSize = 32
  val SSIDWidth = log2Up(LFSTSize)
  val LFSTWidth = 4
  val StoreSetEnable = true // LWT will be disabled if SS is enabled

  val loadExuConfigs = coreParams.loadExuConfigs
  val storeExuConfigs = coreParams.storeExuConfigs

  val intExuConfigs = coreParams.intExuConfigs

  val fpExuConfigs = coreParams.fpExuConfigs

  val exuConfigs = coreParams.exuConfigs

  val PCntIncrStep: Int = 6
  val numPCntHc: Int = 25
  val numPCntPtw: Int = 19

  val numCSRPCntFrontend = 8
  val numCSRPCntCtrl     = 8
  val numCSRPCntLsu      = 8
  val numCSRPCntHc       = 5
}
