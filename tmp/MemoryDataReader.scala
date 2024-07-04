package big_model_nic

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import common.storage._
import qdma._
import common._

/* Memory Reader
 * This Module accepts memory read request from CPU via QDMA control reg,
 * reads corresponding data according to memory address and length
 * and finally writes callback signal to memory.
 */
class MemoryDataReader extends Module {
    val io = IO(new Bundle {
        // Request sent from CPU, including address, length and callback address.
        val cpuReq      = Flipped(Decoupled(new PacketRequest))
        // QDMA H2C DMA signals
		val h2cCmd		= Decoupled(new H2C_CMD)
		val h2cData	    = Flipped(Decoupled(new H2C_DATA))
        // Callback signals, which contains callback address to write.
        val callback    = Decoupled(UInt(64.W))
        // Output data stream.
        val memData     = Decoupled(UInt(512.W))
    })

    val gradReqFifo     = XQueue(new PacketRequest, 4096)
    gradReqFifo.io.in   <> io.cpuReq
    // Gradients in execution, i.e. reading from GPU.
    val gradExcFifo     = XQueue(new PacketRequest, 512)
    // Gradient data FIFO. 
    val gradFifo        = XQueue(UInt(512.W), 4096, almostfull_threshold = 2048)

    // Used to control read memory commands
    object GradArState extends ChiselEnum {
        val sGradArIdle, sGradArReq = Value
    }
    import GradArState._

    val gradArSt = RegInit(GradArState(), sGradArIdle)

    // Counters tracing gradient buffer read progress
    val gradReqBaseAddr    = RegInit(UInt(64.W), 0.U)
    val gradReqDataRemain  = RegInit(UInt(32.W), 0.U)
    val gradReqFirstBeat   = RegInit(Bool(), false.B)

    switch (gradArSt) {
        is (sGradArIdle) {
            when (gradFifo.io.almostfull.asBool || ~gradExcFifo.io.in.ready) {
                // Gradient FIFO will be full or Callback fifo is full.
                gradArSt := sGradArIdle
            }.otherwise {
                when (gradReqFifo.io.out.valid || gradReqFirstBeat) { 
                    gradArSt := sGradArReq
                }.otherwise {
                    gradArSt := sGradArIdle
                }
            }
        }
        is (sGradArReq) {
            when (io.h2cCmd.fire) {
                gradArSt := sGradArIdle
            }.otherwise {
                gradArSt := sGradArReq
            }
        }
    }

    gradReqFifo.io.out.ready := (gradArSt === sGradArIdle
        && gradReqDataRemain === 0.U && ~gradFifo.io.almostfull.asBool
        && gradExcFifo.io.in.ready)

    when (gradReqFifo.io.out.fire) {
        gradReqBaseAddr     := gradReqFifo.io.out.bits.addr
        gradReqDataRemain   := gradReqFifo.io.out.bits.size
        gradReqFirstBeat    := true.B
    }.elsewhen (io.h2cCmd.fire) {
        gradReqBaseAddr := gradReqBaseAddr + 4096.U
        when (gradReqDataRemain(31, 12) === 0.U) {
            gradReqDataRemain   := 0.U
            gradReqFirstBeat    := false.B
        }.otherwise {
            gradReqDataRemain   := gradReqDataRemain - 4096.U
        }
    }

    gradExcFifo.io.in.valid := gradReqFifo.io.out.fire
    gradExcFifo.io.in.bits  := gradReqFifo.io.out.bits

    val h2cDataCnt = RegInit(UInt(64.W), 0.U)

    when (io.h2cData.fire) {
        when (h2cDataCnt + 64.U(64.W) === gradExcFifo.io.out.bits.size) {
            h2cDataCnt := 0.U(64.W)
        }.otherwise {
            h2cDataCnt := h2cDataCnt + 64.U(64.W)
        }
    }
    gradExcFifo.io.out.ready := (io.h2cData.fire 
        && (h2cDataCnt + 64.U(64.W) === gradExcFifo.io.out.bits.size))

    // Callback write waiting list
    // Notice that theoratically callback may not be successfully
    // to callback FIFO. But here we think this won't happen.
    val gradCallbackFifo = XQueue(UInt(64.W), 16)
    
    gradCallbackFifo.io.in.valid := gradExcFifo.io.out.fire
    gradCallbackFifo.io.in.bits  := gradExcFifo.io.out.bits.callback

    // Send H2C command
    ToZero(io.h2cCmd.bits)
    io.h2cCmd.valid     := (gradArSt === sGradArReq)
    io.h2cCmd.bits.addr := gradReqBaseAddr
    io.h2cCmd.bits.eop  := 1.U
    io.h2cCmd.bits.sop  := 1.U
    io.h2cCmd.bits.qid  := 0.U
    io.h2cCmd.bits.len  := Mux(
        gradReqDataRemain(31, 12) === 0.U, 
        gradReqDataRemain(11, 0), 
        4096.U
    )

    // H2C data handle.
    gradFifo.io.in.bits     := io.h2cData.bits.data
    // Please note that H2C data MIGHT send some useless zero bits.
    // If that happens, the beat should be thrown away!
    gradFifo.io.in.valid    := io.h2cData.valid & !io.h2cData.bits.tuser_zero_byte
    io.h2cData.ready        := gradFifo.io.in.ready

    gradFifo.io.out         <> io.memData
    gradCallbackFifo.io.out <> io.callback
}