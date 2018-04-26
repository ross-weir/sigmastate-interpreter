package sigmastate.utxo

import com.google.common.primitives.{Bytes, Longs}
import org.scalatest.TryValues._
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import org.scalatest.{Matchers, PropSpec}
import scapi.sigma.DLogProtocol.ProveDlog
import scapi.sigma.ProveDiffieHellmanTuple
import scorex.crypto.authds.avltree.batch._
import scorex.crypto.authds.{ADKey, ADValue}
import scorex.crypto.encode.Base16
import scorex.crypto.hash.{Blake2b256, Digest32}
import sigmastate.Values._
import sigmastate._
import sigmastate.interpreter.GroupSettings
import sigmastate.lang.Terms._
import sigmastate.lang._
import sigmastate.serialization.ValueSerializer
import sigmastate.utxo.ErgoBox._

class ErgoInterpreterSpecification extends SigmaTesting {

  import BoxHelpers.{fakeMessage, fakeSelf}

  property("scripts EQ/NEQ") {
    val prover1 = new ErgoProvingInterpreter
    val prover2 = new ErgoProvingInterpreter

    val verifier = new ErgoInterpreter

    val h1 = prover1.dlogSecrets.head.publicImage
    val h2 = prover2.dlogSecrets.head.publicImage

    val ctx = ErgoContext.dummy(fakeSelf)

    val e = compile(Map("h1" -> h1.bytes, "h2" -> h2.bytes), "h1 == h1")
    val exp = EQ(ByteArrayConstant(h1.bytes), ByteArrayConstant(h1.bytes))
    e shouldBe exp

    verifier.reduceToCrypto(ctx, exp)
      .get.isInstanceOf[TrueLeaf.type] shouldBe true

    verifier.reduceToCrypto(ctx, EQ(ByteArrayConstant(h1.bytes), ByteArrayConstant(h2.bytes)))
      .get.isInstanceOf[FalseLeaf.type] shouldBe true
  }

  /**
    * The script is asking for a hash function preimage. The "proof" could be replayed, so not really a proof.
    */
  property("prover enriching context") {
    val prover = new ErgoProvingInterpreter
    val preimage = prover.contextExtenders(1: Byte).value.asInstanceOf[Array[Byte]]

    val env = Map("blake" -> Blake2b256(preimage))
    val compiledScript = compile(env,
      """{
        |  blake2b256(taggedByteArray(1)) == blake
        |}
      """.stripMargin)

    val prop = EQ(CalcBlake2b256(TaggedByteArray(1)), ByteArrayConstant(Blake2b256(preimage)))
    compiledScript shouldBe prop

    val ctx = ErgoContext.dummy(fakeSelf)
    val pr = prover.prove(prop, ctx, fakeMessage).get

    val ctxv = ctx.withExtension(pr.extension)

    val verifier = new ErgoInterpreter
    verifier.verify(prop, ctx, pr.proof, fakeMessage).get shouldBe false //context w/out extensions
    verifier.verify(prop, ctxv, pr.proof, fakeMessage).get shouldBe true
  }

  property("prover enriching context 2") {
    val prover = new ErgoProvingInterpreter
    val preimage1 = prover.contextExtenders(1).value.asInstanceOf[Array[Byte]]
    val preimage2 = prover.contextExtenders(2).value.asInstanceOf[Array[Byte]]

    val env = Map("blake" -> Blake2b256(preimage2 ++ preimage1))
    val compiledScript = compile(env,
      """{
        |  blake2b256(taggedByteArray(2) ++ taggedByteArray(1)) == blake
        |}
      """.stripMargin)

    val prop = EQ(CalcBlake2b256(AppendBytes(TaggedByteArray(2), TaggedByteArray(1))),
      ByteArrayConstant(Blake2b256(preimage2 ++ preimage1)))
    compiledScript shouldBe prop

    val ctx = ErgoContext.dummy(fakeSelf)
    val pr = prover.prove(prop, ctx, fakeMessage).get

    val ctxv = ctx.withExtension(pr.extension)

    val verifier = new ErgoInterpreter
    verifier.verify(prop, ctx, pr.proof, fakeMessage).get shouldBe false //context w/out extensions
    verifier.verify(prop, ctxv, pr.proof, fakeMessage).get shouldBe true
  }

  property("prover enriching context - xor") {
    val v1 = Base16.decode("abcdef7865").get
    val k1 = 21: Byte

    val v2 = Base16.decode("10abdca345").get
    val k2 = 22: Byte

    val r = Base16.decode("bb6633db20").get

    val prover = new ErgoProvingInterpreter()
      .withContextExtender(k1, ByteArrayConstant(v1))
      .withContextExtender(k2, ByteArrayConstant(v2))

    val env = Map("k1" -> k1.toInt, "k2" -> k2.toInt, "r" -> r)
    val compiledScript = compile(env,
      """{
        |  (taggedByteArray(k1) | taggedByteArray(k2)) == r
        |}
      """.stripMargin)

    val prop = EQ(Xor(TaggedByteArray(k1), TaggedByteArray(k2)), ByteArrayConstant(r))
    compiledScript shouldBe prop

    val ctx = ErgoContext.dummy(fakeSelf)
    val pr = prover.prove(prop, ctx, fakeMessage).get

    val ctxv = ctx.withExtension(pr.extension)

    val verifier = new ErgoInterpreter
    verifier.verify(prop, ctx, pr.proof, fakeMessage).get shouldBe false //context w/out extensions
    verifier.verify(prop, ctxv, pr.proof, fakeMessage).get shouldBe true
  }

  property("context enriching mixed w. crypto") {
    val prover = new ErgoProvingInterpreter
    val preimage = prover.contextExtenders(1).value.asInstanceOf[Array[Byte]]
    val pubkey = prover.dlogSecrets.head.publicImage

    val env = Map("blake" -> Blake2b256(preimage), "pubkey" -> pubkey)
    val compiledScript = compile(env,
      """{
        |  pubkey && blake2b256(taggedByteArray(1)) == blake
        |}
      """.stripMargin)
    val prop = AND(
      pubkey,
      EQ(CalcBlake2b256(TaggedByteArray(1)), ByteArrayConstant(Blake2b256(preimage)))
    )
    compiledScript shouldBe prop

    val ctx = ErgoContext.dummy(fakeSelf)
    val pr = prover.prove(prop, ctx, fakeMessage).get

    val ctxv = ctx.withExtension(pr.extension)

    pr.proof.isInstanceOf[SchnorrNode] shouldBe true

    val verifier = new ErgoInterpreter
    verifier.verify(prop, ctx, pr.proof, fakeMessage).getOrElse(false) shouldBe false //context w/out extensions
    verifier.verify(prop, ctxv, pr.proof, fakeMessage).get shouldBe true
  }

  property("context enriching mixed w. crypto 2") {
    val prover = new ErgoProvingInterpreter
    val preimage1 = prover.contextExtenders(1).value.asInstanceOf[Array[Byte]]
    val preimage2 = prover.contextExtenders(2).value.asInstanceOf[Array[Byte]]
    val pubkey = prover.dlogSecrets.head.publicImage

    val env = Map("blake" -> Blake2b256(preimage1 ++ preimage2), "pubkey" -> pubkey)
    val compiledScript = compile(env,
      """{
        |  pubkey && blake2b256(taggedByteArray(1) ++ taggedByteArray(2)) == blake
        |}
      """.stripMargin)

    val prop = AND(
      pubkey,
      EQ(
        CalcBlake2b256(AppendBytes(TaggedByteArray(1), TaggedByteArray(2))),
        ByteArrayConstant(Blake2b256(preimage1 ++ preimage2))
      )
    )
    compiledScript shouldBe prop

    val ctx = ErgoContext.dummy(fakeSelf)
    val pr = prover.prove(prop, ctx, fakeMessage).get

    val ctxv = ctx.withExtension(pr.extension)

    pr.proof.isInstanceOf[SchnorrNode] shouldBe true

    val verifier = new ErgoInterpreter
    verifier.verify(prop, ctx, pr.proof, fakeMessage).getOrElse(false) shouldBe false //context w/out extensions
    verifier.verify(prop, ctxv, pr.proof, fakeMessage).get shouldBe true
  }

  /**
    * Atomic cross-chain trading example:
    * Alice(A) has coins in chain 1, Bob(B) has coins in chain 2, they want to exchange them atomically and with no
    * any trusted mediate.
    *
    * Alternative protocol for Bitcoin is described in this forum message,
    * https://bitcointalk.org/index.php?topic=193281.msg2224949#msg2224949,
    * this implementation is simpler. In particular, only one transaction(one output) is required per party.
    */
  property("atomic cross-chain trading") {
    val proverA = new ErgoProvingInterpreter
    val proverB = new ErgoProvingInterpreter
    val pubkeyA = proverA.dlogSecrets.head.publicImage
    val pubkeyB = proverB.dlogSecrets.head.publicImage
    val verifier = new ErgoInterpreter

    val x = proverA.contextExtenders(1).value.asInstanceOf[Array[Byte]]
    val hx = ByteArrayConstant(Blake2b256(x))

    val height1 = 100000
    val height2 = 50000

    val deadlineA = 1000
    val deadlineB = 500

    val env = Map(
      "height1" -> height1, "height2" -> height2,
      "deadlineA" -> deadlineA, "deadlineB" -> deadlineB,
      "pubkeyA" -> pubkeyA, "pubkeyB" -> pubkeyB, "hx" -> hx)
    val prop1 = compile(env,
      """{
        |  anyOf(Array(
        |    HEIGHT > height1 + deadlineA && pubkeyA,
        |    pubkeyB && blake2b256(taggedByteArray(1)) == hx
        |  ))
        |}""".stripMargin).asBoolValue

    //chain1 script
    val prop1Tree = OR(
      AND(GT(Height, Plus(IntConstant(height1), IntConstant(deadlineA))), pubkeyA),
      AND(pubkeyB, EQ(CalcBlake2b256(TaggedByteArray(1)), hx))
    )
    prop1 shouldBe prop1Tree

    val prop2 = compile(env,
      """{
        |  anyOf(Array(
        |    HEIGHT > height2 + deadlineB && pubkeyB,
        |    pubkeyA && blake2b256(taggedByteArray(1)) == hx
        |  ))
        |}
      """.stripMargin).asBoolValue

    //chain2 script
    val prop2Tree = OR(
      AND(GT(Height, Plus(IntConstant(height2), IntConstant(deadlineB))), pubkeyB),
      AND(pubkeyA, EQ(CalcBlake2b256(TaggedByteArray(1)), hx))
    )
    prop2 shouldBe prop2Tree

    //Preliminary checks:

    //B can't spend coins of A in chain1 (generate a valid proof)
    val ctxf1 = ErgoContext(
      currentHeight = height1 + 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)
    proverB.prove(prop1, ctxf1, fakeMessage).isSuccess shouldBe false

    //A can't withdraw her coins in chain1 (generate a valid proof)
    proverA.prove(prop1, ctxf1, fakeMessage).isSuccess shouldBe false

    //B cant't withdraw his coins in chain2 (generate a valid proof)
    val ctxf2 = ErgoContext(
      currentHeight = height2 + 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(), spendingTransaction = null, self = fakeSelf)
    proverB.prove(prop2, ctxf2, fakeMessage).isSuccess shouldBe false

    //Successful run below:

    //A spends coins of B in chain2
    val ctx1 = ErgoContext(
      currentHeight = height2 + 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)
    val pr = proverA.prove(prop2, ctx1, fakeMessage).get
    verifier.verify(prop2, ctx1, pr, fakeMessage).get shouldBe true

    //B extracts preimage x of hx
    val t = pr.extension.values(1)
    val proverB2 = proverB.withContextExtender(1, t.asInstanceOf[ByteArrayConstant])

    //B spends coins of A in chain1 with knowledge of x
    val ctx2 = ErgoContext(
      currentHeight = height1 + 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)
    val pr2 = proverB2.prove(prop1, ctx2, fakeMessage).get
    verifier.verify(prop1, ctx2, pr2, fakeMessage).get shouldBe true
  }

  /**
    * Whether A or B, or both are able to sign a transaction
    */
  property("simplest linear-sized ring signature (1-out-of-2 OR)") {
    val proverA = new ErgoProvingInterpreter
    val proverB = new ErgoProvingInterpreter
    val proverC = new ErgoProvingInterpreter
    val verifier = new ErgoInterpreter

    val pubkeyA = proverA.dlogSecrets.head.publicImage
    val pubkeyB = proverB.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyA, "pubkeyB" -> pubkeyB)
    val compiledProp = compile(env, """pubkeyA || pubkeyB""")

    val prop = OR(pubkeyA, pubkeyB)
    compiledProp shouldBe prop

    val ctx = ErgoContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)

    val prA = proverA.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prA, fakeMessage).get shouldBe true

    val prB = proverB.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prB, fakeMessage).get shouldBe true

    proverC.prove(prop, ctx, fakeMessage).isFailure shouldBe true
  }

  property("simplest linear-sized ring signature (1-out-of-3 OR)") {
    val proverA = new ErgoProvingInterpreter
    val proverB = new ErgoProvingInterpreter
    val proverC = new ErgoProvingInterpreter
    val verifier = new ErgoInterpreter

    val pubkeyA = proverA.dlogSecrets.head.publicImage
    val pubkeyB = proverB.dlogSecrets.head.publicImage
    val pubkeyC = proverC.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyA, "pubkeyB" -> pubkeyB, "pubkeyC" -> pubkeyC)
    val compiledProp = compile(env, """anyOf(Array(pubkeyA, pubkeyB, pubkeyC))""")

    val prop = OR(pubkeyA, pubkeyB, pubkeyC)
    compiledProp shouldBe prop

    val ctx = ErgoContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)

    val prA = proverA.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prA, fakeMessage).get shouldBe true

    val prB = proverB.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prB, fakeMessage).get shouldBe true

    val prC = proverC.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prC, fakeMessage).get shouldBe true
  }

  //two secrets are known, nevertheless, one will be simulated
  property("simplest linear-sized ring signature (1-out-of-4 OR), all secrets are known") {
    val proverA = new ErgoProvingInterpreter

    val verifier = new ErgoInterpreter

    val pubkeyA1 = proverA.dlogSecrets(0).publicImage
    val pubkeyA2 = proverA.dlogSecrets(1).publicImage
    val pubkeyA3 = proverA.dlogSecrets(2).publicImage
    val pubkeyA4 = proverA.dlogSecrets(3).publicImage

    val env = Map("pubkeyA1" -> pubkeyA1, "pubkeyA2" -> pubkeyA2, "pubkeyA3" -> pubkeyA3, "pubkeyA4" -> pubkeyA4)
    val compiledProp = compile(env, """anyOf(Array(pubkeyA1, pubkeyA2, pubkeyA3, pubkeyA4))""")

    val prop = OR(Seq(pubkeyA1, pubkeyA2, pubkeyA3, pubkeyA4))
    compiledProp shouldBe prop

    val ctx = ErgoContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)

    val prA = proverA.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prA, fakeMessage).get shouldBe true
  }

  property("complex sig scheme - OR of two ANDs") {
    val proverA = new ErgoProvingInterpreter
    val proverB = new ErgoProvingInterpreter
    val proverC = new ErgoProvingInterpreter
    val proverD = new ErgoProvingInterpreter

    val verifier = new ErgoInterpreter

    val pubkeyA = proverA.dlogSecrets.head.publicImage
    val pubkeyB = proverB.dlogSecrets.head.publicImage
    val pubkeyC = proverC.dlogSecrets.head.publicImage
    val pubkeyD = proverD.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyA, "pubkeyB" -> pubkeyB, "pubkeyC" -> pubkeyC, "pubkeyD" -> pubkeyD)
    val compiledProp = compile(env, """pubkeyA && pubkeyB || pubkeyC && pubkeyD""")

    val prop = OR(AND(pubkeyA, pubkeyB), AND(pubkeyC, pubkeyD))
    compiledProp shouldBe prop

    val ctx = ErgoContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)

    proverA.prove(prop, ctx, fakeMessage).isFailure shouldBe true
    proverB.prove(prop, ctx, fakeMessage).isFailure shouldBe true
    proverC.prove(prop, ctx, fakeMessage).isFailure shouldBe true
    proverD.prove(prop, ctx, fakeMessage).isFailure shouldBe true

    val proverAB = proverA.withSecrets(Seq(proverB.dlogSecrets.head))
    val pr = proverAB.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, pr, fakeMessage).get shouldBe true

    val proverCD = proverC.withSecrets(Seq(proverD.dlogSecrets.head))
    val pr2 = proverCD.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, pr2, fakeMessage).get shouldBe true
  }

  property("complex sig scheme - OR of AND and OR") {
    val proverA = new ErgoProvingInterpreter
    val proverB = new ErgoProvingInterpreter
    val proverC = new ErgoProvingInterpreter
    val proverD = new ErgoProvingInterpreter

    val verifier = new ErgoInterpreter

    val pubkeyA = proverA.dlogSecrets.head.publicImage
    val pubkeyB = proverB.dlogSecrets.head.publicImage
    val pubkeyC = proverC.dlogSecrets.head.publicImage
    val pubkeyD = proverD.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyA, "pubkeyB" -> pubkeyB, "pubkeyC" -> pubkeyC, "pubkeyD" -> pubkeyD)
    val compiledProp = compile(env, """pubkeyA && pubkeyB || (pubkeyC || pubkeyD)""")

    val prop = OR(AND(pubkeyA, pubkeyB), OR(pubkeyC, pubkeyD))
    compiledProp shouldBe prop

    val ctx = ErgoContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)

    proverA.prove(prop, ctx, fakeMessage).isFailure shouldBe true
    proverB.prove(prop, ctx, fakeMessage).isFailure shouldBe true

    val prC = proverC.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prC, fakeMessage).get shouldBe true

    val prD = proverD.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prD, fakeMessage).get shouldBe true

    val proverAB = proverA.withSecrets(Seq(proverB.dlogSecrets.head))
    val pr = proverAB.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, pr, fakeMessage).get shouldBe true
  }

  property("complex sig scheme - AND of two ORs") {
    val proverA = new ErgoProvingInterpreter
    val proverB = new ErgoProvingInterpreter
    val proverC = new ErgoProvingInterpreter
    val proverD = new ErgoProvingInterpreter

    val verifier = new ErgoInterpreter

    val pubkeyA = proverA.dlogSecrets.head.publicImage
    val pubkeyB = proverB.dlogSecrets.head.publicImage
    val pubkeyC = proverC.dlogSecrets.head.publicImage
    val pubkeyD = proverD.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyA, "pubkeyB" -> pubkeyB, "pubkeyC" -> pubkeyC, "pubkeyD" -> pubkeyD)
    val compiledProp = compile(env, """(pubkeyA || pubkeyB) && (pubkeyC || pubkeyD)""")

    val prop = AND(OR(pubkeyA, pubkeyB), OR(pubkeyC, pubkeyD))
    compiledProp shouldBe prop

    val ctx = ErgoContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)

    proverA.prove(prop, ctx, fakeMessage).isFailure shouldBe true
    proverB.prove(prop, ctx, fakeMessage).isFailure shouldBe true
    proverC.prove(prop, ctx, fakeMessage).isFailure shouldBe true
    proverD.prove(prop, ctx, fakeMessage).isFailure shouldBe true

    val proverAC = proverA.withSecrets(Seq(proverC.dlogSecrets.head))
    val pr = proverAC.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, pr, fakeMessage).get shouldBe true

    val proverBD = proverB.withSecrets(Seq(proverD.dlogSecrets.head))
    val pr2 = proverBD.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, pr2, fakeMessage).get shouldBe true
  }

  property("complex sig scheme - AND of AND and OR") {
    val proverA = new ErgoProvingInterpreter
    val proverB = new ErgoProvingInterpreter
    val proverC = new ErgoProvingInterpreter
    val proverD = new ErgoProvingInterpreter

    val verifier = new ErgoInterpreter

    val pubkeyA = proverA.dlogSecrets.head.publicImage
    val pubkeyB = proverB.dlogSecrets.head.publicImage
    val pubkeyC = proverC.dlogSecrets.head.publicImage
    val pubkeyD = proverD.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyA, "pubkeyB" -> pubkeyB, "pubkeyC" -> pubkeyC, "pubkeyD" -> pubkeyD)
    val compiledProp = compile(env, """(pubkeyA && pubkeyB) && (pubkeyC || pubkeyD)""")

    val prop = AND(AND(pubkeyA, pubkeyB), OR(pubkeyC, pubkeyD))
    compiledProp shouldBe prop

    val ctx = ErgoContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)

    proverA.prove(prop, ctx, fakeMessage).isFailure shouldBe true
    proverB.prove(prop, ctx, fakeMessage).isFailure shouldBe true
    proverC.prove(prop, ctx, fakeMessage).isFailure shouldBe true
    proverD.prove(prop, ctx, fakeMessage).isFailure shouldBe true

    val proverAB = proverA.withSecrets(Seq(proverB.dlogSecrets.head))
    proverAB.prove(prop, ctx, fakeMessage).isFailure shouldBe true

    val proverABC = proverAB.withSecrets(Seq(proverC.dlogSecrets.head))
    val prABC = proverABC.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prABC, fakeMessage).get shouldBe true

    val proverABD = proverAB.withSecrets(Seq(proverC.dlogSecrets.head))
    val prABD = proverABD.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prABD, fakeMessage).get shouldBe true
  }

  property("complex sig scheme - OR of two ORs") {
    val proverA = new ErgoProvingInterpreter
    val proverB = new ErgoProvingInterpreter
    val proverC = new ErgoProvingInterpreter
    val proverD = new ErgoProvingInterpreter

    val verifier = new ErgoInterpreter

    val pubkeyA = proverA.dlogSecrets.head.publicImage
    val pubkeyB = proverB.dlogSecrets.head.publicImage
    val pubkeyC = proverC.dlogSecrets.head.publicImage
    val pubkeyD = proverD.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyA, "pubkeyB" -> pubkeyB, "pubkeyC" -> pubkeyC, "pubkeyD" -> pubkeyD)
    val compiledProp = compile(env, """(pubkeyA || pubkeyB) || (pubkeyC || pubkeyD)""")

    val prop = OR(OR(pubkeyA, pubkeyB), OR(pubkeyC, pubkeyD))
    compiledProp shouldBe prop

    val ctx = ErgoContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)

    val prA = proverA.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prA, fakeMessage).get shouldBe true

    val prB = proverB.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prB, fakeMessage).get shouldBe true

    val prC = proverC.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prC, fakeMessage).get shouldBe true

    val prD = proverD.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prD, fakeMessage).get shouldBe true
  }

  property("complex sig scheme - OR w. predicate") {
    val proverA = new ErgoProvingInterpreter
    val proverB = new ErgoProvingInterpreter
    val proverC = new ErgoProvingInterpreter

    val verifier = new ErgoInterpreter

    val pubkeyA = proverA.dlogSecrets.head.publicImage
    val pubkeyB = proverB.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyA, "pubkeyB" -> pubkeyB)
    val compiledProp = compile(env, """anyOf(Array(pubkeyA, pubkeyB, HEIGHT > 500))""")

    val prop = OR(pubkeyA, pubkeyB, GT(Height, IntConstant(500)))
    compiledProp shouldBe prop

    val ctx1 = ErgoContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)
    val prA = proverA.prove(prop, ctx1, fakeMessage).get
    verifier.verify(prop, ctx1, prA, fakeMessage).get shouldBe true
    val prB = proverB.prove(prop, ctx1, fakeMessage).get
    verifier.verify(prop, ctx1, prB, fakeMessage).get shouldBe true
    proverC.prove(prop, ctx1, fakeMessage).isFailure shouldBe true

    val ctx2 = ErgoContext(
      currentHeight = 501,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)
    val prC = proverC.prove(prop, ctx2, fakeMessage).get
    verifier.verify(prop, ctx2, prC, fakeMessage).get shouldBe true
  }

  property("complex sig scheme - OR of OR and AND w. predicate") {
    val proverA = new ErgoProvingInterpreter
    val proverB = new ErgoProvingInterpreter
    val proverC = new ErgoProvingInterpreter

    val verifier = new ErgoInterpreter

    val pubkeyA = proverA.dlogSecrets.head.publicImage
    val pubkeyB = proverB.dlogSecrets.head.publicImage
    val pubkeyC = proverC.dlogSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyA, "pubkeyB" -> pubkeyB, "pubkeyC" -> pubkeyC)
    val compiledProp = compile(env, """anyOf(Array(pubkeyA || pubkeyB, pubkeyC && HEIGHT > 500))""")

    val prop = OR(OR(pubkeyA, pubkeyB), AND(pubkeyC, GT(Height, IntConstant(500))))
    compiledProp shouldBe prop

    val ctx1 = ErgoContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)

    val prA = proverA.prove(prop, ctx1, fakeMessage).get
    verifier.verify(prop, ctx1, prA, fakeMessage).get shouldBe true
    val prB = proverB.prove(prop, ctx1, fakeMessage).get
    verifier.verify(prop, ctx1, prB, fakeMessage).get shouldBe true
    proverC.prove(prop, ctx1, fakeMessage).isFailure shouldBe true


    val ctx2 = ErgoContext(
      currentHeight = 501,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)

    val prA2 = proverA.prove(prop, ctx2, fakeMessage).get
    verifier.verify(prop, ctx2, prA2, fakeMessage).get shouldBe true
    val prB2 = proverB.prove(prop, ctx2, fakeMessage).get
    verifier.verify(prop, ctx2, prB2, fakeMessage).get shouldBe true
    val prC2 = proverC.prove(prop, ctx2, fakeMessage).get
    verifier.verify(prop, ctx2, prC2, fakeMessage).get shouldBe true
  }

  property("DH tuple") {
    val prover = new ErgoProvingInterpreter
    val fakeProver = new ErgoProvingInterpreter

    val verifier = new ErgoInterpreter

    val secret = prover.dhSecrets.head

    val ci = secret.commonInput

    val prop = ProveDiffieHellmanTuple(ci.g, ci.h, ci.u, ci.v)
    val wrongProp = ProveDiffieHellmanTuple(ci.g, ci.h, ci.u, ci.u)

    val ctx = ErgoContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)

    val pr = prover.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, pr, fakeMessage).get shouldBe true

    fakeProver.prove(prop, ctx, fakeMessage).isSuccess shouldBe false
    prover.prove(wrongProp, ctx, fakeMessage).isSuccess shouldBe false
  }

  property("DH tuple - simulation") {
    val proverA = new ErgoProvingInterpreter
    val proverB = new ErgoProvingInterpreter

    val verifier = new ErgoInterpreter

    val pubkeyA = proverA.dlogSecrets.head.publicImage
    val pubdhB = proverB.dhSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyA, "pubdhB" -> pubdhB)
    val compiledProp = compile(env, """pubkeyA || pubdhB""")

    val prop = OR(pubkeyA, pubdhB)
    compiledProp shouldBe prop

    val ctx = ErgoContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)

    val prA = proverA.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prA, fakeMessage).get shouldBe true
  }

  property("DH tuple and DLOG") {
    val proverA = new ErgoProvingInterpreter
    val proverB = new ErgoProvingInterpreter

    val verifier = new ErgoInterpreter

    val pubkeyA = proverA.dlogSecrets.head.publicImage
    val pubdhA = proverA.dhSecrets.head.publicImage

    val env = Map("pubkeyA" -> pubkeyA, "pubdhA" -> pubdhA)
    val compiledProp = compile(env, """pubkeyA && pubdhA""")

    val prop = AND(pubkeyA, pubdhA)
    compiledProp shouldBe prop

    val ctx = ErgoContext(
      currentHeight = 1,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction = null,
      self = fakeSelf)

    val prA = proverA.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, prA, fakeMessage).get shouldBe true

    proverB.prove(prop, ctx, fakeMessage).isSuccess shouldBe false
  }

  property("mixing scenario w. timeout") {
    val proverA = new ErgoProvingInterpreter
    val proverB = new ErgoProvingInterpreter

    val verifier = new ErgoInterpreter

    val pubkeyA = proverA.dlogSecrets.head.publicImage
    val pubkeyA2 = proverA.dlogSecrets.head.publicImage

    val pubkeyB = proverB.dlogSecrets.head.publicImage
    val pubkeyB2 = proverB.dlogSecrets.head.publicImage

    val newBox1 = new ErgoBoxCandidate(10, pubkeyB2)
    val newBox2 = new ErgoBoxCandidate(10, pubkeyA2)

    val newBoxes = IndexedSeq(newBox1, newBox2)

    val properBytes = Bytes.concat(newBoxes.map(_.bytesWithNoRef): _*)

    val properHash = Blake2b256(properBytes)

    val spendingTransaction = ErgoTransaction(IndexedSeq(), newBoxes)

    def mixingRequestProp(sender: ProveDlog, timeout: Int) = {
      val env = Map("sender" -> sender, "timeout" -> timeout, "properHash" -> properHash)
      val compiledProp = compile(env,
        """{
          |  let notTimePassed = HEIGHT <= timeout
          |  let outBytes = OUTPUTS.map(fun (box: Box) = box.bytesWithNoRef)
          |  let outSumBytes = outBytes.fold(EmptyByteArray, fun (arr1: ByteArray, arr2: ByteArray) = arr2 ++ arr1)
          |  let timePassed = HEIGHT > timeout
          |  notTimePassed && blake2b256(outSumBytes) == properHash || timePassed && sender
           }""".stripMargin).asBoolValue

      val prop = OR(
        AND(LE(Height, IntConstant(timeout)),
          EQ(CalcBlake2b256(Fold.sumBytes(MapCollection(Outputs, 21, ExtractBytesWithNoRef(TaggedBox(21))))),
            ByteArrayConstant(properHash))),
        AND(GT(Height, IntConstant(timeout)), sender)
      )
      compiledProp shouldBe prop
      compiledProp
    }

    val ctx = ErgoContext(
      currentHeight = 50,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction,
      self = fakeSelf)

    //before timeout
    val prA = proverA.prove(mixingRequestProp(pubkeyA, 100), ctx, fakeMessage).get
    verifier.verify(mixingRequestProp(pubkeyA, 100), ctx, prA, fakeMessage).get shouldBe true
    verifier.verify(mixingRequestProp(pubkeyB, 100), ctx, prA, fakeMessage).get shouldBe true

    //after timeout
    val prA2 = proverA.prove(mixingRequestProp(pubkeyA, 40), ctx, fakeMessage).get
    verifier.verify(mixingRequestProp(pubkeyA, 40), ctx, prA2, fakeMessage).get shouldBe true
    verifier.verify(mixingRequestProp(pubkeyB, 40), ctx, prA2, fakeMessage).isSuccess shouldBe false
  }

  property("map + sum") {
    val prover = new ErgoProvingInterpreter
    val verifier = new ErgoInterpreter

    val pubkey = prover.dlogSecrets.head.publicImage

    val env = Map("pubkey" -> pubkey)
    val prop = compile(env,
      """{
        |  let outValues = OUTPUTS.map(fun (box: Box) = box.value)
        |  pubkey && outValues.fold(0, fun (x: Int, y: Int) = y + x) > 20
         }""".stripMargin).asBoolValue

    val propExp = AND(pubkey, GT(Fold.sum(MapCollection(Outputs, 21, ExtractAmount(TaggedBox(21)))), IntConstant(20)))
    prop shouldBe propExp

    val newBox1 = ErgoBox(11, pubkey)
    val newBox2 = ErgoBox(10, pubkey)
    val newBoxes = IndexedSeq(newBox1, newBox2)

    val spendingTransaction = ErgoTransaction(IndexedSeq(), newBoxes)

    val ctx = ErgoContext(
      currentHeight = 50,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction,
      self = fakeSelf)

    val pr = prover.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, pr, fakeMessage)
  }

  property("byindex") {
    val prover = new ErgoProvingInterpreter
    val verifier = new ErgoInterpreter

    val pubkey = prover.dlogSecrets.head.publicImage

    val env = Map("pubkey" -> pubkey)
    val compiledProp = compile(env, """pubkey && OUTPUTS(0).value > 10""")

    val prop = AND(pubkey, GT(ExtractAmount(ByIndex(Outputs, 0)), IntConstant(10)))
    compiledProp shouldBe prop

    val newBox1 = ErgoBox(11, pubkey)
    val newBox2 = ErgoBox(10, pubkey)
    val newBoxes = IndexedSeq(newBox1, newBox2)

    val spendingTransaction = ErgoTransaction(IndexedSeq(), newBoxes)

    val ctx = ErgoContext(
      currentHeight = 50,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction,
      self = fakeSelf)

    val pr = prover.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, pr, fakeMessage)


    val fProp1 = AND(pubkey, GT(ExtractAmount(ByIndex(Outputs, 0)), IntConstant(11)))
    prover.prove(fProp1, ctx, fakeMessage).isSuccess shouldBe false

    val fProp2 = AND(pubkey, GT(ExtractAmount(ByIndex(Outputs, 1)), IntConstant(11)))
    prover.prove(fProp2, ctx, fakeMessage).isSuccess shouldBe false
  }

  property("sizeof - num of outputs = num of inputs + 1") {
    val prover = new ErgoProvingInterpreter
    val verifier = new ErgoInterpreter

    val pubkey = prover.dlogSecrets.head.publicImage

    val env = Map("pubkey" -> pubkey)
    val prop = compile(env, """pubkey && OUTPUTS.size == INPUTS.size + 1""").asBoolValue
    val propTree = AND(pubkey, EQ(SizeOf(Outputs), Plus(SizeOf(Inputs), IntConstant(1))))
    prop shouldBe propTree

    val newBox1 = ErgoBox(11, pubkey)
    val newBox2 = ErgoBox(10, pubkey)
    val newBoxes = IndexedSeq(newBox1, newBox2)

    val spendingTransaction = ErgoTransaction(IndexedSeq(), newBoxes)

    val s = ErgoBox(21, pubkey)

    val ctx = ErgoContext(
      currentHeight = 50,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(s),
      spendingTransaction,
      self = s)

    val pr = prover.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, pr, fakeMessage)


    val fProp = AND(pubkey, EQ(SizeOf(Outputs), SizeOf(Inputs)))
    prover.prove(fProp, ctx, fakeMessage).isSuccess shouldBe false
  }

  property("exists") {
    val prover = new ErgoProvingInterpreter
    val verifier = new ErgoInterpreter

    val pubkey = prover.dlogSecrets.head.publicImage

    val prop = compile(Map(), "OUTPUTS.exists(fun (box: Box) = box.value + 5 > 10)").asBoolValue

    val expProp = Exists(Outputs, 21, GT(Plus(ExtractAmount(TaggedBox(21)), IntConstant(5)), IntConstant(10)))
    prop shouldBe expProp

    val newBox1 = ErgoBox(16, pubkey)
    val newBox2 = ErgoBox(15, pubkey)
    val newBoxes = IndexedSeq(newBox1, newBox2)

    val spendingTransaction = ErgoTransaction(IndexedSeq(), newBoxes)

    val ctx = ErgoContext(
      currentHeight = 50,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction,
      self = fakeSelf)

    val pr = prover.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, pr, fakeMessage).get shouldBe true
    //todo: finish
  }

  property("forall") {
    val prover = new ErgoProvingInterpreter
    val verifier = new ErgoInterpreter
    val pubkey = prover.dlogSecrets.head.publicImage

    val prop = compile(Map(), "OUTPUTS.forall(fun (box: Box) = box.value == 10)").asBoolValue

    val propTree = ForAll(Outputs, 21, EQ(ExtractAmount(TaggedBox(21)), IntConstant(10)))
    prop shouldBe propTree

    val newBox1 = ErgoBox(10, pubkey)
    val newBox2 = ErgoBox(10, pubkey)
    val newBoxes = IndexedSeq(newBox1, newBox2)

    val spendingTransaction = ErgoTransaction(IndexedSeq(), newBoxes)

    val ctx = ErgoContext(
      currentHeight = 50,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction,
      self = fakeSelf)

    val pr = prover.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, pr, fakeMessage).get shouldBe true
    //todo: finish
  }


  property("forall - fail") {
    val prover = new ErgoProvingInterpreter

    val pubkey = prover.dlogSecrets.head.publicImage

    val prop = compile(Map(), "OUTPUTS.forall(fun (box: Box) = box.value == 10)").asBoolValue
    val propTree = ForAll(Outputs, 21, EQ(ExtractAmount(TaggedBox(21)), IntConstant(10)))
    prop shouldBe propTree

    val newBox1 = ErgoBox(10, pubkey)
    val newBox2 = ErgoBox(11, pubkey)
    val newBoxes = IndexedSeq(newBox1, newBox2)

    val spendingTransaction = ErgoTransaction(IndexedSeq(), newBoxes)

    val ctx = ErgoContext(
      currentHeight = 50,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction,
      self = fakeSelf)

    prover.prove(prop, ctx, fakeMessage).isSuccess shouldBe false
  }

  property("counter") {
    val prover = new ErgoProvingInterpreter
    val verifier = new ErgoInterpreter

    val pubkey = prover.dlogSecrets.head.publicImage

    val prop = compile(Map(),
      """OUTPUTS.exists(fun (box: Box) = {
        |  box.R3[Int].value == SELF.R3[Int].value + 1
         })""".stripMargin).asBoolValue

    val propTree = Exists(Outputs, 21, EQ(ExtractRegisterAs(TaggedBox(21), R3)(SInt),
      Plus(ExtractRegisterAs(Self, R3)(SInt), IntConstant(1))))
    prop shouldBe propTree

    val newBox1 = ErgoBox(10, pubkey, Map(R3 -> IntConstant(3)))
    val newBox2 = ErgoBox(10, pubkey, Map(R3 -> IntConstant(6)))
    val newBoxes = IndexedSeq(newBox1, newBox2)

    val spendingTransaction = ErgoTransaction(IndexedSeq(), newBoxes)

    val s = ErgoBox(20, TrueLeaf, Map(R3 -> IntConstant(5)))

    val ctx = ErgoContext(
      currentHeight = 50,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction,
      self = s)

    val pr = prover.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, pr, fakeMessage).get shouldBe true
  }

  property("counter - no register in outputs") {
    val prover = new ErgoProvingInterpreter
    val verifier = new ErgoInterpreter

    val pubkey = prover.dlogSecrets.head.publicImage

    val prop = compile(Map(),
      """OUTPUTS.exists(fun (box: Box) = {
        |  box.R3[Int].valueOrElse(0) == SELF.R3[Int].value + 1
         })""".stripMargin).asBoolValue

    val propTree = Exists(Outputs, 21,
      EQ(ExtractRegisterAs(TaggedBox(21), R3, default = Some(IntConstant(0L))),
        Plus(ExtractRegisterAs(Self, R3), IntConstant(1))))
    prop shouldBe propTree

    val newBox1 = ErgoBox(10, pubkey)
    val newBox2 = ErgoBox(10, pubkey, Map(R3 -> IntConstant(6)))
    val newBoxes = IndexedSeq(newBox1, newBox2)

    val spendingTransaction = ErgoTransaction(IndexedSeq(), newBoxes)

    val s = ErgoBox(20, TrueLeaf, Map(R3 -> IntConstant(5)))

    val ctx = ErgoContext(
      currentHeight = 50,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction,
      self = s)

    val pr = prover.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, pr, fakeMessage).get shouldBe true
  }

  property(">= compile") {
    val elementId = 1: Byte
    val env = Map("elementId" -> elementId)
    val propTree = GE(TaggedInt(elementId), IntConstant(120))
    val propComp = compile(env,
      """{
        |  taggedInt(elementId) >= 120
        |}""".stripMargin).asBoolValue
    propComp shouldBe propTree
  }

  property("P2SH") {
    val scriptId = 21.toByte

    val customScript = TrueLeaf // new ErgoProvingInterpreter().dlogSecrets.head.publicImage
    val scriptBytes = ValueSerializer.serialize(customScript)
    val prover = new ErgoProvingInterpreter()
      .withContextExtender(scriptId, ByteArrayConstant(scriptBytes))
    val scriptHash = Blake2b256(scriptBytes)

    val hashEquals = EQ(CalcBlake2b256(TaggedByteArray(scriptId)), scriptHash)
    val scriptIsCorrect = Deserialize[SBoolean.type](TaggedByteArray(scriptId))
    val prop = AND(hashEquals, scriptIsCorrect)

    val recipientProposition = new ErgoProvingInterpreter().dlogSecrets.head.publicImage
    val ctx = ErgoContext(
      currentHeight = 50,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      ErgoTransaction(IndexedSeq(), IndexedSeq(ErgoBox(1, recipientProposition))),
      self = ErgoBox(20, TrueLeaf, Map()))

    val proof = prover.prove(prop, ctx, fakeMessage).get

    (new ErgoInterpreter).verify(prop, ctx, proof, fakeMessage).get shouldBe true
  }

  property("Prove keys from registers") {
    val prover = new ErgoProvingInterpreter
    val verifier = new ErgoInterpreter

    val pubkey1 = prover.dlogSecrets.head.publicImage
    val pubkey2 = prover.dlogSecrets(1).publicImage
    val pubkey3 = prover.dlogSecrets(2).publicImage

    val prop = compile(Map(),
      """{
        |  let pubkey1 = SELF.R3[GroupElement].value
        |  let pubkey2 = SELF.R4[GroupElement].value
        |  proveDlog(pubkey1) && proveDlog(pubkey2)
        |}""".stripMargin).asBoolValue

    val propTree = AND(new ProveDlog(ExtractRegisterAs(Self, R3)), new ProveDlog(ExtractRegisterAs(Self, R4)))
    prop shouldBe propTree

    val newBox1 = ErgoBox(10, pubkey3)
    val newBoxes = IndexedSeq(newBox1)
    val spendingTransaction = ErgoTransaction(IndexedSeq(), newBoxes)

    val s1 = ErgoBox(20, TrueLeaf, Map(R3 -> pubkey1.value, R4 -> pubkey2.value))

    val ctx = ErgoContext(
      currentHeight = 50,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction,
      self = s1)

    val pr = prover.prove(prop, ctx, fakeMessage).success.value
    verifier.verify(prop, ctx, pr, fakeMessage).success.value shouldBe true


    //make sure that wrong case couldn't be proved
    val s2 = ErgoBox(20, TrueLeaf, Map(R4 -> pubkey2.value, R5 -> pubkey1.value))
    val wrongCtx = ErgoContext(
      currentHeight = 50,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(),
      spendingTransaction,
      self = s2)

    prover.prove(prop, wrongCtx, fakeMessage).isFailure shouldBe true
    verifier.verify(prop, wrongCtx, pr, fakeMessage).isFailure shouldBe true
  }

  /**
    * An example script where an output could be spent only along with an output with given id
    * (and no more outputs could be provided as an input of a spending transaction).
    */
  property("Along with a brother") {
    val prover = new ErgoProvingInterpreter
    val verifier = new ErgoInterpreter

    val pubkey1 = prover.dlogSecrets.head.publicImage
    val pubkey2 = prover.dlogSecrets(1).publicImage

    val brother = ErgoBox(10, pubkey1)
    val brotherWithWrongId = ErgoBox(10, pubkey1, boxId = 120: Short)

    val newBox = ErgoBox(20, pubkey2)

    val newBoxes = IndexedSeq(newBox)
    val spendingTransaction = ErgoTransaction(IndexedSeq(), newBoxes)

    val env = Map("brother" -> brother)
    val prop = compile(env,
      """{
        |  let okInputs = INPUTS.size == 2
        |  let okIds = INPUTS(0).id == brother.id
        |  okInputs && okIds
         }""".stripMargin).asBoolValue

    val propExpected = AND(
      EQ(SizeOf(Inputs), IntConstant(2)),
      EQ(ExtractId(ByIndex(Inputs, 0)), ExtractId(BoxConstant(brother))))
    prop shouldBe propExpected

    val s = ErgoBox(10, prop, Map())

    val ctx = ErgoContext(
      currentHeight = 50,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(brother, s),
      spendingTransaction,
      self = s)

    val pr = prover.prove(prop, ctx, fakeMessage).success.value
    verifier.verify(prop, ctx, pr, fakeMessage).success.value shouldBe true

    val wrongCtx = ErgoContext(
      currentHeight = 50,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(brotherWithWrongId, s),
      spendingTransaction,
      self = s)

    prover.prove(prop, wrongCtx, fakeMessage).isFailure shouldBe true
    verifier.verify(prop, wrongCtx, pr, fakeMessage).success.value shouldBe false

    val prop2 = compile(env,
      """{
        |  let okInputs = INPUTS.size == 3
        |  let okIds = INPUTS(0).id == brother.id
        |  okInputs && okIds
         }""".stripMargin).asBoolValue

    prover.prove(prop2, ctx, fakeMessage).isFailure shouldBe true
    verifier.verify(prop2, ctx, pr, fakeMessage).success.value shouldBe false
  }

  property("If") {
    val prover = new ErgoProvingInterpreter
    val verifier = new ErgoInterpreter

    val pubkey = prover.dlogSecrets.head.publicImage

    val preimageHello = "hello world".getBytes("UTF-8")
    val preimageWrong = "wrong".getBytes("UTF-8")

    val helloHash = Blake2b256.hash(preimageHello)

    val env = Map("helloHash" -> helloHash)
    val prop = compile(env,
      """{
        |  let cond = INPUTS(0).value > 10
        |  let preimage = if (cond)
        |    INPUTS(2).R3[ByteArray].value
        |  else
        |    INPUTS(1).R3[ByteArray].value
        |  helloHash == blake2b256(preimage)
         }""".stripMargin).asBoolValue

    val propExpected = EQ(ByteArrayConstant(helloHash),
      CalcBlake2b256(
        If(GT(ExtractAmount(ByIndex(Inputs, 0)), IntConstant(10)),
          ExtractRegisterAs[SByteArray.type](ByIndex(Inputs, 2), R3),
          ExtractRegisterAs[SByteArray.type](ByIndex(Inputs, 1), R3))))
    prop shouldBe propExpected

    val input0 = ErgoBox(10, pubkey, Map())
    val input1 = ErgoBox(1, pubkey, Map(R3 -> ByteArrayConstant(preimageHello)))
    val input2 = ErgoBox(1, pubkey, Map(R3 -> ByteArrayConstant(preimageWrong)))
    val input3 = ErgoBox(10, prop, Map())

    val output = ErgoBox(22, pubkey, Map())

    val spendingTransaction = ErgoTransaction(IndexedSeq(), IndexedSeq(output))

    val ctx = ErgoContext(
      currentHeight = 50,
      lastBlockUtxoRoot = AvlTreeData.dummy,
      boxesToSpend = IndexedSeq(input0, input1, input2, input3),
      spendingTransaction,
      self = input3)

    val pr = prover.prove(prop, ctx, fakeMessage).get
    verifier.verify(prop, ctx, pr, fakeMessage).get shouldBe true

    //todo: check failing branches
  }
}