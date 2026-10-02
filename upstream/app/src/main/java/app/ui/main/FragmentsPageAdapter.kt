@file:Suppress("DEPRECATION")

package app.ui.main

import androidx.fragment.app.*
import androidx.viewpager2.adapter.*
import app.core.bases.*
import app.ui.main.fragments.browser.*
import app.ui.main.fragments.downloads.*
import app.ui.main.fragments.home.*
import app.ui.main.fragments.settings.*

/**
 * An adapter that provides fragments for a [androidx.viewpager2.widget.ViewPager2].
 *
 * This adapter is responsible for creating and supplying the correct fragment for each page
 * based on its position. The mapping is as follows:
 * - Position 0: [HomeFragment]
 * - Position 1: [BrowserFragment]
 * - Position 2: [DownloadsFragment]
 * - Position 3: [SettingsFragment]
 *
 * @param baseActivity The host activity that will contain the ViewPager2.
 */
class FragmentsPageAdapter(baseActivity: BaseActivity) : FragmentStateAdapter(baseActivity) {

	/**
	 * Returns the total number of fragments (pages) managed by this adapter.
	 *
	 * The count is fixed to 4, representing the Home, Browser, Downloads, and Settings fragments.
	 */
	override fun getItemCount(): Int = 4

	/**
	 * Creates and returns the fragment for a given position.
	 *
	 * This method maps each position to a specific fragment instance:
	 * - **0:** [HomeFragment]
	 * - **1:** [BrowserFragment]
	 * - **2:** [DownloadsFragment]
	 * - **3:** [SettingsFragment]
	 *
	 * If an unexpected position is provided, it defaults to returning a [HomeFragment].
	 *
	 * @param position The position of the fragment to be created.
	 * @return The [Fragment] instance corresponding to the specified position.
	 */
	override fun createFragment(position: Int): Fragment {
		return when (position) {
			0 -> HomeFragment()
			1 -> BrowserFragment()
			2 -> DownloadsFragment()
			3 -> SettingsFragment()
			else -> HomeFragment()
		}
	}
}
