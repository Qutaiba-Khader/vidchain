package app.ui.main.guides

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.core.bases.BaseActivity
import app.ui.main.fragments.home.HomeFragment.FaviconViewHolder
import com.aio.R
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.getText
import lib.ui.ViewUtility.setViewOnClickListener
import lib.ui.builders.DialogBuilder
import java.lang.ref.WeakReference

/**
 * A dialog that allows users to select a platform-specific download guide.
 *
 * This class displays a grid of platform options (Internet, Facebook, Instagram, YouTube)
 * and shows the appropriate guide when a platform is selected. It tracks user
 * interactions with the guide system.
 *
 * @param baseActivity The parent activity that will host this dialog
 */
class GuidePlatformPicker(private val baseActivity: BaseActivity?) {
	private val logger = LogHelperUtils.from(javaClass)

	// Weak reference to parent activity to prevent memory leaks
	private val safeBaseActivityRef = WeakReference(baseActivity).get()

	// Dialog builder for creating and managing the platform picker dialog
	private val dialogBuilder: DialogBuilder = DialogBuilder(safeBaseActivityRef)

	init {
		safeBaseActivityRef?.let { _ ->
			logger.d("Initializing GuidePlatformPicker dialog")

			// Set up the dialog layout and properties
			dialogBuilder.setView(R.layout.dialog_guide_pick_platform_1)
			dialogBuilder.setCancelable(true)

			// Set up click listeners for dialog buttons
			setViewOnClickListener(
				{ button: View -> this.setupClickEvents(button) },
				dialogBuilder.view,
				R.id.btn_dialog_positive_container
			)

			// Initialize the platform selection grid
			setupRecycleList()
		} ?: logger.d("SafeBaseActivityRef is null — dialog not initialized")
	}

	/**
	 * Handles click events for dialog buttons.
	 * Currently only handles the close button.
	 * @param button The view that was clicked
	 */
	private fun setupClickEvents(button: View) {
		logger.d("Button clicked with id=${button.id}")
		when (button.id) {
			R.id.btn_dialog_positive_container -> {
				logger.d("Close button clicked")
				close()
			}
		}
	}

	/**
	 * Shows the platform picker dialog if it's not already showing.
	 * Tracks guide views for analytics.
	 */
	fun show() {
		if (!dialogBuilder.isShowing) {
			logger.d("Showing GuidePlatformPicker dialog")
			dialogBuilder.show()
		} else {
			logger.d("Dialog already showing — skipping show()")
		}
	}

	/**
	 * Closes the platform picker dialog if it's currently showing.
	 */
	fun close() {
		if (dialogBuilder.isShowing) {
			logger.d("Closing GuidePlatformPicker dialog")
			dialogBuilder.close()
		} else {
			logger.d("Dialog already closed — skipping close()")
		}
	}

	/**
	 * Sets up the RecyclerView that displays platform options.
	 * Configures a 3-column grid of platform choices with icons and labels.
	 */
	private fun setupRecycleList() {
		if (safeBaseActivityRef == null) {
			logger.d("RecyclerView not initialized because safeBaseActivityRef is null")
			return
		}

		logger.d("Setting up RecyclerView for platform selection")

		val layoutView = dialogBuilder.view
		with(layoutView) {
			val recyclerView = findViewById<RecyclerView>(R.id.favicons_recycler_list)

			// Platform options with their icons and labels
			val favicons = listOf(
				Pair(first = R.drawable.ic_site_youtube, second = R.string.title_youtube),
				Pair(first = R.drawable.ic_site_instagram, second = R.string.title_instagram),
				Pair(first = R.drawable.ic_site_facebook, second = R.string.title_facebook),
				Pair(first = R.drawable.ic_site_web, second = R.string.title_internet)
			)

			// Configure grid layout
			recyclerView.layoutManager = GridLayoutManager(safeBaseActivityRef, 4)

			// Set up adapter for platform options
			recyclerView.adapter = object : RecyclerView.Adapter<FaviconViewHolder>() {

				/**
				 * Creates view holders for platform items.
				 * Sets up click handlers to show the appropriate guide when a platform is selected.
				 */
				override fun onCreateViewHolder(
					parent: ViewGroup,
					viewType: Int
				): FaviconViewHolder {
					val view = LayoutInflater.from(parent.context)
						.inflate(R.layout.frag_home_1_main_1_fav_item_1, parent, false)

					return FaviconViewHolder(view) { siteName ->
						logger.d("Platform selected: $siteName")

						when {
							siteName.contains(getText(R.string.title_internet), true) -> {
								logger.d("Opening WebDownloadGuide")
								safeBaseActivityRef.let {
									close()
									WebDownloadGuide(it).show()
								}
							}

							siteName.contains(getText(R.string.title_youtube), true) -> {
								logger.d("Opening YTDownloadGuide")
								safeBaseActivityRef.let {
									close()
									YTDownloadGuide(it).show()
								}
							}

							siteName.contains(getText(R.string.title_facebook), true) -> {
								logger.d("Opening FBDownloadGuide")
								safeBaseActivityRef.let {
									close()
									FBDownloadGuide(it).show()
								}
							}

							siteName.contains(getText(R.string.title_instagram), true) -> {
								logger.d("Opening InstDownloadGuide")
								safeBaseActivityRef.let {
									close()
									InstDownloadGuide(it).show()
								}
							}

							else -> logger.d("Unknown platform selected: $siteName")
						}
					}
				}

				/**
				 * Binds platform data to view holders.
				 * @param holder The view holder to bind data to
				 * @param position The position of the item in the list
				 */
				override fun onBindViewHolder(holder: FaviconViewHolder, position: Int) {
					holder.setImageFavicon(favicons[position].first)
					holder.setFaviconTitle(favicons[position].second)
					logger.d("Binding platform item at position=$position")
				}

				/**
				 * Returns the total number of platform options.
				 * @return The size of the favicons list
				 */
				override fun getItemCount() = favicons.size
			}
		}
	}
}
