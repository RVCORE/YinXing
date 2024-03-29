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

package utils

import chisel3._
import chisel3.util._

class RawDataModuleTemplate[T <: Data](gen: T, numEntries: Int, numRead: Int, numWrite: Int, isSync: Boolean) extends Module {
  val io = IO(new Bundle {
    val rvec  = Vec(numRead,  Input(UInt(numEntries.W)))
    val rdata = Vec(numRead,  Output(gen))
    val wen   = Vec(numWrite, Input(Bool()))
    val wvec  = Vec(numWrite, Input(UInt(numEntries.W)))
    val wdata = Vec(numWrite, Input(gen))
  })

  val data = Reg(Vec(numEntries, gen))

  // read ports
  val rvec = if (isSync) RegNext(io.rvec) else io.rvec
  for (i <- 0 until numRead) {
    assert(PopCount(rvec(i)) <= 1.U)
    io.rdata(i) := Mux1H(rvec(i), data)
  }

  // write ports
  for (i <- 0 until numEntries) {
    val w = VecInit((0 until numWrite).map(j => io.wen(j) && io.wvec(j)(i)))
    assert(PopCount(w) <= 1.U)
    when (w.asUInt.orR) {
      data(i) := Mux1H(w, io.wdata)
    }
  }
}


class SyncRawDataModuleTemplate[T <: Data](gen: T, numEntries: Int, numRead: Int, numWrite: Int)
  extends RawDataModuleTemplate(gen, numEntries, numRead, numWrite, true)
class AsyncRawDataModuleTemplate[T <: Data](gen: T, numEntries: Int, numRead: Int, numWrite: Int)
  extends RawDataModuleTemplate(gen, numEntries, numRead, numWrite, false)

class SyncDataModuleTemplate[T <: Data](
  gen: T,
  numEntries: Int,
  numRead: Int,
  numWrite: Int,
  parentModule: String,
  concatData: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val raddr = Vec(numRead,  Input(UInt(log2Ceil(numEntries).W)))
    val rdata = Vec(numRead,  Output(gen))
    val wen   = Vec(numWrite, Input(Bool()))
    val waddr = Vec(numWrite, Input(UInt(log2Ceil(numEntries).W)))
    val wdata = Vec(numWrite, Input(gen))
  })

  override def desiredName: String = s"SyncDataModuleTemplate_${parentModule}_${numEntries}entry"
  val dataType = if (concatData) UInt(gen.getWidth.W) else gen

  val maxBankEntries = if (numEntries >= 2 * 64) 64 else 16
  val numBanks = (numEntries + maxBankEntries - 1) / maxBankEntries
  def bankOffset(address: UInt): UInt = {
    if (numBanks > 1) address(log2Ceil(maxBankEntries) - 1, 0)
    else address
  }
  def bankIndex(address: UInt): UInt = {
    if (numBanks > 1) address(log2Ceil(numEntries) - 1, log2Ceil(maxBankEntries))
    else 0.U
  }

  val dataBanks = Seq.tabulate(numBanks)(i => {
    val bankEntries = if (i < numBanks - 1) maxBankEntries else numEntries - (i * maxBankEntries)
    Module(new NegedgeDataModuleTemplate(dataType, bankEntries, numRead, numWrite, parentModule))
  })

  // delay one clock
  val raddr = RegNext(io.raddr)
  val wen = RegNext(io.wen)
  val waddr = io.wen.zip(io.waddr).map(w => RegEnable(w._2, w._1))
  val wdata = if (concatData) RegNext(VecInit(io.wdata.map(w => w.asTypeOf(dataType)))) else RegNext(io.wdata)

  // input
  for ((dataBank, i) <- dataBanks.zipWithIndex) {
    dataBank.io.raddr := raddr.map(bankOffset)
    dataBank.io.wen := wen.zip(waddr).map{ case (en, addr) => en && bankIndex(addr) === i.U }
    dataBank.io.waddr := waddr.map(bankOffset)
    dataBank.io.wdata := wdata
  }

  // output
  val rdata = if (concatData) dataBanks.map(_.io.rdata.map(_.asTypeOf(gen))) else dataBanks.map(_.io.rdata)
  for (j <- 0 until numRead) {
    val index_dec = UIntToOH(bankIndex(raddr(j)), numBanks)
    io.rdata(j) := Mux1H(index_dec, rdata.map(_(j)))
  }
}

class NegedgeDataModuleTemplate[T <: Data](gen: T, numEntries: Int, numRead: Int, numWrite: Int, parentModule: String) extends Module {
  val io = IO(new Bundle {
    val raddr = Vec(numRead,  Input(UInt(log2Ceil(numEntries).W)))
    val rdata = Vec(numRead,  Output(gen))
    val wen   = Vec(numWrite, Input(Bool()))
    val waddr = Vec(numWrite, Input(UInt(log2Ceil(numEntries).W)))
    val wdata = Vec(numWrite, Input(gen))
  })

  override def desiredName: String = s"NegedgeDataModule_${parentModule}_${numEntries}entry"
  val data = Reg(Vec(numEntries, gen))

  // read ports
  for (i <- 0 until numRead) {
    val read_by = io.wen.zip(io.waddr).map(w => w._1 && w._2 === io.raddr(i))
    val addr_dec = UIntToOH(io.raddr(i), numEntries)
    when (VecInit(read_by).asUInt.orR) {
      io.rdata(i) := Mux1H(read_by, io.wdata)
    } .otherwise {
      io.rdata(i) := Mux1H(addr_dec, data)
    }
  }

  // write ports
  val waddr_dec = io.waddr.map(a => UIntToOH(a))
  for (j <- 0 until numEntries) {
    val write_wen = io.wen.zip(waddr_dec).map(w => w._1 && w._2(j))
    when (VecInit(write_wen).asUInt.orR) {
      data(j) := Mux1H(write_wen, io.wdata)
    }
  }
}

class Folded1WDataModuleTemplate[T <: Data](gen: T, numEntries: Int, numRead: Int,
  isSync: Boolean, width: Int, hasResetEn: Boolean = true) extends Module {
  val io = IO(new Bundle {
    val raddr = Vec(numRead,  Input(UInt(log2Up(numEntries).W)))
    val rdata = Vec(numRead,  Output(gen))
    val wen   = Input(Bool())
    val waddr = Input(UInt(log2Up(numEntries).W))
    val wdata = Input(gen)
    val resetEn = if (hasResetEn) Some(Input(Bool())) else None
  })

  require(width > 0 && isPow2(width))
  require(numEntries % width == 0)

  val nRows = numEntries / width

  val data = Mem(nRows, Vec(width, gen))

  val doing_reset = RegInit(true.B)
  if (hasResetEn) {
    io.resetEn.map(en => when (en) { doing_reset := true.B })
  }
  val resetRow = RegInit(0.U(log2Ceil(nRows).W))
  resetRow := resetRow + doing_reset
  when (resetRow === (nRows-1).U) { doing_reset := false.B }

  val raddr = if (isSync) RegNext(io.raddr) else io.raddr

  for (i <- 0 until numRead) {
    val addr = raddr(i) >> log2Ceil(width)
    val idx = raddr(i)(log2Ceil(width)-1, 0)
    io.rdata(i) := Mux(doing_reset, 0.U.asTypeOf(gen), data(addr)(idx))
  }

  val waddr = io.waddr >> log2Ceil(width)
  val wmask = UIntToOH(io.waddr(log2Ceil(width)-1, 0))
  val wdata = VecInit(Seq.fill(width)(io.wdata))

  when(doing_reset) {
    data.write(resetRow, 0.U.asTypeOf(Vec(width, gen)))
  }.elsewhen(io.wen) {
    data.write(waddr, wdata, wmask.asBools)
  }
}
