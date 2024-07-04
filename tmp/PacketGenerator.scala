package big_model_nic

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import common.axi._
import common.storage._
import common.connection._
import qdma._
import common._
import cmac._
import big_model.ReliableNetwork

class PacketRequest extends Bundle {
    val addr     = Output(UInt(64.W))
    val size     = Output(UInt(32.W))
    val callback = Output(UInt(64.W))
}

class PacketGenerator (
    NUM_OF_CON_LAYER    : Int = 4
) extends Module {
    val io = IO(new Bundle {
        val cmacTx	    = Decoupled(new AXIS(512))
        val cmacRx	    = Flipped(Decoupled(new AXIS(512)))

		val c2hCmd		= Decoupled(new C2H_CMD)
		val c2hData	    = Decoupled(new C2H_DATA)
		val h2cCmd		= Decoupled(new H2C_CMD)
		val h2cData	    = Flipped(Decoupled(new H2C_DATA))
        val sAxib       = new AXIB_SLAVE

        val gradReq     = Flipped(Decoupled(new PacketRequest))
        val paramReq    = Flipped(Decoupled(new PacketRequest))
        val nodeRank    = Input(UInt(48.W))
    })

    // Network Module

    val networkInst = Module(new ReliableNetwork(
        RX_DATA_LENGTH      = 16,
        RX_BUFFER_DEPTH     = 16384,
        RX_CREDIT_DEPTH     = 16320,
        RX_ETHER_TYPE       = 0x2002,
        RX_ACK_ETHER_TYPE   = 0x2802,
        RX_TIMEOUT_THRESH   = 10000,
        TX_DATA_LENGTH      = 2,
        TX_BUFFER_DEPTH     = 16384,
        TX_CREDIT_DEPTH     = 16320,
        TX_ETHER_TYPE       = 0x2003,
        TX_ACK_ETHER_TYPE   = 0x2803,
        TX_TIMEOUT_THRESH   = 1000
    ))
    networkInst.io.nodeRank := io.nodeRank

    val netRxRt = SerialRouter(AXIS(512), 2)
    netRxRt.io.in       <> io.cmacRx
    netRxRt.io.idx      := Mux(HToN(io.cmacRx.bits.data(111, 96)) === 0x2002.U(16.W) 
        || HToN(io.cmacRx.bits.data(111, 96)) === 0x2802.U(16.W),
    0.U, 1.U)
    netRxRt.io.out(1).ready := 1.U  // Throw away unknown packets
    netRxRt.io.out(0)   <> networkInst.io.netRx

    // 1. Send parameter request

    val paramReqFifo    = XQueue(new PacketRequest, 4096)
    paramReqFifo.io.in.bits     := io.paramReq.bits
    paramReqFifo.io.in.valid    := io.paramReq.valid
    
    // When FPGA accepts a parameter request and push it into request FIFO,
    // It will send a parameter request packet at the same time.
    val layerId         = RegInit(UInt(48.W), 0.U)
    val reqPacketFifo   = XQueue(new AXIS(512), 8)
    val reqParamNum     = Wire(UInt(32.W))
    reqParamNum := Cat(Seq(0.U(2.W), io.paramReq.bits.size(31, 2)))

    io.paramReq.ready   := reqPacketFifo.io.in.ready & paramReqFifo.io.in.ready

    reqPacketFifo.io.in.valid       := paramReqFifo.io.in.fire
    reqPacketFifo.io.in.bits.last   := 1.U
    reqPacketFifo.io.in.bits.keep   := "h3ffff".U(64.W)
    reqPacketFifo.io.in.bits.data   := Cat(Seq(
        0.U(368.W),
        HToN(reqParamNum),                  // 32 bit param num, not in bytes!
        HToN(0x2001.U(16.W)),               // 16 bit ether type
        HToN(io.nodeRank),                  // 48 bit ether src = node rank
        HToN(layerId)                       // 48 bit ether dst = layer id
    ))
    // Increment the layer ID
    when (reqPacketFifo.io.in.fire) {
        layerId := layerId + 1.U(48.W)
    }
    
    // 2. Push gradient to P4

    val gradReader = Module(new MemoryDataReader)

    gradReader.io.cpuReq    <> io.gradReq
    gradReader.io.h2cCmd    <> io.h2cCmd
    gradReader.io.h2cData   <> io.h2cData

    // Now convert gradient data to packets.

    gradReader.io.memData   <> networkInst.io.dataTx

    // 3. CMAC_TX router

    val txRouter    = SerialArbiter(new AXIS(512), 2)
    txRouter.io.in(0)   <> reqPacketFifo.io.out
    txRouter.io.in(1)   <> networkInst.io.netTx
    io.cmacTx           <> txRouter.io.out

    // 4. Pull parameter from P4

    val paramWriter = Module(new MemoryDataWriter)

    paramWriter.io.cpuReq   <> paramReqFifo.io.out
    paramWriter.io.c2hCmd   <> io.c2hCmd
    paramWriter.io.c2hData  <> io.c2hData
    paramWriter.io.memData  <> networkInst.io.dataRx

    // 5. Overall callback handling.

    val callbackWriter = Module(new MemoryCallbackWriter)

    val callbackAbt = XArbiter(UInt(64.W), 2)
    
    callbackAbt.io.in(0) <> paramWriter.io.callback
    callbackAbt.io.in(1) <> gradReader.io.callback
    callbackAbt.io.out   <> callbackWriter.io.callback

    callbackWriter.io.sAxib <> io.sAxib

    // Debug module

    val dbg_cnt = RegInit(UInt(32.W), 0.U)

    when (io.h2cData.ready && ~io.h2cData.valid) {
        dbg_cnt := dbg_cnt + 1.U
    }.otherwise {
        dbg_cnt := 0.U
    }

    class ila_debug(seq:Seq[Data]) extends BaseILA(seq)
    val instIlaDbg = Module(new ila_debug(Seq(	
        io.cmacRx,
        io.cmacTx,
        io.c2hCmd,
        io.c2hData,
        io.h2cCmd,
        io.h2cData,
        networkInst.io.dataRx.valid,
        networkInst.io.dataRx.ready,
        networkInst.io.dataTx.valid,
        networkInst.io.dataTx.ready,
    )))
    instIlaDbg.connect(clock)
}

object HToN {
    def apply(in: UInt) = {
        assert(in.getWidth % 8 == 0)
        val segNum = in.getWidth / 8
        val outSeg = Wire(Vec(segNum, UInt(8.W)))

        for (i <- 0 until segNum) {
            outSeg(segNum-1-i)  := in(8*i+7, 8*i)
        }

        outSeg.asUInt
    }
}