package big_model_nic

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import common.storage._
import qdma._
import common._

/* Memory Callback Writer 
 * This module accepts callback address and write `1` to corresponding *physical* address
 * via QDMA AXI Slave Bridge interface.
 */

class MemoryCallbackWriter extends Module {
    val io = IO(new Bundle {
        val callback    = Flipped(Decoupled(UInt(64.W)))
        val sAxib       = new AXIB_SLAVE
    })

    val callbackFifo = XQueue(UInt(64.W), 16)
    callbackFifo.io.in  <> io.callback

    // AXI slave bridge initialization
    io.sAxib.qdma_init()
    // Write callback to slave bridge.
    io.sAxib.aw.bits.addr       := Cat(Seq(callbackFifo.io.out.bits(63, 6), 0.U(6.W)))
    io.sAxib.aw.bits.size       := 2.U(3.W)
    io.sAxib.aw.bits.len        := 0.U(8.W)
    io.sAxib.aw.valid           := callbackFifo.io.out.valid
    callbackFifo.io.out.ready   := io.sAxib.aw.ready

    // Control S_AXIB data offset.
    val callbackOffFifo = XQueue(UInt(6.W), 256)
    callbackOffFifo.io.in.valid     := callbackFifo.io.out.fire
    callbackOffFifo.io.in.bits      := callbackFifo.io.out.bits(5, 0)
    io.sAxib.w.bits.data            := (1.U(63.W) << Cat(Seq(callbackOffFifo.io.out.bits, 0.U(3.W))))
    io.sAxib.w.bits.strb            := ("hf".U(63.W) << callbackOffFifo.io.out.bits)
    io.sAxib.w.bits.last            := 1.U
    io.sAxib.w.valid                := callbackOffFifo.io.out.valid
    callbackOffFifo.io.out.ready   := io.sAxib.w.ready
}