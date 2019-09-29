package io.demars.stellarwallet.api.horizon.model

import java.io.Serializable

data class Trade(var activeAssetCode: String, var createdAt: String,
                 var offerId: String?, var baseIsSeller: Boolean?, var priceN: String,
                 var priceD: String, var baseOfferId: String, var counterOfferId: String,
                 var baseAsset: String, var counterAsset: String,
                 var baseAssetType: String?, var counterAssetType: String?,
                 var baseAmount: String?, var counterAmount: String?,
                 var baseAccount: String?, var counterAccount: String?) : Serializable