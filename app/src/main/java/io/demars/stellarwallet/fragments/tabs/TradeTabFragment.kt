package io.demars.stellarwallet.fragments.tabs

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import io.demars.stellarwallet.R
import io.demars.stellarwallet.WalletApplication
import io.demars.stellarwallet.helpers.Constants
import io.demars.stellarwallet.interfaces.*
import io.demars.stellarwallet.models.AssetUtil
import io.demars.stellarwallet.models.Currency
import io.demars.stellarwallet.models.SelectionModel
import io.demars.stellarwallet.remote.Horizon
import io.demars.stellarwallet.utils.AccountUtils
import io.demars.stellarwallet.utils.DebugPreferencesHelper
import kotlinx.android.synthetic.main.fragment_tab_trade.*
import kotlinx.android.synthetic.main.view_custom_selector.view.*
import org.stellar.sdk.Asset
import org.stellar.sdk.responses.OrderBookResponse
import timber.log.Timber
import java.text.DecimalFormat

class TradeTabFragment : Fragment(), View.OnClickListener, OnUpdateTradeTab {
  private lateinit var appContext: Context
  private lateinit var parentListener: OnTradeCurrenciesChanged
  private lateinit var selectedSellingCurrency: SelectionModel
  private lateinit var selectedBuyingCurrency: SelectionModel
  private lateinit var toolTip: PopupWindow

  private var sellingCurrencies = mutableListOf<SelectionModel>()
  private var buyingCurrencies = mutableListOf<SelectionModel>()
  private var holdingsAmount: Double = 0.0
  private var addedCurrencies: ArrayList<Currency> = ArrayList()
  private var latestBid: OrderBookResponse.Row? = null
  private var orderType: OrderType = OrderType.MARKET
  private var dataAvailable = false
  private var isShowingAdvanced = false
  private val ZERO_VALUE = "0.0"
  private val decimalFormat = DecimalFormat("0.#######")


  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
    return inflater.inflate(R.layout.fragment_tab_trade, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    appContext = view.context.applicationContext
    toolTip = PopupWindow(view.context)
    setBuyingSelectorEnabled(false)
    refreshAddedCurrencies()
    setupListeners()
    updateView()
  }

  private fun setupListeners() {
    toggleMarket.setOnClickListener(this)
    toggleLimit.setOnClickListener(this)
    tenth.setOnClickListener(this)
    quarter.setOnClickListener(this)
    half.setOnClickListener(this)
    threeQuarters.setOnClickListener(this)
    all.setOnClickListener(this)
    submitTrade.setOnClickListener(this)

    sellingCustomSelector.editText.addTextChangedListener(object : AfterTextChanged() {
      override fun afterTextChanged(editable: Editable) {
        updateBuyingValueIfNeeded()
        refreshSubmitTradeButton()
      }
    })

    sellingCustomSelector.setSelectionValues(sellingCurrencies)
    sellingCustomSelector.spinner.onItemSelectedListener = object : io.demars.stellarwallet.interfaces.OnItemSelected() {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        selectedSellingCurrency = sellingCurrencies[position]
        holdingsAmount = selectedSellingCurrency.holdings

        sellingCustomSelector.imageView.setImageResource(
          Constants.getImage(selectedSellingCurrency.label))

        if (selectedSellingCurrency.label == AssetUtil.NATIVE_ASSET_CODE) {
          val available = WalletApplication.wallet.getAvailableBalance().toDouble()

          holdings.text = String.format(getString(R.string.holdings_amount),
            decimalFormat.format(available),
            selectedSellingCurrency.label)

          holdingsAmount = available

        } else {
          holdings.text = String.format(getString(R.string.holdings_amount),
            decimalFormat.format(holdingsAmount),
            selectedSellingCurrency.label)
        }

        resetBuyingCurrencies()
        buyingCurrencies.removeAt(position)

        buyingCustomSelector.setSelectionValues(buyingCurrencies)

        onSelectorChanged()
      }
    }

    buyingCustomSelector.setSelectionValues(buyingCurrencies)
    buyingCustomSelector.spinner.onItemSelectedListener = object : io.demars.stellarwallet.interfaces.OnItemSelected() {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        selectedBuyingCurrency = buyingCurrencies[position]

        buyingCustomSelector.imageView.setImageResource(
          Constants.getImage(selectedBuyingCurrency.label))

        onSelectorChanged()
      }
    }
  }

  private fun updateView() {
    if (isShowingAdvanced) {
      tenth.visibility = View.VISIBLE
      quarter.visibility = View.VISIBLE
      half.visibility = View.VISIBLE
      threeQuarters.visibility = View.VISIBLE
    } else {
      tenth.visibility = View.GONE
      quarter.visibility = View.GONE
      half.visibility = View.GONE
      threeQuarters.visibility = View.GONE
    }

    val allText = if (isShowingAdvanced) "100%" else "Sell 100%"
    val allWeight = if (isShowingAdvanced) 0.2f else 1.0f
    val allParams = all.layoutParams as LinearLayout.LayoutParams
    allParams.weight = allWeight
    all.layoutParams = allParams
    all.text = allText
  }

  private fun onSelectorChanged() {
    dataAvailable = false
    if (::selectedBuyingCurrency.isInitialized && ::selectedSellingCurrency.isInitialized) {
      notifyParent(selectedSellingCurrency, selectedBuyingCurrency)
    }
    refreshSubmitTradeButton()
    updateBuyingValueIfNeeded()
  }

  private fun refreshSubmitTradeButton() {
    val sellingValue = sellingCustomSelector.editText.text.toString()
    val buyingValue = sellingCustomSelector.editText.text.toString()

    var numberFormatValid = true
    var sellingValueDouble = 0.toDouble()
    try {
      sellingValueDouble = sellingValue.toDouble()
      buyingValue.toDouble()
    } catch (e: NumberFormatException) {
      Timber.d("selling or buying value have not a valid format")
      numberFormatValid = false
    }

    if (sellingValue.isEmpty() || buyingValue.isEmpty() ||
      !numberFormatValid || sellingValueDouble.compareTo(0) == 0) {
      submitTrade.isEnabled = false
    } else if (::selectedSellingCurrency.isInitialized) {
      submitTrade.isEnabled = sellingValue.toDouble() <= selectedSellingCurrency.holdings
    }
  }

  private fun updateBuyingValueIfNeeded() {
    if (sellingCustomSelector == null) return
    val stringText = sellingCustomSelector.editText.text.toString()
    if (stringText.isEmpty()) {
      buyingCustomSelector.editText.text.clear()
      return
    }

    if (latestBid != null && dataAvailable) {
      val value = sellingCustomSelector.editText.text.toString().toFloatOrNull()
      val price = latestBid?.price?.toFloatOrNull()
      if (value != null && price != null) {
        val floatValue: Float = value.toFloat()
        val floatPrice: Float = price.toFloat()
        val stringValue = decimalFormat.format(floatValue * floatPrice)
        buyingCustomSelector.editText.setText(stringValue)
      }
    } else {
      buyingCustomSelector.editText.setText(ZERO_VALUE)
    }
  }

  private fun updatePrices() {
    val price = latestBid?.price?.toFloatOrNull()
    val pricesString = if (price == null) getString(R.string.no_offers).toUpperCase() else
      "Market Price\n1 ${selectedSellingCurrency.label} = $price ${selectedBuyingCurrency.label}" +
        "  |  1 ${selectedBuyingCurrency.label} = ${1F / price} ${selectedSellingCurrency.label}"
    prices.text = pricesString
  }

  private fun notifyParent(selling: SelectionModel?, buying: SelectionModel?) {
    if (selling != null && buying != null) {
      parentListener.onCurrencyChange(selling, buying)
    }
  }

  enum class OrderType {
    LIMIT,
    MARKET
  }

  override fun onDestroyView() {
    super.onDestroyView()
    if (toolTip.isShowing) {
      toolTip.dismiss()
    }
  }

  override fun onClick(view: View) {
    when (view.id) {
      R.id.tenth -> sellingCustomSelector.editText.setText(decimalFormat.format(0.1 * holdingsAmount).toString())
      R.id.quarter -> sellingCustomSelector.editText.setText(decimalFormat.format(0.25 * holdingsAmount).toString())
      R.id.half -> sellingCustomSelector.editText.setText(decimalFormat.format(0.5 * holdingsAmount).toString())
      R.id.threeQuarters -> sellingCustomSelector.editText.setText(decimalFormat.format(0.75 * holdingsAmount).toString())
      R.id.all -> sellingCustomSelector.editText.setText(decimalFormat.format(holdingsAmount))
      R.id.toggleMarket -> {
        orderType = OrderType.MARKET
        toggleMarket.setTextColor(ContextCompat.getColor(view.context, R.color.white))
        toggleMarket.setBackgroundResource(R.drawable.left_toggle_selected)
        toggleLimit.setBackgroundResource(R.drawable.right_toggle)
        toggleLimit.setTextColor(ContextCompat.getColor(view.context, R.color.blueDark))

        setBuyingSelectorEnabled(false)
        updateBuyingValueIfNeeded()
      }
      R.id.toggleLimit -> {
        orderType = OrderType.LIMIT
        toggleLimit.setBackgroundResource(R.drawable.right_toggle_selected)
        toggleLimit.setTextColor(ContextCompat.getColor(view.context, R.color.white))
        toggleMarket.setBackgroundResource(R.drawable.left_toggle)
        toggleMarket.setTextColor(ContextCompat.getColor(view.context, R.color.blueDark))
        setBuyingSelectorEnabled(true)
      }
      R.id.submitTrade -> {
        if (buyingCustomSelector.editText.text.toString().isEmpty()) {
          createSnackBar("Buying price can not be empty.", Snackbar.LENGTH_SHORT)?.show()
        } else if ((orderType == OrderType.MARKET && !dataAvailable) || buyingCustomSelector.editText.text.toString() == ZERO_VALUE) {
          // buyingEditText should be empty at this moment

          createSnackBar("Trade price cannot be 0. Please override limit order.", Snackbar.LENGTH_SHORT)?.show()

        } else {

          val sellingText = sellingCustomSelector.editText.text.toString().replace(",", ".")
          val buyingText = buyingCustomSelector.editText.text.toString().replace(",", ".")

          val sellingCode = selectedSellingCurrency.label
          val buyingCode = selectedBuyingCurrency.label

          AlertDialog.Builder(view.context)
            .setTitle("Confirm Trade")
            .setMessage("You are about to submit a trade of $sellingText $sellingCode for $buyingText $buyingCode.")
            .setPositiveButton("Submit") { _, _ ->
              proceedWithTrade(buyingText, sellingText, selectedBuyingCurrency.asset!!, selectedSellingCurrency.asset!!)
            }.setNegativeButton("Cancel") { dialog, _ ->
              dialog.dismiss()
            }.show()
        }
      }
    }
  }

  private fun createSnackBar(text: CharSequence, duration: Int): Snackbar? {
    activity?.let {
      return Snackbar.make(it.findViewById(R.id.content_container), text, duration)
    }
    return null
  }

  private fun proceedWithTrade(buyingAmount: String, sellingAmount: String,
                               buyingAsset: Asset, sellingAsset: Asset) {
    val snackBar = createSnackBar("Submitting order", Snackbar.LENGTH_INDEFINITE)
    val snackView = snackBar?.view as Snackbar.SnackbarLayout
    val progress = ProgressBar(context)
    val height = resources.getDimensionPixelOffset(R.dimen.progress_snackbar_height)
    val width = resources.getDimensionPixelOffset(R.dimen.progress_snackbar_width)

    val params = FrameLayout.LayoutParams(height, width)
    params.gravity = Gravity.END or Gravity.RIGHT or Gravity.CENTER_VERTICAL
    val margin = resources.getDimensionPixelOffset(R.dimen.progress_snackbar_margin)
    progress.setPadding(margin, margin, margin, margin)
    snackView.addView(progress, params)
    snackBar.show()

    submitTrade.isEnabled = false

    setBuyingSelectorEnabled(false)
    setSellingSelectorEnabled(false)

    WalletApplication.userSession.getAvailableBalance()

    val sellingAmountFormatted: String
    val priceFormatted: String
    try {
      sellingAmountFormatted = decimalFormat.format(sellingAmount.toDouble())
      priceFormatted = decimalFormat.format(buyingAmount.toDouble() / sellingAmount.toDouble())
    } catch (ex: NumberFormatException) {
      Toast.makeText(activity!!, "Wrong numbers format", Toast.LENGTH_LONG).show()
      return
    }

    Horizon.getCreateMarketOffer(object : Horizon.OnMarketOfferListener {
      override fun onExecuted() {
        snackBar.dismiss()
        createSnackBar("Order executed", Snackbar.LENGTH_SHORT)?.show()

        submitTrade.isEnabled = true
        setSellingSelectorEnabled(true)
        setBuyingSelectorEnabled(true)
      }

      override fun onFailed(errorMessage: String) {
        snackBar.dismiss()

        createSnackBar("Order failed: $errorMessage", Snackbar.LENGTH_SHORT)?.show()

        submitTrade.isEnabled = true
        setSellingSelectorEnabled(false)
        setBuyingSelectorEnabled(false)
      }
    }, AccountUtils.getSecretSeed(appContext), sellingAsset, buyingAsset,
      sellingAmountFormatted, priceFormatted)
  }


  private fun setSellingSelectorEnabled(isEnabled: Boolean) {
    sellingCustomSelector.editText.isEnabled = isEnabled
  }

  private fun setBuyingSelectorEnabled(isEnabled: Boolean) {
    buyingCustomSelector.editText.isEnabled = isEnabled
  }

  override fun onAttach(context: Context?) {
    super.onAttach(context)
    try {
      parentListener = parentFragment as OnTradeCurrenciesChanged
    } catch (e: ClassCastException) {
      Timber.e("the parent must implement: %s", OnTradeCurrenciesChanged::class.java.simpleName)
    }
  }

  private fun resetBuyingCurrencies() {
    buyingCurrencies.clear()
    addedCurrencies.forEach {
      buyingCurrencies.add(it)
    }
  }

  private fun refreshAddedCurrencies() {
    val accounts = WalletApplication.wallet.getBalances()
    addedCurrencies.clear()
    var i = 0
    var native: Currency? = null
    accounts.forEach {
      val currency = if (it.assetType != "native") {
        Currency(i, it.assetCode, it.assetCode, it.balance.toDouble(), it.asset)
      } else {
        native = Currency(i, AssetUtil.NATIVE_ASSET_CODE, "LUMEN", it.balance.toDouble(), it.asset)
        native as Currency
      }
      addedCurrencies.add(currency)
      i++
    }

    native?.let {
      addedCurrencies.remove(it)
      addedCurrencies.add(0, it)
    }

    sellingCurrencies.clear()
    buyingCurrencies.clear()
    addedCurrencies.forEach {
      sellingCurrencies.add(it)
      buyingCurrencies.add(it)
    }
  }

  override fun onLastOrderBookUpdated(asks: Array<OrderBookResponse.Row>, bids: Array<OrderBookResponse.Row>) {
    if (bids.isNotEmpty()) {
      latestBid = bids[0]
      dataAvailable = true
      updateBuyingValueIfNeeded()
    } else {
      dataAvailable = false
    }

    updatePrices()
  }
}