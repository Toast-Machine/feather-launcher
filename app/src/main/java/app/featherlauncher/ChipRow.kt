package app.featherlauncher

import android.content.Context
import android.util.AttributeSet
import android.widget.HorizontalScrollView

/**
 * A sideways-scrolling row (the All apps view's category chips). The app list takes sideways
 * swipes for itself, so this marks drags that start on it as its own.
 */
class ChipRow(context: Context, attrs: AttributeSet?) : HorizontalScrollView(context, attrs), OwnsDrag
