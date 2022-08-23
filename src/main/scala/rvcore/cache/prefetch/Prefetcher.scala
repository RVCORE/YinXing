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

package rvcore.cache.prefetch

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import rvcore._
import rvcore.cache._
import utils._

trait HasPrefetchParameters extends HasRVCOREParameter {
  val bopParams = p(BOPParamsKey)
  val streamParams = p(StreamParamsKey)
}


abstract class PrefetchModule(implicit p: Parameters) extends RVCOREModule with HasPrefetchParameters
abstract class PrefetchBundle(implicit p: Parameters) extends RVCOREBundle with HasPrefetchParameters

class PrefetchReq(implicit p: Parameters) extends PrefetchBundle {
  val addr = UInt(PAddrBits.W)
  val write = Bool()

  override def toPrintable: Printable = {
    p"addr=0x${Hexadecimal(addr)} w=${write}"
  }
}

class PrefetchResp(implicit p: Parameters) extends PrefetchBundle {

}

class PrefetchFinish(implicit p: Parameters) extends PrefetchBundle {

}

class PrefetchTrain(implicit p: Parameters) extends PrefetchBundle {
  val addr = UInt(PAddrBits.W)
  val write = Bool()
  val miss = Bool() // TODO: delete this

  override def toPrintable: Printable = {
    p"addr=0x${Hexadecimal(addr)} w=${write} miss=${miss}"
  }
}
