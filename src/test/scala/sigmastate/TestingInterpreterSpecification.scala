package sigmastate

import org.scalatest.prop.{PropertyChecks, GeneratorDrivenPropertyChecks}
import org.scalatest.{PropSpec, Matchers}
import scapi.sigma.DLogProtocol.{ProveDlog, DLogProverInput}
import scorex.crypto.hash.Blake2b256
import sigmastate.Values._
import sigmastate.interpreter._
import sigmastate.lang.SigmaCompiler
import sigmastate.utxo.{CostTable, Height}
import sigmastate.lang.Terms._
import scala.util.Random



class TestingInterpreterSpecification extends PropSpec
  with PropertyChecks
  with GeneratorDrivenPropertyChecks
  with Matchers {

  import TestingInterpreter._

  implicit val soundness = GroupSettings.soundness

  property("Reduction to crypto #1") {
    forAll() { (h: Int) =>
      whenever(h > 0 && h < Int.MaxValue - 1) {
        val dk1 = DLogProverInput.random().publicImage

        val ctx = TestingContext(h)
        assert(reduceToCrypto(ctx, AND(GE(Height, IntConstant(h - 1)), dk1)).get.isInstanceOf[ProveDlog])
        assert(reduceToCrypto(ctx, AND(GE(Height, IntConstant(h)), dk1)).get.isInstanceOf[ProveDlog])
        assert(reduceToCrypto(ctx, AND(GE(Height, IntConstant(h + 1)), dk1)).get.isInstanceOf[FalseLeaf.type])

        assert(reduceToCrypto(ctx, OR(GE(Height, IntConstant(h - 1)), dk1)).get.isInstanceOf[TrueLeaf.type])
        assert(reduceToCrypto(ctx, OR(GE(Height, IntConstant(h)), dk1)).get.isInstanceOf[TrueLeaf.type])
        assert(reduceToCrypto(ctx, OR(GE(Height, IntConstant(h + 1)), dk1)).get.isInstanceOf[ProveDlog])
      }
    }
  }

  property("Reduction to crypto #2") {
    forAll() { (h: Int) =>

      whenever(h > 0 && h < Int.MaxValue - 1) {

        val dk1 = DLogProverInput.random().publicImage
        val dk2 = DLogProverInput.random().publicImage

        val ctx = TestingContext(h)

        assert(reduceToCrypto(ctx, OR(
                  AND(LE(Height, IntConstant(h + 1)), AND(dk1, dk2)),
                  AND(GT(Height, IntConstant(h + 1)), dk1)
                )).get.isInstanceOf[CAND])


        assert(reduceToCrypto(ctx, OR(
                  AND(LE(Height, IntConstant(h - 1)), AND(dk1, dk2)),
                  AND(GT(Height, IntConstant(h - 1)), dk1)
                )).get.isInstanceOf[ProveDlog])


        assert(reduceToCrypto(ctx, OR(
                  AND(LE(Height, IntConstant(h - 1)), AND(dk1, dk2)),
                  AND(GT(Height, IntConstant(h + 1)), dk1)
                )).get.isInstanceOf[FalseLeaf.type])

        assert(reduceToCrypto(ctx, OR(OR(
                  AND(LE(Height, IntConstant(h - 1)), AND(dk1, dk2)),
                  AND(GT(Height, IntConstant(h + 1)), dk1)
                ), AND(GT(Height, IntConstant(h - 1)), LE(Height, IntConstant(h + 1))))).get.isInstanceOf[TrueLeaf.type])

      }
    }
  }

  val compiler = new SigmaCompiler
  def compile(env: Map[String, Any], code: String): Value[SType] = {
    compiler.compile(env, code)
  }

  property("Evaluate ops") {
    val dk1 = ProveDlog(secrets(0).publicImage.h)

    val ctx = TestingContext(99)

    val env = Map("dk1" -> dk1)
    val prop = compile(env,
      """{
        |  let arr = Array(1, 2) ++ Array(3, 4)
        |  arr.size == 4
        |}""".stripMargin
    ).asBoolValue

    val challenge = Array.fill(32)(Random.nextInt(100).toByte)

    val proof1 = TestingInterpreter.prove(prop, ctx, challenge).get.proof
    verify(prop, ctx, proof1, challenge).getOrElse(false) shouldBe true
  }

  property("Evaluation example #1") {
    val dk1 = ProveDlog(secrets(0).publicImage.h)
    val dk2 = ProveDlog(secrets(1).publicImage.h)

    val env1 = TestingContext(99)
    val env2 = TestingContext(101)

    val prop = OR(
      AND(LE(Height, IntConstant(100)), AND(dk1, dk2)),
      AND(GT(Height, IntConstant(100)), dk1)
    )

    val challenge = Array.fill(32)(Random.nextInt(100).toByte)

    val proof1 = TestingInterpreter.prove(prop, env1, challenge).get.proof

    verify(prop, env1, proof1, challenge).getOrElse(false) shouldBe true

    verify(prop, env2, proof1, challenge).getOrElse(false) shouldBe false
  }

  property("Evaluation - no real proving - true case") {
    val prop1 = TrueLeaf

    val challenge = Array.fill(32)(Random.nextInt(100).toByte)
    val proof = NoProof
    val env = TestingContext(99)

    verify(prop1, env, proof, challenge).getOrElse(false) shouldBe true

    val prop2 = OR(TrueLeaf, FalseLeaf)
    verify(prop2, env, proof, challenge).getOrElse(false) shouldBe true

    val prop3 = AND(TrueLeaf, TrueLeaf)
    verify(prop3, env, proof, challenge).getOrElse(false) shouldBe true

    val prop4 = GT(Height, IntConstant(90))
    verify(prop4, env, proof, challenge).getOrElse(false) shouldBe true
  }

  property("Evaluation - no real proving - false case") {
    val prop1 = FalseLeaf

    val challenge = Array.fill(32)(Random.nextInt(100).toByte)
    val proof = NoProof
    val env = TestingContext(99)

    verify(prop1, env, proof, challenge).getOrElse(false) shouldBe false

    val prop2 = OR(FalseLeaf, FalseLeaf)
    verify(prop2, env, proof, challenge).getOrElse(false) shouldBe false

    val prop3 = AND(FalseLeaf, TrueLeaf)
    verify(prop3, env, proof, challenge).getOrElse(false) shouldBe false

    val prop4 = GT(Height, IntConstant(100))
    verify(prop4, env, proof, challenge).getOrElse(false) shouldBe false
  }

  property("Evaluation - hash function") {
    val bytes = "hello world".getBytes
    val hash = Blake2b256(bytes)

    val prop1 = EQ(CalcBlake2b256(ByteArrayConstant(bytes)), ByteArrayConstant(hash))

    val challenge = Array.fill(32)(Random.nextInt(100).toByte)
    val proof = NoProof
    val env = TestingContext(99)

    verify(prop1, env, proof, challenge).getOrElse(false) shouldBe true

    val prop2 = NEQ(CalcBlake2b256(ByteArrayConstant(bytes)), ByteArrayConstant(hash))

    verify(prop2, env, proof, challenge).getOrElse(false) shouldBe false

    val prop3 = EQ(CalcBlake2b256(ByteArrayConstant(bytes)), ByteArrayConstant(bytes))

    verify(prop3, env, proof, challenge).getOrElse(false) shouldBe false
  }
}


case class TestingContext(height: Int,
                          override val extension: ContextExtension = ContextExtension(values = Map())
                         ) extends Context[TestingContext] {
  override def withExtension(newExtension: ContextExtension): TestingContext = this.copy(extension = newExtension)
}

/** An interpreter for tests with 2 random secrets*/
object TestingInterpreter extends Interpreter with ProverInterpreter {
  override type CTX = TestingContext

  override val maxCost = CostTable.ScriptLimit

  override lazy val secrets: Seq[DLogProverInput] = {
    import GroupSettings.soundness

    Seq(DLogProverInput.random(), DLogProverInput.random())
  }

  override val contextExtenders: Map[Byte, ByteArrayConstant] = Map[Byte, ByteArrayConstant]()

  override def specificTransformations(context: TestingContext, tree: SValue): SValue = tree match {
    case Height => IntConstant(context.height)
    case _ => super.specificTransformations(context, tree)
  }
}
