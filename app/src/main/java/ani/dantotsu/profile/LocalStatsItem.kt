package ani.dantotsu.profile

import android.view.View
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemLocalStatsBinding
import com.xwray.groupie.viewbinding.BindableItem

/** "This device" card shown above the AniList charts. */
class LocalStatsItem : BindableItem<ItemLocalStatsBinding>() {
    private fun fmt(sec: Long): String {
        val m = sec / 60
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    override fun bind(viewBinding: ItemLocalStatsBinding, position: Int) {
        val c = viewBinding.root.context
        fun line(label: Int, anime: Boolean): String {
            val s = LocalStats.summary(anime)
            return c.getString(
                R.string.stats_line, c.getString(label),
                fmt(s.today), fmt(s.week), fmt(s.month), fmt(s.all)
            )
        }
        val top = LocalStats.top(5)
        viewBinding.localStatsBody.text = buildString {
            appendLine(line(R.string.stats_watching, true))
            appendLine(line(R.string.stats_reading, false))
            appendLine()
            appendLine(c.getString(R.string.stats_top_titles))
            if (top.isEmpty()) append(c.getString(R.string.stats_none))
            else top.forEachIndexed { i, (n, s) ->
                if (i > 0) appendLine()
                append("${i + 1}. $n - ${fmt(s.toLong())}")
            }
        }
    }

    override fun getLayout() = R.layout.item_local_stats
    override fun initializeViewBinding(view: View) = ItemLocalStatsBinding.bind(view)
}
