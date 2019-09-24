package io.demars.stellarwallet.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import io.demars.stellarwallet.R
import io.demars.stellarwallet.DmcApp
import io.demars.stellarwallet.interfaces.SuccessErrorCallback
import io.demars.stellarwallet.api.horizon.model.HorizonException
import io.demars.stellarwallet.api.horizon.Horizon
import io.demars.stellarwallet.utils.*
import io.demars.stellarwallet.utils.StringFormat.Companion.getNumDecimals
import io.demars.stellarwallet.utils.StringFormat.Companion.hasDecimalPoint
import io.demars.stellarwallet.views.pin.PinLockView
import kotlinx.android.synthetic.main.activity_pay.*

class PayActivity : BaseActivity(), PinLockView.DialerListener, SuccessErrorCallback {

  companion object {
    private const val ARG_ADDRESS_DATA = "ARG_ADDRESS_DATA"
    private const val ARG_ASSET_CODE = "ARG_ASSET_CODE"
    private const val ARG_ASSET_ISSUER = "ARG_ASSET_ISSUER"
    private const val REQUEST_PIN = 0x0

    fun newIntent(context: Context, assetCode: String,
                  assetIssuer: String, address: String): Intent =
      Intent(context, PayActivity::class.java).apply {
        putExtra(ARG_ADDRESS_DATA, address)
        putExtra(ARG_ASSET_CODE, assetCode)
        putExtra(ARG_ASSET_ISSUER, assetIssuer)
      }
  }

  private var amount = 0.0
  private var amountAvailable = 0.0

  private var assetCode = "XLM"
  private var assetIssuer = ""
  private var address: String = ""
  private var amountText: String = ""
  private var amountAvailableText = "0.0"

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_pay)
    setupUI()
  }

  //region User Interface

  private fun setupUI() {
    backButton.setOnClickListener {
      onBackPressed()
    }

    amountTextView.text = "0"
    numberKeyboard.mDialerListener = this

    if (intent.hasExtra(ARG_ADDRESS_DATA)) {
      address = intent.getStringExtra(ARG_ADDRESS_DATA)
    }

    if (intent.hasExtra(ARG_ASSET_CODE)) {
      assetCode = intent.getStringExtra(ARG_ASSET_CODE)
    }

    if (intent.hasExtra(ARG_ASSET_ISSUER)) {
      assetIssuer = intent.getStringExtra(ARG_ASSET_ISSUER)
    }

    addressEditText.text = address

    payButton.setOnClickListener {
      if (amount <= amountAvailable && amount != 0.0) {
        if (DmcApp.wallet.getShowPinOnSend()) {
          startActivityForResult(WalletManagerActivity.verifyPin(it.context), REQUEST_PIN)
        } else {
          sendPayment()
        }
      } else {
        val shakeAnimation = AnimationUtils.loadAnimation(this, R.anim.shake)
        amountTextView.startAnimation(shakeAnimation)
      }
    }
  }

  override fun onResume() {
    super.onResume()

    updateAvailableBalance()
  }

  @SuppressLint("SetTextI18n")
  private fun updateAvailableBalance() {
    val available = AccountUtils.getAvailableBalance(assetCode)
    val maxDecimals = AssetUtils.getMaxDecimals(assetCode)
    val availableFormatted = StringFormat.truncateDecimalPlaces(available, maxDecimals)
    amountAvailable = availableFormatted.toDouble()
    amountAvailableText = availableFormatted
    titleText.text = getString(R.string.pattern_available, "$availableFormatted $assetCode")
    assetLogo.setImageResource(AssetUtils.getLogo(assetCode))
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQUEST_PIN) {
      if (resultCode == Activity.RESULT_OK) {
        sendPayment()
      }
    }
  }

  //region Dialer Clicks
  override fun onDial(number: Int) {
    if (amountText.isEmpty() && number == 0) {
      return
    }
    updateAmount(amountText + number)
  }

  override fun onDelete() {
    if (amountText.isEmpty()) return

    var newAmountText: String
    if (amountText.length <= 1) {
      newAmountText = ""
    } else {
      newAmountText = amountText.substring(0, amountText.length - 1)
      if (newAmountText[newAmountText.length - 1] == '.') {
        newAmountText = newAmountText.substring(0, newAmountText.length - 1)
      }
      if ("0" == newAmountText) {
        newAmountText = ""
      }
    }
    updateAmount(newAmountText)
  }

  override fun onDeleteAll() {
    if (amountText.isEmpty()) return
    updateAmount("")
  }

  override fun onDot() {
    if (!hasDecimalPoint(amountText)) {
      amountText = if (amountText.isEmpty()) "0." else "$amountText."
      showAmount(amountText)
    }
  }
  //endregion

  private fun updateAmount(newAmountText: String) {
    val newAmount = if (newAmountText.isEmpty()) 0.0 else newAmountText.toDouble()
    val maxDecimals = AssetUtils.getMaxDecimals(assetCode)
    if (newAmount >= 0.0 && getNumDecimals(newAmountText) <= maxDecimals) {
      amountText = if (newAmount > amountAvailable) amountAvailableText else newAmountText
      amount = if (newAmount > amountAvailable) amountAvailable else newAmount
      showAmount(amountText)
    }
  }

  private fun showAmount(amount: String) {
    val amountToUse = when {
      amount.isEmpty() -> "0"
      else -> amount
    }

    amountTextView?.text = amountToUse
  }
  //endregion

  private fun sendPayment() {
    if (NetworkUtils(applicationContext).isNetworkAvailable()) {
      progressBar.visibility = View.VISIBLE

      val secretSeed = AccountUtils.getSecretSeed(applicationContext)
      val asset = AssetUtils.getAsset(assetCode, assetIssuer)
      Horizon.getSendTask(this, asset, address, secretSeed,
        memoTextView.text.toString(), amountText).execute()
    } else {
      NetworkUtils(applicationContext).displayNoNetwork()
    }
  }

  //region Horizon callbacks
  override fun onSuccess() {
    progressBar.visibility = View.GONE
    Toast.makeText(applicationContext, getString(R.string.send_success_message), Toast.LENGTH_LONG).show()
    runOnUiThread {
      if (!isFinishing) {
        setResult(RESULT_OK)
        finish()
      }
    }
  }

  override fun onError(error: HorizonException) {
    progressBar.visibility = View.GONE
    // Showing op_low_reserve message when is confusing to the user,
    // specially when the other account was not created and the funds sent are lower than 1 XML.
    // Let's add a generic message for now for any error sending funds.
    Toast.makeText(applicationContext, getString(HorizonException.HorizonExceptionType.SEND.value), Toast.LENGTH_LONG).show()
  }

  override fun onBackPressed() {
    super.onBackPressed()
    ViewUtils.hideKeyboard(this)
    overridePendingTransition(R.anim.slide_in_end, R.anim.slide_out_end)
  }
//endregion
}
