package io.demars.stellarwallet.api.stellarport.model

import com.google.gson.annotations.SerializedName

class  WithdrawResponse {
  @SerializedName("account_id")
  var accountId = ""
  @SerializedName("memo_type")
  var memoType = ""
  @SerializedName("memo")
  var memo = ""
  @SerializedName("min_amount")
  var minAmount = -1.0
  @SerializedName("fee_fixed")
  var feeFixed = 0.0
  @SerializedName("fee_percent")
  var feePercent = 0.0
  @SerializedName("eta")
  var eta = -1
  @SerializedName("extra_info")
  var extraInfo = ExtraInfo()

  class ExtraInfo{
    @SerializedName("message")
    var message = ""
  }
}