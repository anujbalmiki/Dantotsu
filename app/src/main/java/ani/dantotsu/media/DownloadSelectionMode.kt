package ani.dantotsu.media

import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import ani.dantotsu.R
import ani.dantotsu.util.customAlertDialog

/**
 * Contextual toolbar for picking several chapters or episodes and downloading or deleting them in
 * one go. The manga chapter list and the anime episode list want exactly the same five actions, so
 * the ActionMode plumbing lives here and each list supplies a [Target].
 */
class DownloadSelectionMode(private val target: Target) : ActionMode.Callback {

    interface Target {
        val selectionActivity: AppCompatActivity?

        /** How many items are currently ticked. */
        val selectedCount: Int

        /** Lowest and highest parsed numbers in the list, used to seed the range dialog. */
        fun selectionNumberRange(): Pair<Float, Float>?

        fun selectAllItems()

        /** Ticks everything whose number falls in [from]..[to]. Returns how many were ticked. */
        fun selectNumberRange(from: Float, to: Float): Int

        /** Ticks everything already on disk, which is the usual prelude to deleting. */
        fun selectDownloadedItems(): Int

        fun clearItemSelection()

        fun downloadSelectedItems()

        fun deleteSelectedItems()

        /** Called once the toolbar closes, for whatever the list wants to reset. */
        fun onSelectionModeFinished()
    }

    private var mode: ActionMode? = null

    fun start(): Boolean {
        val activity = target.selectionActivity ?: return false
        mode = activity.startSupportActionMode(this)
        return mode != null
    }

    fun finish() {
        mode?.finish()
    }

    /** Refreshes the "N selected" title, and closes the toolbar once nothing is left ticked. */
    fun refresh() {
        val current = mode ?: return
        if (target.selectedCount == 0) {
            current.finish()
            return
        }
        current.title = "${target.selectedCount} selected"
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.menu_download_selection, menu)
        mode.title = "${target.selectedCount} selected"
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val activity = target.selectionActivity ?: return false
        when (item.itemId) {
            R.id.action_select_all -> {
                target.selectAllItems()
                refresh()
            }

            R.id.action_select_downloaded -> {
                target.selectDownloadedItems()
                refresh()
            }

            R.id.action_select_range -> showRangeDialog()

            R.id.action_download_selected -> {
                target.downloadSelectedItems()
                mode.finish()
            }

            R.id.action_delete_selected -> {
                val count = target.selectedCount
                if (count == 0) return true
                activity.customAlertDialog().apply {
                    setTitle(activity.getString(R.string.delete_selected))
                    setMessage(
                        activity.resources.getQuantityString(
                            R.plurals.delete_selected_confirm, count, count
                        )
                    )
                    setPosButton(R.string.yes) {
                        target.deleteSelectedItems()
                        mode.finish()
                    }
                    setNegButton(R.string.no)
                    show()
                }
            }

            else -> return false
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        this.mode = null
        target.clearItemSelection()
        target.onSelectionModeFinished()
    }

    private fun showRangeDialog() {
        val activity = target.selectionActivity ?: return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_select_range, null)
        val fromField = view.findViewById<EditText>(R.id.rangeFrom)
        val toField = view.findViewById<EditText>(R.id.rangeTo)
        val hint = view.findViewById<TextView>(R.id.rangeHint)

        val bounds = target.selectionNumberRange()
        if (bounds != null) {
            hint.text = activity.getString(
                R.string.range_available, bounds.first.trimNumber(), bounds.second.trimNumber()
            )
            fromField.setText(bounds.first.trimNumber())
            toField.setText(bounds.second.trimNumber())
        } else {
            hint.text = activity.getString(R.string.range_unavailable)
        }

        activity.customAlertDialog().apply {
            setTitle(activity.getString(R.string.select_range))
            setCustomView(view)
            setPosButton(R.string.ok) {
                val from = fromField.text.toString().trim().toFloatOrNull()
                val to = toField.text.toString().trim().toFloatOrNull()
                if (from == null || to == null) {
                    ani.dantotsu.toast(activity.getString(R.string.range_invalid))
                    return@setPosButton
                }
                val ticked = target.selectNumberRange(minOf(from, to), maxOf(from, to))
                ani.dantotsu.toast(
                    activity.resources.getQuantityString(
                        R.plurals.range_selected, ticked, ticked
                    )
                )
                refresh()
            }
            setNegButton(R.string.cancel)
            show()
        }
    }
}

/** 12.0 reads better as "12" in a number field the user is about to edit. */
internal fun Float.trimNumber(): String =
    if (this % 1f == 0f) toInt().toString() else toString()
