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

package rvcore.frontend

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.experimental.chiselName
import chisel3.util._
import utils._
import rvcore._

class RASEntry()(implicit p: Parameters) extends RVCOREBundle {
    val retAddr = UInt(VAddrBits.W)
    val ctr = UInt(8.W) // layer of nested call functions
}

@chiselName
class RAS(implicit p: Parameters) extends BasePredictor {
  object RASEntry {
    def apply(retAddr: UInt, ctr: UInt): RASEntry = {
      val e = Wire(new RASEntry)
      e.retAddr := retAddr
      e.ctr := ctr
      e
    }
  }

  @chiselName
  class RASStack(val rasSize: Int) extends RVCOREModule {
    val io = IO(new Bundle {
      val push_valid = Input(Bool())
      val pop_valid = Input(Bool())
      val spec_new_addr = Input(UInt(VAddrBits.W))

      val recover_sp = Input(UInt(log2Up(rasSize).W))
      val recover_top = Input(new RASEntry)
      val recover_valid = Input(Bool())
      val recover_push = Input(Bool())
      val recover_pop = Input(Bool())
      val recover_new_addr = Input(UInt(VAddrBits.W))

      val sp = Output(UInt(log2Up(rasSize).W))
      val top = Output(new RASEntry)
    })

    val debugIO = IO(new Bundle{
        val spec_push_entry = Output(new RASEntry)
        val spec_alloc_new = Output(Bool())
        val recover_push_entry = Output(new RASEntry)
        val recover_alloc_new = Output(Bool())
        val sp = Output(UInt(log2Up(rasSize).W))
        val topRegister = Output(new RASEntry)
        val out_mem = Output(Vec(RasSize, new RASEntry))
    })

    val stack = Mem(RasSize, new RASEntry)
    val sp = RegInit(0.U(log2Up(rasSize).W))
    val top = RegInit(RASEntry(0x80000000L.U, 0.U))
    val topPtr = RegInit(0.U(log2Up(rasSize).W))

    def ptrInc(ptr: UInt) = Mux(ptr === (rasSize-1).U, 0.U, ptr + 1.U)
    def ptrDec(ptr: UInt) = Mux(ptr === 0.U, (rasSize-1).U, ptr - 1.U)

    val spec_alloc_new = io.spec_new_addr =/= top.retAddr || top.ctr.andR
    val recover_alloc_new = io.recover_new_addr =/= io.recover_top.retAddr || io.recover_top.ctr.andR

    // TODO: fix overflow and underflow bugs
    def update(recover: Bool)(do_push: Bool, do_pop: Bool, do_alloc_new: Bool,
                              do_sp: UInt, do_top_ptr: UInt, do_new_addr: UInt,
                              do_top: RASEntry) = {
      when (do_push) {
        when (do_alloc_new) {
          sp     := ptrInc(do_sp)
          topPtr := do_sp
          top.retAddr := do_new_addr
          top.ctr := 0.U
          stack.write(do_sp, RASEntry(do_new_addr, 0.U))
        }.otherwise {
          when (recover) {
            sp := do_sp
            topPtr := do_top_ptr
            top.retAddr := do_top.retAddr
          }
          top.ctr := do_top.ctr + 1.U
          stack.write(do_top_ptr, RASEntry(do_new_addr, do_top.ctr + 1.U))
        }
      }.elsewhen (do_pop) {
        when (do_top.ctr === 0.U) {
          sp     := ptrDec(do_sp)
          topPtr := ptrDec(do_top_ptr)
          top := stack.read(ptrDec(do_top_ptr))
        }.otherwise {
          when (recover) {
            sp := do_sp
            topPtr := do_top_ptr
            top.retAddr := do_top.retAddr
          }
          top.ctr := do_top.ctr - 1.U
          stack.write(do_top_ptr, RASEntry(do_top.retAddr, do_top.ctr - 1.U))
        }
      }.otherwise {
        when (recover) {
          sp := do_sp
          topPtr := do_top_ptr
          top := do_top
          stack.write(do_top_ptr, do_top)
        }
      }
    }

    
    update(io.recover_valid)(
      Mux(io.recover_valid, io.recover_push,     io.push_valid),
      Mux(io.recover_valid, io.recover_pop,      io.pop_valid),
      Mux(io.recover_valid, recover_alloc_new,   spec_alloc_new),
      Mux(io.recover_valid, io.recover_sp,       sp),
      Mux(io.recover_valid, io.recover_sp - 1.U, topPtr),
      Mux(io.recover_valid, io.recover_new_addr, io.spec_new_addr),
      Mux(io.recover_valid, io.recover_top,      top))
      
    io.sp := sp
    io.top := top
    
    val resetIdx = RegInit(0.U(log2Ceil(RasSize).W))
    val do_reset = RegInit(true.B)
    when (do_reset) {
      stack.write(resetIdx, RASEntry(0x80000000L.U, 0.U))
    }
    resetIdx := resetIdx + do_reset
    when (resetIdx === (RasSize-1).U) {
      do_reset := false.B
    }

    debugIO.spec_push_entry := RASEntry(io.spec_new_addr, Mux(spec_alloc_new, 1.U, top.ctr + 1.U))
    debugIO.spec_alloc_new := spec_alloc_new
    debugIO.recover_push_entry := RASEntry(io.recover_new_addr, Mux(recover_alloc_new, 1.U, io.recover_top.ctr + 1.U))
    debugIO.recover_alloc_new := recover_alloc_new
    debugIO.sp := sp
    debugIO.topRegister := top
    for (i <- 0 until RasSize) {
        debugIO.out_mem(i) := stack.read(i.U)
    }
  }

  val spec = Module(new RASStack(RasSize))
  val spec_ras = spec.io
  val spec_top_addr = spec_ras.top.retAddr


  val s2_spec_push = WireInit(false.B)
  val s2_spec_pop = WireInit(false.B)
  val s2_full_pred = io.in.bits.resp_in(0).s2.full_pred
  // when last inst is an rvi call, fall through address would be set to the middle of it, so an addition is needed
  val s2_spec_new_addr = s2_full_pred.fallThroughAddr + Mux(s2_full_pred.last_may_be_rvi_call, 2.U, 0.U)
  spec_ras.push_valid := s2_spec_push
  spec_ras.pop_valid  := s2_spec_pop
  spec_ras.spec_new_addr := s2_spec_new_addr

  // confirm that the call/ret is the taken cfi
  s2_spec_push := io.s2_fire && s2_full_pred.hit_taken_on_call && !io.s3_redirect
  s2_spec_pop  := io.s2_fire && s2_full_pred.hit_taken_on_ret  && !io.s3_redirect

  val s2_jalr_target = io.out.resp.s2.full_pred.jalr_target
  val s2_last_target_in = s2_full_pred.targets.last
  val s2_last_target_out = io.out.resp.s2.full_pred.targets.last
  val s2_is_jalr = s2_full_pred.is_jalr
  val s2_is_ret = s2_full_pred.is_ret
  // assert(is_jalr && is_ret || !is_ret)
  when(s2_is_ret && io.ctrl.ras_enable) {
    s2_jalr_target := spec_top_addr
    // FIXME: should use s1 globally
  }
  s2_last_target_out := Mux(s2_is_jalr, s2_jalr_target, s2_last_target_in)
  
  val s3_top = RegEnable(spec_ras.top, io.s2_fire)
  val s3_sp = RegEnable(spec_ras.sp, io.s2_fire)
  val s3_spec_new_addr = RegEnable(s2_spec_new_addr, io.s2_fire)

  val s3_jalr_target = io.out.resp.s3.full_pred.jalr_target
  val s3_last_target_in = io.in.bits.resp_in(0).s3.full_pred.targets.last
  val s3_last_target_out = io.out.resp.s3.full_pred.targets.last
  val s3_is_jalr = io.in.bits.resp_in(0).s3.full_pred.is_jalr
  val s3_is_ret = io.in.bits.resp_in(0).s3.full_pred.is_ret
  // assert(is_jalr && is_ret || !is_ret)
  when(s3_is_ret && io.ctrl.ras_enable) {
    s3_jalr_target := s3_top.retAddr
    // FIXME: should use s1 globally
  }
  s3_last_target_out := Mux(s3_is_jalr, s3_jalr_target, s3_last_target_in)

  val s3_pushed_in_s2 = RegEnable(s2_spec_push, io.s2_fire)
  val s3_popped_in_s2 = RegEnable(s2_spec_pop,  io.s2_fire)
  val s3_push = io.in.bits.resp_in(0).s3.full_pred.hit_taken_on_call
  val s3_pop  = io.in.bits.resp_in(0).s3.full_pred.hit_taken_on_ret

  val s3_recover = io.s3_fire && (s3_pushed_in_s2 =/= s3_push || s3_popped_in_s2 =/= s3_pop)
  io.out.resp.s3.rasSp  := s3_sp
  io.out.resp.s3.rasTop := s3_top


  val redirect = RegNext(io.redirect)
  val do_recover = redirect.valid || s3_recover
  val recover_cfi = redirect.bits.cfiUpdate

  val retMissPred  = do_recover && redirect.bits.level === 0.U && recover_cfi.pd.isRet
  val callMissPred = do_recover && redirect.bits.level === 0.U && recover_cfi.pd.isCall
  // when we mispredict a call, we must redo a push operation
  // similarly, when we mispredict a return, we should redo a pop
  spec_ras.recover_valid := do_recover
  spec_ras.recover_push := Mux(redirect.valid, callMissPred, s3_push)
  spec_ras.recover_pop  := Mux(redirect.valid, retMissPred, s3_pop)

  spec_ras.recover_sp  := Mux(redirect.valid, recover_cfi.rasSp, s3_sp)
  spec_ras.recover_top := Mux(redirect.valid, recover_cfi.rasEntry, s3_top)
  spec_ras.recover_new_addr := Mux(redirect.valid, recover_cfi.pc + Mux(recover_cfi.pd.isRVC, 2.U, 4.U), s3_spec_new_addr)


  RVCOREPerfAccumulate("ras_s3_recover", s3_recover)
  RVCOREPerfAccumulate("ras_redirect_recover", redirect.valid)
  RVCOREPerfAccumulate("ras_s3_and_redirect_recover_at_the_same_time", s3_recover && redirect.valid)
  // TODO: back-up stack for ras
  // use checkpoint to recover RAS

  val spec_debug = spec.debugIO
  RVCOREDebug("----------------RAS----------------\n")
  RVCOREDebug(" TopRegister: 0x%x   %d \n",spec_debug.topRegister.retAddr,spec_debug.topRegister.ctr)
  RVCOREDebug("  index       addr           ctr \n")
  for(i <- 0 until RasSize){
      RVCOREDebug("  (%d)   0x%x      %d",i.U,spec_debug.out_mem(i).retAddr,spec_debug.out_mem(i).ctr)
      when(i.U === spec_debug.sp){RVCOREDebug(false,true.B,"   <----sp")}
      RVCOREDebug(false,true.B,"\n")
  }
  RVCOREDebug(s2_spec_push, "s2_spec_push  inAddr: 0x%x  inCtr: %d |  allocNewEntry:%d |   sp:%d \n",
  s2_spec_new_addr,spec_debug.spec_push_entry.ctr,spec_debug.spec_alloc_new,spec_debug.sp.asUInt)
  RVCOREDebug(s2_spec_pop, "s2_spec_pop  outAddr: 0x%x \n",io.out.resp.s2.getTarget)
  val s3_recover_entry = spec_debug.recover_push_entry
  RVCOREDebug(s3_recover && s3_push, "s3_recover_push  inAddr: 0x%x  inCtr: %d |  allocNewEntry:%d |   sp:%d \n",
    s3_recover_entry.retAddr, s3_recover_entry.ctr, spec_debug.recover_alloc_new, s3_sp.asUInt)
  RVCOREDebug(s3_recover && s3_pop, "s3_recover_pop  outAddr: 0x%x \n",io.out.resp.s3.getTarget)
  val redirectUpdate = redirect.bits.cfiUpdate
  RVCOREDebug(do_recover && callMissPred, "redirect_recover_push\n")
  RVCOREDebug(do_recover && retMissPred, "redirect_recover_pop\n")
  RVCOREDebug(do_recover, "redirect_recover(SP:%d retAddr:%x ctr:%d) \n",
      redirectUpdate.rasSp,redirectUpdate.rasEntry.retAddr,redirectUpdate.rasEntry.ctr)

  generatePerfEvent()
}
