package sigmastate.lang.js

import org.ergoplatform.ErgoAddressEncoder
import org.ergoplatform.sdk.js.Isos.isoValueToConstant
import org.scalablytyped.runtime.StringDictionary

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import org.ergoplatform.sdk.js.{ErgoTree, Value}
import sigmastate.Values
import sigmastate.eval.CompiletimeIRContext
import sigmastate.lang.Terms.ValueOps


/** Wrapper exported to JS. */
@JSExportTopLevel("SigmaCompiler")
class SigmaCompiler(_compiler: sigmastate.lang.SigmaCompiler) extends js.Object {

  /** Compiles ErgoScript code to ErgoTree.
    *
    * @param namedConstants       named constants to be used in the script
    * @param segregateConstants   if true, then constants will be segregated from the tree
    * @param additionalHeaderFlags additional header flags to be set in the tree
    * @param ergoScript           ErgoScript code to be compiled
    * @return ErgoTree instance
    */
  def compile(
      namedConstants: StringDictionary[Value],
      segregateConstants: Boolean,
      additionalHeaderFlags: Byte, ergoScript: String): ErgoTree = {
    val env = StringDictionary
        .wrapStringDictionary(namedConstants)
        .view.mapValues(v => isoValueToConstant.to(v)).toMap
    val IR = new CompiletimeIRContext
    val prop = _compiler.compile(env, ergoScript)(IR).buildTree
    require(prop.tpe.isSigmaProp, s"Expected SigmaProp expression type bue got ${prop.tpe}: $prop")

    val tree = if (segregateConstants) {
      Values.ErgoTree.withSegregation(additionalHeaderFlags, prop.asSigmaProp)
    } else {
      Values.ErgoTree.withoutSegregation(additionalHeaderFlags, prop.asSigmaProp)
    }
    new ErgoTree(tree)
  }
}

@JSExportTopLevel("SigmaCompilerObj")
object SigmaCompiler extends js.Object {
  /** Creates a new instance of SigmaCompiler for the mainnet. */
  def forMainnet(): SigmaCompiler = create(ErgoAddressEncoder.MainnetNetworkPrefix)

  /** Creates a new instance of SigmaCompiler for the testnet. */
  def forTestnet(): SigmaCompiler = create(ErgoAddressEncoder.TestnetNetworkPrefix)

  /** Creates a new instance of SigmaCompiler for the given network prefix.
    *
    * @param networkPrefix network prefix to be used in the compiler
    * @return SigmaCompiler instance
    */
  private def create(networkPrefix: Byte): SigmaCompiler = {
    val compiler = new sigmastate.lang.SigmaCompiler(networkPrefix)
    new SigmaCompiler(compiler)
  }
}
