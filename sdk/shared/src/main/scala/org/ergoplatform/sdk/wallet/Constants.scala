package org.ergoplatform.sdk.wallet

import org.ergoplatform.sdk.wallet.secrets.DerivationPath
import sigmastate.crypto.CryptoFacade

object Constants {
  /** part of the protocol, do not change */
  val SecretKeyLength = 32

  /** part of the protocol, do not change */
  val ModifierIdLength = 32

  val BitcoinSeed: Array[Byte] = "Bitcoin seed".getBytes(CryptoFacade.Encoding)

  /**
    * [See EIP-3 https://github.com/ergoplatform/eips/blob/master/eip-0003.md ]
    *
    * For coin type, we suggest consider "ergo" word in ASCII and calculate coin_type number as
    *
    * 101 + 114 + 103 + 111 = 429
    *
    * Following this idea we should use next scheme
    *
    * m / 44' / 429' / account' / change / address_index
    */
  val CoinType = 429

  /**
    * There needs to be reasonable amount of tokens in a box due to a byte size limit for ergo box
    * */
  val MaxAssetsPerBox = 100

  /**
    * Pre - EIP3 derivation path
    */
  val preEip3DerivationPath: DerivationPath = DerivationPath.fromEncoded("m/1").get

  /**
    * Post - EIP3 derivation path
    */
  val eip3DerivationPath: DerivationPath = DerivationPath.fromEncoded("m/44'/429'/0'/0/0").get
}
