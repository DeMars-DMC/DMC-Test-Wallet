package io.demars.stellarwallet.views

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.demars.stellarwallet.R
import kotlinx.android.synthetic.main.chip_view.view.*

class ChipView(context: Context, attrs: AttributeSet?) : ScrollView(context, attrs) {
  init {
    inflate(getContext(), R.layout.chip_view, this)
  }

  fun loadChips(chips: List<String>, chipsIndexes: List<String>? = null) {
    if (chipsIndexes != null) {
      if (chips.size != chipsIndexes.size) throw IllegalStateException("chips list and chipIndexes list have to be equal in size.")
    }

    for (i in chips.indices) {
      val index: String = if (chipsIndexes == null) {
        (i + 1).toString()
      } else {
        chipsIndexes[i]
      }
      renderView(index, chips[i])
    }
  }

  private fun renderView(indexText: String, phrase: String) {
    val margins = context.resources.getDimensionPixelSize(R.dimen.padding_mini)

    @SuppressLint("InflateParams")
    val itemView = LayoutInflater.from(context).inflate(R.layout.item_view_phrase_word, null)

    val numberTextView = itemView.findViewById<TextView>(R.id.numberItem)
    val wordTextView = itemView.findViewById<TextView>(R.id.wordItem)

    val layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
      LinearLayout.LayoutParams.WRAP_CONTENT)
    layoutParams.setMargins(margins, margins, margins, margins)

    mnemonicGridView.addView(itemView, layoutParams)

    numberTextView.text = indexText
    wordTextView.text = phrase
  }
}