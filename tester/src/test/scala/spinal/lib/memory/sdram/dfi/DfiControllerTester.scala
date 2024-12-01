package spinal.lib

import spinal.core.assert
import spinal.lib.bus.bmb.sim.{BmbMasterAgent, BmbMemoryAgent, BmbRegionAllocator}
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.sim.Phase
import spinal.tester.code.SpinalAnyFunSuite

import scala.util.Random

class DfiControllerTester extends SpinalAnyFunSuite {

  import spinal.core._
  import spinal.core.sim._
  import spinal.lib.bus.bmb.BmbParameter
  import spinal.lib.memory.sdram.dfi._
  import spinal.lib.memory.sdram.dfi.interface._

  import scala.collection.mutable

  test("DfiControllerTester") {
    SimConfig.withVcdWave
      .compile {
        val task: TaskParameter =
          TaskParameter(timingWidth = 5, refWidth = 23, cmdBufferSize = 1024, dataBufferSize = 1024, rspBufferSize = 1024)
        val sdramtime = SdramTiming(
          generation = 3,
          RFC = 260,
          RAS = 38,
          RP = 15,
          RCD = 15,
          WTR = 8,
          WTP = 0,
          RTP = 8,
          RRD = 6,
          REF = 64000,
          FAW = 35
          )
        val sdram = SdramConfig(
          SdramGeneration.DDR3,
          bgWidth = 0,
          cidWidth = 0,
          bankWidth = 3,
          columnWidth = 10,
          rowWidth = 15,
          dataWidth = 16,
          ddrMHZ = 200,
          ddrWrLat = 4,
          ddrRdLat = 4,
          sdramTime = sdramtime
          )
        val timeConfig = DfiTimeConfig(
          tPhyWrLat = sdram.tPhyWrlat,
          tPhyWrData = 0,
          tPhyWrCsGap = 3,
          tRddataEn = sdram.tRddataEn,
          tPhyRdlat = 4,
          tPhyRdCsGap = 3,
          tPhyRdCslat = 0,
          tPhyWrCsLat = 0
          )
        val dfiConfig: DfiConfig = DfiConfig(
          frequencyRatio = 2,
          chipSelectNumber = 2,
          dataSlice = 1,
          cmdPhase = 0,
          signalConfig = new DDRSignalConfig(),
          timeConfig = timeConfig,
          sdram = sdram
          )
        val bmbp: BmbParameter = BmbParameter(
          addressWidth = sdram.byteAddressWidth + log2Up(dfiConfig.chipSelectNumber),
          dataWidth = dfiConfig.beatWidth,
          sourceWidth = 0,
          contextWidth = 2,
          lengthWidth = 10,
          alignment = BmbParameter.BurstAlignement.WORD
          )
        val dut = DfiController(bmbp, task, dfiConfig, RowBankColumn)
        dut
      }
      .doSimUntilVoid{ dut =>
        dut.clockDomain.forkStimulus(10,resetCycles = 16)
        fork {
          sleep(160)
          dut.clockDomain.assertReset()
          sleep(20)
          dut.clockDomain.deassertReset()
        }
        val memorySize = 1 << dut.io.bmb.p.access.addressWidth
        val regions = BmbRegionAllocator(alignmentMinWidth = 6)
        val bmbAgent = new BmbMasterAgent(dut.io.bmb, dut.clockDomain){
          override def regionAllocate(sizeMax: Int): SizeMapping = regions.allocate(Random.nextInt(memorySize) & ~((1<<regions.alignmentMinWidth)-1), sizeMax, dut.io.bmb.p)
          override def regionFree(region: SizeMapping): Unit = regions.free(region)
          override def regionIsMapped(region: SizeMapping, opcode: Int): Boolean = true
        }
        while (bmbAgent.rspQueue.exists(_.nonEmpty)) {
          dut.clockDomain.waitSampling(1000)
        }
        dut.clockDomain.waitSampling(10000)
        simSuccess()
      }
  }
}
