package io.demars.stellarwallet.views.pin

import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.demars.stellarwallet.R

class PinLockAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
  override fun getItemCount(): Int = 12

  var customizationOptions: CustomizationOptionsBundle? = null
  var onItemClickListener: OnNumberClickListener? = null
  var onDeleteClickListener: OnDeleteClickListener? = null
  var pinLength: Int = 0

  private var mKeyValues: IntArray? = getAdjustKeyValues(intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0))

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    val viewHolder: RecyclerView.ViewHolder
    val inflater = LayoutInflater.from(parent.context)

    if (viewType == VIEW_TYPE_NUMBER) {
      val view = inflater.inflate(R.layout.layout_number_item, parent, false)
      viewHolder = NumberViewHolder(view)
    } else {
      val view = inflater.inflate(R.layout.layout_delete_item, parent, false)
      viewHolder = DeleteViewHolder(view)
    }
    return viewHolder
  }

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    if (holder.itemViewType == VIEW_TYPE_NUMBER) {
      (holder as NumberViewHolder).configureNumberButtonHolder()
    } else if (holder.itemViewType == VIEW_TYPE_DELETE) {
      (holder as DeleteViewHolder).configureDeleteButtonHolder()
    }
  }

  override fun getItemViewType(position: Int): Int {
    return if (position == itemCount - 1) {
      VIEW_TYPE_DELETE
    } else VIEW_TYPE_NUMBER
  }

  private fun getAdjustKeyValues(keyValues: IntArray): IntArray {
    val adjustedKeyValues = IntArray(keyValues.size + 1)
    for (i in keyValues.indices) {
      if (i < 9) {
        adjustedKeyValues[i] = keyValues[i]
      } else {
        adjustedKeyValues[i] = -1
        adjustedKeyValues[i + 1] = keyValues[i]
      }
    }
    return adjustedKeyValues
  }

  inner class NumberViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    fun configureNumberButtonHolder() {
      if (adapterPosition == 9) {
        mNumberButton.visibility = View.GONE
      } else {
        mNumberButton.text = mKeyValues!![adapterPosition].toString()
        mNumberButton.visibility = View.VISIBLE
        mNumberButton.tag = mKeyValues!![adapterPosition]
      }

      if (customizationOptions != null) {
        mNumberButton.setTextColor(customizationOptions!!.textColor)
        if (customizationOptions!!.buttonBackgroundDrawable != null) {
          mNumberButton.background = customizationOptions!!.buttonBackgroundDrawable
        }
        mNumberButton.setTextSize(TypedValue.COMPLEX_UNIT_PX,
          customizationOptions!!.textSize.toFloat())
        val params = FrameLayout.LayoutParams(
          customizationOptions!!.buttonSize,
          customizationOptions!!.buttonSize,
          Gravity.CENTER)
        mNumberButton.layoutParams = params
      }
    }

    private var mNumberButton: TextView = itemView.findViewById(R.id.button) as TextView

    init {
      mNumberButton.setOnClickListener { v ->
        if (onItemClickListener != null) {
          onItemClickListener!!.onNumberClicked(v.tag as Int)
        }
      }
    }
  }

  inner class DeleteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private var mDeleteButton: ImageView = itemView.findViewById(R.id.button) as ImageView

    init {
      if (customizationOptions!!.isShowDeleteButton) {
        mDeleteButton.setOnClickListener {
          if (onDeleteClickListener != null) {
            onDeleteClickListener!!.onDeleteClicked()
          }
        }

        mDeleteButton.setOnLongClickListener {
          if (onDeleteClickListener != null) {
            onDeleteClickListener!!.onDeleteLongClicked()
          }
          true
        }
      }
    }

    fun configureDeleteButtonHolder() {
      mDeleteButton.setColorFilter(customizationOptions?.textColor!!)
      mDeleteButton.setImageDrawable(customizationOptions?.deleteButtonDrawable!!)
    }
  }

  interface OnNumberClickListener {
    fun onNumberClicked(keyValue: Int)
  }

  interface OnDeleteClickListener {
    fun onDeleteClicked()
    fun onDeleteLongClicked()
  }

  companion object {
    private const val VIEW_TYPE_NUMBER = 0
    private const val VIEW_TYPE_DELETE = 1
  }
}
