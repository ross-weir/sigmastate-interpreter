package org.ergoplatform

import java.math.BigInteger

import org.bouncycastle.math.ec.ECPoint
import org.ergoplatform.ErgoLikeContext.Height
import scalan.RType
import scalan.RType.{TupleType, PairType}
import sigmastate.Values._
import sigmastate._
import sigmastate.eval._
import sigmastate.eval.Extensions._
import sigmastate.interpreter.{ContextExtension, Context => ErgoContext}
import sigmastate.serialization.OpCodes
import sigmastate.serialization.OpCodes.OpCode
import special.collection.{Coll, CollType}
import special.sigma
import special.sigma.{WrapperType, Header, Box, AnyValue, TestValue, PreHeader}
import SType._
import RType._
import special.sigma.Extensions._

import scala.util.Try

case class BlockchainState(currentHeight: Height, lastBlockUtxoRoot: AvlTreeData)

// todo: write description
class ErgoLikeContext(val currentHeight: Height,
                      val lastBlockUtxoRoot: AvlTreeData,
                      val minerPubkey: Array[Byte],
                      val headers: Coll[Header],
                      val preHeader: PreHeader,
                      val dataInputs: IndexedSeq[ErgoBox],
                      val boxesToSpend: IndexedSeq[ErgoBox],
                      val spendingTransaction: ErgoLikeTransactionTemplate[_ <: UnsignedInput],
                      val self: ErgoBox,
                      override val extension: ContextExtension = ContextExtension(Map())
                 ) extends ErgoContext {
  assert(self == null || boxesToSpend.exists(box => box.id == self.id), s"Self box if defined should be among boxesToSpend")
  override def withExtension(newExtension: ContextExtension): ErgoLikeContext =
    new ErgoLikeContext(
      currentHeight, lastBlockUtxoRoot, minerPubkey, headers, preHeader,
      dataInputs, boxesToSpend, spendingTransaction, self, newExtension)

  def withTransaction(newSpendingTransaction: ErgoLikeTransactionTemplate[_ <: UnsignedInput]): ErgoLikeContext =
    new ErgoLikeContext(
      currentHeight, lastBlockUtxoRoot, minerPubkey, headers, preHeader,
      dataInputs, boxesToSpend, newSpendingTransaction, self, extension)

  import ErgoLikeContext._
  import Evaluation._

  override def toSigmaContext(IR: Evaluation, isCost: Boolean, extensions: Map[Byte, AnyValue] = Map()): sigma.Context = {
    implicit val IRForBox: Evaluation = IR
    val dataInputs = this.dataInputs.toArray.map(_.toTestBox(isCost)).toColl
    val inputs = boxesToSpend.toArray.map(_.toTestBox(isCost)).toColl
    val outputs = if (spendingTransaction == null)
        noOutputs.toColl
      else
        spendingTransaction.outputs.toArray.map(_.toTestBox(isCost)).toColl
    val varMap = extension.values.mapValues { case v: EvaluatedValue[_] =>
      val tVal = stypeToRType[SType](v.tpe)
      val dslData = Evaluation.toDslData(v.value, v.tpe, isCost)
      toAnyValue(dslData.asWrappedType)(tVal)
    }
    val vars = contextVars(varMap ++ extensions)
    val avlTree = CAvlTree(lastBlockUtxoRoot)
    new CostingDataContext(
      dataInputs, headers, preHeader, inputs, outputs, currentHeight, self.toTestBox(isCost), avlTree,
      minerPubkey.toColl,
      vars,
      isCost)
  }

}

object ErgoLikeContext {
  type Height = Int

  val dummyPubkey: Array[Byte] = Array.fill(32)(0: Byte)
  
  val noBoxes = IndexedSeq.empty[ErgoBox]
  val noHeaders = CostingSigmaDslBuilder.Colls.emptyColl[Header]
  val dummyPreHeader: PreHeader = null

  /** Maximimum number of headers in `headers` collection of the context. */
  val MaxHeaders = 10

  def apply(currentHeight: Height,
            lastBlockUtxoRoot: AvlTreeData,
            minerPubkey: Array[Byte],
            boxesToSpend: IndexedSeq[ErgoBox],
            spendingTransaction: ErgoLikeTransactionTemplate[_ <: UnsignedInput],
            self: ErgoBox,
            extension: ContextExtension = ContextExtension(Map())) =
    new ErgoLikeContext(currentHeight, lastBlockUtxoRoot, minerPubkey,
      noHeaders,
      dummyPreHeader,
      noBoxes,
      boxesToSpend, spendingTransaction, self, extension)


  def dummy(selfDesc: ErgoBox) = ErgoLikeContext(currentHeight = 0,
    lastBlockUtxoRoot = AvlTreeData.dummy, dummyPubkey, boxesToSpend = IndexedSeq(selfDesc),
    spendingTransaction = null, self = selfDesc)

  def fromTransaction(tx: ErgoLikeTransaction,
                      blockchainState: BlockchainState,
                      boxesReader: ErgoBoxReader,
                      inputIndex: Int): Try[ErgoLikeContext] = Try {

    val boxes = tx.inputs.map(_.boxId).map(id => boxesReader.byId(id).get)

    val proverExtension = tx.inputs(inputIndex).spendingProof.extension

    ErgoLikeContext(blockchainState.currentHeight,
      blockchainState.lastBlockUtxoRoot,
      dummyPubkey,
      boxes,
      tx,
      boxes(inputIndex),
      proverExtension)
  }

  val noInputs: Array[Box] = Array[Box]()
  val noOutputs: Array[Box] = Array[Box]()

  import special.sigma._
  import sigmastate.SType._

  def contextVars(m: Map[Byte, AnyValue])(implicit IR: Evaluation): Coll[AnyValue] = {
    val maxKey = if (m.keys.isEmpty) 0 else m.keys.max
    val res = new Array[AnyValue](maxKey + 1)
    for ((id, v) <- m) {
      assert(res(id) == null, s"register $id is defined more then once")
      res(id) = v
    }
    IR.sigmaDslBuilderValue.Colls.fromArray(res)
  }

  implicit class ErgoBoxOps(val ebox: ErgoBox) extends AnyVal {
    def toTestBox(isCost: Boolean)(implicit IR: Evaluation): Box = {
      if (ebox == null) return null
      new CostingBox(IR, isCost, ebox)
    }
  }
}

/** When interpreted evaluates to a ByteArrayConstant built from Context.minerPubkey */
case object MinerPubkey extends NotReadyValueByteArray {
  override val opCode: OpCode = OpCodes.MinerPubkeyCode
  def opType = SFunc(SContext, SCollection.SByteArray)
}

/** When interpreted evaluates to a IntConstant built from Context.currentHeight */
case object Height extends NotReadyValueInt {
  override val opCode: OpCode = OpCodes.HeightCode
  def opType = SFunc(SContext, SInt)
}

/** When interpreted evaluates to a collection of BoxConstant built from Context.boxesToSpend */
case object Inputs extends LazyCollection[SBox.type] {
  override val opCode: OpCode = OpCodes.InputsCode
  val tpe = SCollection(SBox)
  def opType = SFunc(SContext, tpe)
}

/** When interpreted evaluates to a collection of BoxConstant built from Context.spendingTransaction.outputs */
case object Outputs extends LazyCollection[SBox.type] {
  override val opCode: OpCode = OpCodes.OutputsCode
  val tpe = SCollection(SBox)
  def opType = SFunc(SContext, tpe)
}

/** When interpreted evaluates to a AvlTreeConstant built from Context.lastBlockUtxoRoot */
case object LastBlockUtxoRootHash extends NotReadyValueAvlTree {
  override val opCode: OpCode = OpCodes.LastBlockUtxoRootHashCode
  def opType = SFunc(SContext, tpe)
}


/** When interpreted evaluates to a BoxConstant built from Context.self */
case object Self extends NotReadyValueBox {
  override val opCode: OpCode = OpCodes.SelfCode
  def opType = SFunc(SContext, SBox)
}

case object Context extends NotReadyValue[SContext.type] {
  override val opCode: OpCode = OpCodes.ContextCode
  override def tpe: SContext.type = SContext
  override def opType: SFunc = SFunc(SUnit, SContext)
}
