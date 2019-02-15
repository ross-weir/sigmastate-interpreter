package org.ergoplatform.dsl

import org.ergoplatform.{ErgoLikeContext, ErgoLikeTransaction, ErgoBox}
import org.ergoplatform.ErgoBox.{NonMandatoryRegisterId, BoxId}
import scalan.{Nullable, RType}
import scorex.crypto.hash.Digest32
import sigmastate.{AvlTreeData, SType}
import org.ergoplatform.dsl.ContractSyntax.{Token, TokenId, ErgoScript, Proposition}
import sigmastate.Values.{ErgoTree, SValue, EvaluatedValue, Constant}
import sigmastate.lang.Terms.ValueOps
import sigmastate.eval.{CostingSigmaProp, IRContext, CostingSigmaDslBuilder, Evaluation}
import sigmastate.helpers.{ErgoLikeTestProvingInterpreter, SigmaTestingCommons}
import sigmastate.interpreter.{ProverResult, ContextExtension, CostedProverResult}
import sigmastate.interpreter.Interpreter.{ScriptNameProp, ScriptEnv}
import sigmastate.utxo.ErgoLikeTestInterpreter
import special.collection.Coll
import special.sigma.{Extensions, SigmaProp, SigmaContract, AnyValue, Context, DslSyntaxExtensions, SigmaDslBuilder, TestValue}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions
import scala.util.Try

trait ContractSyntax { contract: SigmaContract =>
  override def builder: SigmaDslBuilder = new CostingSigmaDslBuilder
  val spec: ContractSpec
  val syntax = new DslSyntaxExtensions(builder)

  def Coll[T](items: T*)(implicit cT: RType[T]) = builder.Colls.fromItems(items:_*)

  def proposition(name: String, dslSpec: Proposition, scriptEnv: ScriptEnv, scriptCode: String) = {
    val env = scriptEnv.mapValues(v => v match {
      case sp: CostingSigmaProp => sp.sigmaTree
      case coll: Coll[SType#WrappedType]@unchecked =>
        val elemTpe = Evaluation.rtypeToSType(coll.tItem)
        spec.IR.builder.mkCollectionConstant[SType](coll.toArray, elemTpe)
      case _ => v
    })
    spec.mkPropositionSpec(name, dslSpec, ErgoScript(env, scriptCode))
  }

  def Env(entries: (String, Any)*): ScriptEnv = Map(entries:_*)
}
object ContractSyntax {
  type Proposition = Context => SigmaProp
  type TokenId = Coll[Byte]
  case class ErgoScript(env: ScriptEnv, code: String)
  case class Token(id: TokenId, value: Long)
}

trait SigmaContractSyntax extends SigmaContract with ContractSyntax {
  override def canOpen(ctx: Context): Boolean = ???
}

trait ContractSpec {
  val dsl: SigmaDslBuilder = CostingSigmaDslBuilder
  implicit def Coll[T](items: Array[T])(implicit cT: RType[T]) = dsl.Colls.fromArray(items)

  val IR: IRContext

  import SType.AnyOps
  implicit class DslDataOps[A](data: A)(implicit tA: RType[A]) {
    def toTreeData: Constant[SType] = {
      val treeType = Evaluation.toErgoTreeType(tA)
      val treeData = Evaluation.fromDslData(data, tRes = treeType)(IR)
      IR.builder.mkConstant(treeData.asWrappedType, Evaluation.rtypeToSType(tA))
    }
  }

  trait PropositionSpec {
    def name: String
    def dslSpec: Proposition
    def scriptSpec: ErgoScript
    def ergoTree: ErgoTree
  }
  object PropositionSpec {
    def apply(name: String, dslSpec: Proposition, scriptSpec: ErgoScript) = mkPropositionSpec(name, dslSpec, scriptSpec)
  }

  private[dsl] def mkPropositionSpec(name: String, dslSpec: Proposition, scriptSpec: ErgoScript): PropositionSpec


  trait ProtocolParty {
    def name: String
  }

  /** Represents a participant of blockchain scenario (protocol). Participants are identified by `pubKey`
    * and may have human readable names.
    * This type of participant can generate proof for input boxes. */
  trait ProvingParty extends ProtocolParty {
    /** Public key of this party represented as sigma protocol proposition.
      * Thus, it can be used in logical `&&`, `||` and `atLeast` propositions.
      * For example `(HEIGHT > 10 && bob.pubKey) || (HEIGHT <= 10 && alice.pubKey). */
    def pubKey: SigmaProp

    /** Generate proof for the given `inBox`. The input box has attached guarding proposition,
      * which is executed in the Context, specifically created for `inBox`.*/
    def prove(inBox: InputBox, extensions: Map[Byte, AnyValue] = Map()): Try[CostedProverResult]
  }
  object ProvingParty {
    def apply(name: String): ProvingParty = mkProvingParty(name)
  }
  protected def mkProvingParty(name: String): ProvingParty

  trait VerifyingParty extends ProtocolParty {
    /** Verifies the proof generated by the ProvingParty (using `prove` method) for the given `inBox`.*/
    def verify(inBox: InputBox, proverResult: ProverResult): Boolean
  }
  object VerifyingParty {
    def apply(name: String): VerifyingParty = mkVerifyingParty(name)
  }
  protected def mkVerifyingParty(name: String): VerifyingParty

  trait InputBox {
    def tx: Transaction
    def utxoBox: OutBox
    def runDsl(extensions: Map[Byte, AnyValue] = Map()): SigmaProp
    private [dsl] def toErgoContext: ErgoLikeContext
  }

  trait OutBox {
    def id: BoxId
    def tx: Transaction
    def boxIndex: Int
    def value: Long
    def propSpec: PropositionSpec
    def withTokens(tokens: Token*): OutBox
    def withRegs(regs: (NonMandatoryRegisterId, Any)*): OutBox
    def token(id: TokenId): Token
    private[dsl] def ergoBox: ErgoBox
  }

  trait Transaction {
    def block: Block
    def inputs: Seq[InputBox]
    def outputs: Seq[OutBox]
    def inBox(utxoBox: OutBox): InputBox
    def outBox(value: Long, propSpec: PropositionSpec): OutBox
    def spending(utxos: OutBox*): Transaction
  }

  trait Block {
    def height: Int
    def newTransaction(): Transaction
  }

  val MinErgValue = 1
  def error(msg: String) = sys.error(msg)
}

case class TestContractSpec(testSuite: SigmaTestingCommons)(implicit val IR: IRContext) extends ContractSpec {

  case class TestPropositionSpec(name: String, dslSpec: Proposition, scriptSpec: ErgoScript) extends PropositionSpec {
    lazy val ergoTree: ErgoTree = {
      val value = testSuite.compileWithCosting(scriptSpec.env, scriptSpec.code)
      val tree: ErgoTree = value
      tree
    }
  }

  override private[dsl] def mkPropositionSpec(name: String, dslSpec: Proposition, scriptSpec: ErgoScript) =
    TestPropositionSpec(name, dslSpec, scriptSpec)


  case class TestProvingParty(name: String) extends ProvingParty {
    private val prover = new ErgoLikeTestProvingInterpreter

    val pubKey: SigmaProp = CostingSigmaProp(prover.dlogSecrets.head.publicImage)

    import SType.AnyOps
    def prove(inBox: InputBox, extensions: Map[Byte, AnyValue] = Map()): Try[CostedProverResult] = {
      val boxToSpend = inBox.utxoBox
      val propSpec: PropositionSpec = boxToSpend.propSpec
      val bindings = extensions.mapValues { case v: TestValue[a] =>
        implicit val tA = v.tA
        val treeType = Evaluation.toErgoTreeType(tA)
        val treeData = Evaluation.fromDslData(v.value, tRes = treeType)
        IR.builder.mkConstant(treeData.asWrappedType, Evaluation.rtypeToSType(v.tA))
      }
      val ctx = inBox.toErgoContext
//      val newExtension = ContextExtension(ctx.extension.values ++ bindings)
      val env = propSpec.scriptSpec.env + (ScriptNameProp -> (propSpec.name + "_prove"))
      val prop = propSpec.ergoTree.proposition.asBoolValue
      val p = bindings.foldLeft(prover) { (p, b) => p.withContextExtender(b._1, b._2) }
      val proof = p.prove(env, prop, ctx, testSuite.fakeMessage)
      proof
    }
  }

  override protected def mkProvingParty(name: String): ProvingParty = TestProvingParty(name)

  case class TestVerifyingParty(name: String) extends VerifyingParty {
    private val verifier = new ErgoLikeTestInterpreter

    def verify(inBox: InputBox, proverResult: ProverResult) = {
      val boxToSpend = inBox.utxoBox
      val propSpec = boxToSpend.propSpec
      val ctx = inBox.toErgoContext
      val env = propSpec.scriptSpec.env + (ScriptNameProp -> (propSpec.name + "_verify"))
      val prop = propSpec.ergoTree.proposition.asBoolValue
      verifier.verify(env, prop, ctx, proverResult, testSuite.fakeMessage).get._1
    }
  }

  override protected def mkVerifyingParty(name: String): VerifyingParty = TestVerifyingParty(name)

  case class TestInputBox(tx: Transaction, utxoBox: OutBox) extends InputBox {
    private [dsl] def toErgoContext: ErgoLikeContext = {
      val propSpec = utxoBox.propSpec
      val ctx = ErgoLikeContext(
        currentHeight = tx.block.height,
        lastBlockUtxoRoot = AvlTreeData.dummy,
        minerPubkey = ErgoLikeContext.dummyPubkey,
        boxesToSpend = tx.inputs.map(_.utxoBox.ergoBox).toIndexedSeq,
        spendingTransaction = ErgoLikeTransaction(IndexedSeq(), tx.outputs.map(_.ergoBox).toIndexedSeq),
        self = utxoBox.ergoBox)
      ctx
    }
    def runDsl(extensions: Map[Byte, AnyValue] = Map()): SigmaProp = {
      val ctx = toErgoContext.toSigmaContext(IR, false, extensions)
      val res = utxoBox.propSpec.dslSpec(ctx)
      res
    }
  }

  case class TestOutBox(tx: Transaction, boxIndex: Int, value: Long, propSpec: PropositionSpec) extends OutBox {
    private var _tokens: Seq[Token] = Seq()
    private var _regs: Map[NonMandatoryRegisterId, _ <: EvaluatedValue[_ <: SType]] = Map()

    def withTokens(tokens: Token*) = { _tokens = tokens.toSeq; this }
    def withRegs(regs: (NonMandatoryRegisterId, Any)*) = {
      _regs = regs.map { case (id, v) =>
        val lifted = IR.builder.liftAny(v) match {
          case Nullable(v) => v
          case _ =>
            sys.error(s"Invalid value for register R${id.number}: $v")
        }
        (id, lifted.asInstanceOf[EvaluatedValue[_ <: SType]])
      }.toMap
      this
    }

    override def token(id: TokenId): Token = {
      val optToken = _tokens.collectFirst { case t if t.id == id => t }
      optToken.getOrElse(sys.error(s"Token with id=$id not found in the box $this"))
    }

    private[dsl] lazy val ergoBox: ErgoBox = {
      val tokens = _tokens.map { t => (Digest32 @@ t.id.toArray, t.value) }
      ErgoBox(value, propSpec.ergoTree, tx.block.height, tokens, _regs)
    }
    def id = ergoBox.id
  }

  case class TestTransaction(block: Block) extends Transaction {
    private val _inputs: ArrayBuffer[InputBox] = mutable.ArrayBuffer.empty[InputBox]
    def inputs: Seq[InputBox] = _inputs

    private val _outputs = mutable.ArrayBuffer.empty[OutBox]
    def outputs: Seq[OutBox] = _outputs

    def inBox(utxoBox: OutBox) = {
      val box = TestInputBox(this, utxoBox)
      _inputs += box
      box
    }

    def outBox(value: Long, propSpec: PropositionSpec) = {
      val box = TestOutBox(this, _outputs.size, value, propSpec)
      _outputs += box
      box
    }

    def spending(utxos: OutBox*) = {
      for (b <- utxos) inBox(b)
      this
    }

  }

  case class TestBlock(height: Int) extends Block {
    def newTransaction() = TestTransaction(this)

  }

  def block(height: Int) = TestBlock(height)

}



