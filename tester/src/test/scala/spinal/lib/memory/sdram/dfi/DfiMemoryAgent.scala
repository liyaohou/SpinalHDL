package spinal.lib.memory.sdram.dfi
import spinal.core._
import spinal.core.sim._
import spinal.lib.Stream
import spinal.lib.memory.sdram.dfi.interface.{Dfi, DfiControlInterface, DfiReadInterface, DfiWriteInterface}
import spinal.lib.sim.{SimData, SparseMemory, StreamDriver, StreamMonitor, StreamReadyRandomizer}

import scala.collection.mutable
class DfiMemoryAgent(ctrl: DfiControlInterface, wr: DfiWriteInterface, rd: DfiReadInterface, clockDomain: ClockDomain){
  def this(bus: Dfi, clockDomain: ClockDomain) {
    this(bus.control, bus.write, bus.read, clockDomain);
  }
  val memory = SparseMemory()
  def getByteAsInt(address : Long) = getByte(address).toInt & 0xFF
  def getByte(address : Long) = memory.read(address)
  def setByte(address : Long, value : Byte) = memory.write(address, value)
  def writeNotification(address : Long, value : Byte) = {}//memory.write(address, value)
  
  
  val busConfig = ctrl.config
  assert(ctrl.config == wr.config & rd.config == wr.config, "The config of DfiControlInterface is different from DfiWriteInterface.")
  var awQueueDepth = 8
  var bQueueDepth = 4
  val csCount = busConfig.chipSelectNumber
  val phaseCount = busConfig.frequencyRatio
  val cmdPhase = busConfig.cmdPhase
  val oneTaskDataNumber = busConfig.transferPerBurst/busConfig.dataRate
  val oneTaskByteNumber = busConfig.bytePerBurst
  val bankWidth = busConfig.sdram.bankWidth
  val rowWidth = busConfig.sdram.rowWidth
  val columnWidth = busConfig.sdram.columnWidth
  val idQueue = mutable.Queue[Int]()
  val rowAddrQueue = mutable.Queue[Long]()
  val columnAddrQueue = mutable.Queue[Long]()
  val bankQueue = mutable.Queue[Long]()
  val wrEnQueue = mutable.Queue[Boolean]()
  val wrAddrQueue = mutable.Queue[Long]()
  val wrByteQueue = mutable.Queue[Byte]()
  val rdAddrQueue = mutable.Queue[Long]()
  val rdEnQueue = mutable.Queue[(Boolean, Int)]()
  val rdDataQueue = mutable.Queue[Long]()
  val wProcess = mutable.Queue[(Int) => Unit]()
  val rProcess = Array.fill(phaseCount)(mutable.Queue[(Int) => Unit]())
  var qPending = 0

  val ckeProxy = ctrl.cke.simProxy()
  val csNProxy = ctrl.csN.simProxy()
  val rasNProxy = ctrl.rasN.simProxy()
  val casNProxy = ctrl.casN.simProxy()
  val weNProxy = ctrl.weN.simProxy()
  val wrEnProxy = wr.wr.map(_.wrdataEn.simProxy())
  val wrDataProxy = wr.wr.map(_.wrdata.simProxy())
  val rdEnProxy = rd.rden.map(_.simProxy())
  def selectBit(bigInt: BigInt, partIndex: Int)={
    assert(isPow2(bigInt))
    val bigIntStr = bigInt.toString(2)
    val parts = bigIntStr.grouped(2).toList
    assert(partIndex >= 0 && partIndex < parts.size)
    val SelectedBit =BigInt(parts(partIndex), 2)
    Array[Boolean](SelectedBit.testBit(0), SelectedBit.testBit(1))
  }


  clockDomain.onSamplings{
    val cke = selectBit(ckeProxy.toBigInt.asInstanceOf[BigInt], cmdPhase)
    val csN = selectBit(csNProxy.toBigInt.asInstanceOf[BigInt], cmdPhase)
    val ras = rasNProxy.toBigInt.asInstanceOf[BigInt].testBit(cmdPhase)
    val cas = casNProxy.toBigInt.asInstanceOf[BigInt].testBit(cmdPhase)
    val weN = weNProxy.toBigInt.asInstanceOf[BigInt].testBit(cmdPhase)
    val wrEn = wrEnProxy.map(_.toBoolean)
    val wrData = wrDataProxy.map(_.toLong)
    val rdEn = rdEnProxy.map(_.toBoolean)
    var rowAddr: Long = 0
    var columnAddr: Long = 0
    var bank: Long = 0
    var byteAddr: Long = 0

    //cmd and address
    if(rowAddrQueue.nonEmpty){
      rowAddr = rowAddrQueue.dequeue()
    }
    if(bankQueue.nonEmpty){
      bank = bankQueue.dequeue()
    }
    for(cs <- cke.zip(csN).map(t =>t._1 && !t._2).zipWithIndex){
      val active = cs._1 & !ras & cas & weN
      val write = cs._1 & ras & !cas & !weN
      val read = cs._1 & ras & !cas & weN
      if(active){
        rowAddrQueue.enqueue(ctrl.address.toLong)
        bankQueue.enqueue(ctrl.bank.toLong)
        idQueue.enqueue(cs._2)
      }
      
      //write
      var writeVaild: Boolean = false
      var oneTakeDataCounter: Int = 0
      if(write){
//        columnAddrQueue.enqueue(ctrl.address(columnWidth-1 downto(0)).toLong)
        columnAddr = ctrl.address(columnWidth-1 downto(0)).toLong
        byteAddr = ((cs._2 << busConfig.sdram.wordAddressWidth) + (bank << (columnWidth + rowWidth)) + (rowAddr << columnWidth) + columnAddr) << log2Up(busConfig.sdram.bytePerWord)
        for(i <- (0 until(oneTaskByteNumber)).reverse){
          wrAddrQueue.enqueue(byteAddr+i)
        }
        wProcess += {()
          for(i <- 0 until(busConfig.transferPerBurst)) {
            setByte(wrAddrQueue.dequeue(), wrByteQueue.dequeue())
          }
          oneTakeDataCounter = 0
        }
      }
      for(i <- 0 until phaseCount){
        wrEnQueue.enqueue(wrEn(i))

        if(wrEnQueue.length == busConfig.timeConfig.tPhyWrData + 1){
          writeVaild = wrEnQueue.dequeue()
        }
        if(writeVaild){
          for(j <- 0.until(busConfig.bytePerDq).reverse){
            wrByteQueue.enqueue((wrData(i) >> j*8).toByte)
          }
          if(oneTakeDataCounter == oneTaskDataNumber){
            if(wProcess.nonEmpty) wProcess.dequeue() else null
          }else{
            oneTakeDataCounter = oneTakeDataCounter + 1
          }
        }
      }

      //read
      var rdDataByte: Int = 0
      var rdData: Long = 0
      var rdByteAddress: Long = 0
      var rdVaildPhase: Int = 0
      rd.rd(rdVaildPhase).rddataValid #= false
      if(read){
        columnAddr = ctrl.address(columnWidth-1 downto(0)).toLong
        byteAddr = ((cs._2 << busConfig.sdram.wordAddressWidth) + (bank << (columnWidth + rowWidth)) + (rowAddr << columnWidth) + columnAddr) << log2Up(busConfig.sdram.bytePerWord)
        for(i <- (0 until(oneTaskDataNumber))){
          for(j <- 0 until(busConfig.bytePerDq)){
            rdByteAddress = byteAddr+i*busConfig.bytePerDq+j
            rdDataByte = getByteAsInt(rdByteAddress)
            rdData |= (BigInt(rdDataByte) << (busConfig.bytePerDq-1-j) * 8)
          }
          rdDataQueue.enqueue(rdData)
          rdData = 0
        }
      }
      for(((vaild, rdData), phase) <- rd.rd.map(t => (t.rddataValid, t.rddata)).zipWithIndex){
        if(rProcess(phase).nonEmpty & rdEnQueue.nonEmpty){
          rdEnQueue.dequeue()
          rProcess(phase).dequeue()
        }
      }
      for((en, phase) <- rdEn.zipWithIndex){
        if(en) {
          rdEnQueue.enqueue((true, phase))
          rProcess(rdVaildPhase) += {
            rd.rd(rdVaildPhase).rddataValid #= true
            rd.rd(rdVaildPhase).rddata #= rdDataQueue.dequeue()
          }
          rdVaildPhase = (rdVaildPhase + 1) % phaseCount
        }
      }
    }
  }
}