package io.demars.stellarwallet.interfaces

import io.demars.stellarwallet.models.SessionAsset
import org.stellar.sdk.Asset

interface AssertListener {
    fun changeTrustline(asset: Asset, isRemoveAsset: Boolean)
    fun assetSelected(sessionAsset: SessionAsset)
    fun buyXLM()
    fun tradeDMC()
    fun depositZAR()
}