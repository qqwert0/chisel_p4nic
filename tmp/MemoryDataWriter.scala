package big_model_nic

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import common.storage._
import qdma._
import common._

/* Memory Writer 
 * This Module accepts memory write request from CPU via QDMA control reg,
 * writes corresponding data according to memory address and length
 * and finally writes callback signal to memory.
 */

class MemoryDataWriter extends Module {
    val io = IO(new Bundle {
        // Request sent from CPU, including address, length and callback address.
        val cpuReq      = Flipped(Decoupled(new PacketRequest))
        // QDMA H2C DMA signals
		val c2hCmd		= Decoupled(new C2H_CMD)
		val c2hData	    = Decoupled(new C2H_DATA)
        // Callback signals, which contains callback address to write.
        val callback    = Decoupled(UInt(64.W))
        // Input data stream.
        val memData     = Flipped(Decoupled(UInt(512.W)))
    })

    // Push parameter to C2H engine.
    
    object ParamWState extends ChiselEnum {
        val sParamWIdle, sParamWReq, sParamWData, sParamWCbReq = Value
    }
    import ParamWState._

    val paramWSt = RegInit(ParamWState(), sParamWIdle)

    val paramFifoBaseAddr   = RegInit(UInt(64.W), 0.U)
    val paramFifoDataRemain = RegInit(UInt(32.W), 0.U)
    val paramFifoCbAddr     = RegInit(UInt(64.W), 0.U)

    val paramCallbackFifo = XQueue(UInt(64.W), 16)

    io.cpuReq.ready := (paramWSt === sParamWIdle)

    switch (paramWSt) {
        is (sParamWIdle) {
            when (io.cpuReq.fire) {
                paramWSt := sParamWReq
            }
        }
        is (sParamWReq) {
            when (io.c2hCmd.fire) {
                paramWSt := sParamWData
            }.otherwise {
                paramWSt := sParamWReq
            }
        }
        is (sParamWData) {
            when (io.c2hData.bits.last && io.c2hData.fire) {
                when (paramFifoDataRemain === 0.U) {
                    paramWSt := sParamWCbReq
                }.otherwise {
                    paramWSt := sParamWReq
                }
            }.otherwise {
                paramWSt := sParamWData
            }
        }
        is (sParamWCbReq) {
            when (paramCallbackFifo.io.in.fire) {
                paramWSt := sParamWIdle
            }.otherwise {
                paramWSt := sParamWCbReq
            }
        }
    }

    when (io.cpuReq.fire) {
        paramFifoBaseAddr    := io.cpuReq.bits.addr
        paramFifoDataRemain  := io.cpuReq.bits.size
        paramFifoCbAddr      := io.cpuReq.bits.callback
    }.elsewhen ((paramWSt === sParamWReq) && io.c2hCmd.fire) {
        paramFifoBaseAddr := paramFifoBaseAddr + 4096.U
        when (paramFifoDataRemain(31, 12) === 0.U) {
            paramFifoDataRemain := 0.U
        }.otherwise {
            paramFifoDataRemain := paramFifoDataRemain - 4096.U
        }
    }

    // Send C2H command.
    ToZero(io.c2hCmd.bits)
    io.c2hCmd.valid         := paramWSt === sParamWReq
    io.c2hCmd.bits.qid      := 0.U
    io.c2hCmd.bits.addr     := paramFifoBaseAddr
    io.c2hCmd.bits.len      := 4096.U
    io.c2hCmd.bits.pfch_tag := 0.U

    // Send C2H data.
    val pageBeatCnt = RegInit(UInt(6.W), 0.U)
    ToZero(io.c2hData.bits)
    io.memData.ready           := io.c2hData.ready && (paramWSt === sParamWData)
    io.c2hData.valid        := io.memData.valid && (paramWSt === sParamWData)
    io.c2hData.bits.data    := io.memData.bits
    io.c2hData.bits.last    := pageBeatCnt.andR === 1.U
    io.c2hData.bits.mty     := 0.U
    io.c2hData.bits.ctrl_len:= 4096.U
    io.c2hData.bits.ctrl_qid:= 0.U 

    when ((paramWSt === sParamWData) && io.c2hData.fire) {
        pageBeatCnt := pageBeatCnt + 1.U
    }

    // Write callbacks.
    paramCallbackFifo.io.in.valid    := paramWSt === sParamWCbReq
    paramCallbackFifo.io.in.bits     := paramFifoCbAddr
    io.callback <> paramCallbackFifo.io.out
}