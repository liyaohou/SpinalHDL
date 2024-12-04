//package spinal.lib.memory.sdram.dfi
//import spinal.core._
//import spinal.core.sim._
//import spinal.lib.Stream
//import spinal.lib.memory.sdram.dfi.interface.{Dfi, DfiControlInterface, DfiWriteInterface}
//import spinal.lib.sim.{SimData, SparseMemory, StreamDriver, StreamMonitor, StreamReadyRandomizer}
//
//import scala.collection.mutable
//class DfiWriteSlaveAgent(ctrl: DfiControlInterface, wr: DfiWriteInterface, clockDomain: ClockDomain){
//  def this(bus: Dfi, clockDomain: ClockDomain) {
//    this(bus.control, bus.write, clockDomain);
//  }
//  val busConfig = ctrl.config
//  assert(ctrl.config == wr.config, "The config of DfiControlInterface is different from DfiWriteInterface.")
//  var awQueueDepth = 8
//  var bQueueDepth = 4
//  val csCount = busConfig.chipSelectNumber
//  val phaseCount = busConfig.frequencyRatio
//  val cmdPhase = busConfig.cmdPhase
//  val columnWidth = busConfig.sdram.columnWidth
//  val rowAddrQueue = mutable.Queue[Long]()
//  val columnAddrQueue = mutable.Queue[Long]()
//  val bankQueue = mutable.Queue[Long]()
//  val idQueue = mutable.Queue[Int]()
//  val wProcess = Array.fill(phaseCount)(mutable.Queue[(Int) => Unit]())
//  var qPending = 0
//
//  val ckeProxy = ctrl.cke.simProxy()
//  val csNProxy = ctrl.csN.simProxy()
//  val rasNProxy = ctrl.rasN.simProxy()
//  val casNProxy = ctrl.casN.simProxy()
//  val weNProxy = ctrl.weN.simProxy()
//  val wrEnProxy = wr.wr.map(_.wrdataEn.simProxy())
//  def selectBit(bigInt: BigInt, partIndex: Int)={
//    assert(isPow2(bigInt))
//    val bigIntStr = bigInt.toString(2)
//    val parts = bigIntStr.grouped(2).toList
//    assert(partIndex >= 0 && partIndex < parts.size)
//    val SelectedBit =BigInt(parts(partIndex), 2)
//    Array[Boolean](SelectedBit.testBit(0), SelectedBit.testBit(1))
//  }
//
//  val memory = SparseMemory()
//  def getByteAsInt(address : Long) = getByte(address).toInt & 0xFF
//  def getByte(address : Long) = memory.read(address)
//  def setByte(address : Long, value : Byte) = memory.write(address, value)
//  def writeNotification(address : Long, value : Byte) = {}//memory.write(address, value)
//
//  clockDomain.onSamplings{
//    val cke = selectBit(ckeProxy.toBigInt.asInstanceOf[BigInt], cmdPhase)
//    val csN = selectBit(csNProxy.toBigInt.asInstanceOf[BigInt], cmdPhase)
//    val ras = rasNProxy.toBigInt.asInstanceOf[BigInt].testBit(cmdPhase)
//    val cas = casNProxy.toBigInt.asInstanceOf[BigInt].testBit(cmdPhase)
//    val weN = weNProxy.toBigInt.asInstanceOf[BigInt].testBit(cmdPhase)
//    val wrEn = wrEnProxy.map(_.toBoolean)
//    var rowAddr: Long = 0
//    var bank: Long = 0
//
//    if(rowAddrQueue.nonEmpty){
//      rowAddr = rowAddrQueue.dequeue()
//    }
//    if(bankQueue.nonEmpty){
//      bank = bankQueue.dequeue()
//    }
//
//    for(cs <- cke.zip(csN).map(t =>t._1 && !t._2).zipWithIndex){
//      val active = cs._1 & !ras & cas & weN
//      val write = cs._1 & ras & !cas & !weN
//      val read = cs._1 & ras & !cas & weN
//      if(active){
//        rowAddrQueue.enqueue(ctrl.address.toLong)
//        bankQueue.enqueue(ctrl.bank.toLong)
//        idQueue.enqueue(cs._2)
//      }
//      if(write){
//        columnAddrQueue.enqueue(ctrl.address(columnWidth-1 downto(0)).toLong)
//        wProcess(0) += {()
////          setByte()
//
//        }
//      }
//    }
//
//
//  }
//
//
//
//}
//
//class DfiReadSlaveAgent(){
//
//}