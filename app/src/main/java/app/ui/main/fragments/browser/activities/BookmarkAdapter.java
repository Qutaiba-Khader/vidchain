package app.ui.main.fragments.browser.activities;

import static app.core.AIOApp.INSTANCE;
import static lib.networks.URLUtilityKT.removeWwwFromUrl;
import static lib.process.AsyncJobUtils.executeInBackground;
import static lib.process.AsyncJobUtils.executeOnMainThread;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aio.R;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;

import app.core.bases.BaseActivity;
import app.core.engines.browser.bookmarks.AIOBookmarks;
import app.core.engines.browser.bookmarks.BookmarkModel;
import app.core.engines.caches.AIOFavicons;
import lib.device.DateTimeUtils;
import lib.process.LogHelperUtils;

/**
 * Adapter for displaying bookmark items in a list view.
 * Responsibilities:
 * - Binds {@link BookmarkModel} data (title, URL, favicon, creation date) to list rows.
 * - Handles item click and long-click events via callback interfaces.
 * - Supports lazy-loading of bookmarks in chunks for performance.
 * - Manages favicon loading asynchronously.
 * - Supports search functionality with fuzzy matching.
 */
public class BookmarkAdapter extends BaseAdapter {

	/** Logger instance for tracking adapter events and debugging */
	private final LogHelperUtils logger = LogHelperUtils.from(getClass());

	/** Weak reference to the parent activity to prevent memory leaks */
	private final WeakReference<BaseActivity> safeBaseActivityRef;

	/** Callback interface for handling bookmark click events */
	private final OnBookmarkItemClick onBookmarkItemClick;

	/** Callback interface for handling bookmark long-click events */
	private final OnBookmarkItemLongClick onBookmarkItemLongClick;

	/** Current index position for pagination when loading bookmarks */
	private int currentIndex = 0;

	/** List of bookmark models currently displayed in the adapter */
	private final ArrayList<BookmarkModel> displayedBookmarks = new ArrayList<>();

	/**
	 * Constructs a new {@link BookmarkAdapter}.
	 *
	 * @param bookmarkActivity        optional activity reference for inflating views and context operations.
	 * @param onBookmarkItemClick     callback for handling click events on bookmark items.
	 * @param onBookmarkItemLongClick callback for handling long-click events on bookmark items.
	 */
	public BookmarkAdapter(@Nullable BookmarksActivity bookmarkActivity,
						   @Nullable OnBookmarkItemClick onBookmarkItemClick,
						   @Nullable OnBookmarkItemLongClick onBookmarkItemLongClick) {
		this.safeBaseActivityRef = new WeakReference<>(bookmarkActivity);
		this.onBookmarkItemClick = onBookmarkItemClick;
		this.onBookmarkItemLongClick = onBookmarkItemLongClick;
		logger.d("BookmarkAdapter initialized. Loading initial bookmarks...");
		loadMoreBookmarks(null);
	}

	/**
	 * Returns the number of items currently displayed in the adapter.
	 *
	 * @return the count of displayed bookmarks.
	 */
	@Override
	public int getCount() {
		return displayedBookmarks.size();
	}

	/**
	 * Retrieves the bookmark item at the specified position.
	 *
	 * @param position the position of the item in the adapter's data set.
	 * @return the {@link BookmarkModel} at the specified position, or null if position is invalid.
	 */
	@Nullable
	@Override
	public BookmarkModel getItem(int position) {
		if (position >= 0 && position < displayedBookmarks.size()) {
			return displayedBookmarks.get(position);
		}
		logger.d("Invalid position requested in getItem: " + position);
		return null;
	}

	/**
	 * Returns the stable ID for the item at the specified position.
	 * In this implementation, the position is used as the ID.
	 *
	 * @param position the position of the item within the adapter's data set.
	 * @return the item's ID at the specified position.
	 */
	@Override
	public long getItemId(int position) {
		return position;
	}

	/**
	 * Gets a View that displays the data at the specified position in the data set.
	 * Either creates a new View or reuses an existing one (convertView).
	 *
	 * @param position    the position of the item within the adapter's data set.
	 * @param convertView the old view to reuse, if possible.
	 * @param parent      the parent that this view will eventually be attached to.
	 * @return a View corresponding to the data at the specified position.
	 */
	@Override
	public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
		BaseActivity activity = this.safeBaseActivityRef.get();
		if (activity == null) {
			// If activity reference is lost, safely return an existing or empty view
			logger.d("Activity reference lost. Returning fallback view.");
			return convertView != null ? convertView : new View(parent.getContext());
		}

		ViewHolder holder;

		// Reuse convertView if available, else inflate a new one
		if (convertView == null) {
			LayoutInflater inflater = LayoutInflater.from(activity);
			convertView = inflater.inflate(R.layout.activity_bookmarks_1_row_1, parent, false);

			// Create a new ViewHolder to cache subview references
			holder = new ViewHolder();
			holder.bookmarkFavicon = convertView.findViewById(R.id.bookmark_url_favicon_indicator);
			holder.bookmarkTitle = convertView.findViewById(R.id.bookmark_url_title);
			holder.bookmarkDate = convertView.findViewById(R.id.bookmark_url_date);
			holder.bookmarkUrl = convertView.findViewById(R.id.bookmark_url);

			// Store the holder inside the view's tag for reuse
			convertView.setTag(holder);
			logger.d("Created new ViewHolder for position " + position);
		} else {
			// Retrieve cached ViewHolder to avoid redundant findViewById calls
			holder = (ViewHolder) convertView.getTag();
		}

		// Get current bookmark for this list position
		BookmarkModel bookmarkModel = getItem(position);
		if (bookmarkModel != null) {
			logger.d("Binding bookmark: " + bookmarkModel.getBookmarkName());

			// Bind bookmark title and URL (stripping "www")
			holder.bookmarkTitle.setText(bookmarkModel.getBookmarkName());
			holder.bookmarkUrl.setText(removeWwwFromUrl(bookmarkModel.getBookmarkUrl()));

			// Format and bind bookmark creation/visited date
			Date visitedDate = bookmarkModel.getBookmarkCreationDate();
			String formattedDate = DateTimeUtils.formatDateWithSuffix(visitedDate);
			holder.bookmarkDate.setText(formattedDate);

			// Handle single-click: open bookmark
			convertView.setOnClickListener(view -> {
				logger.d("Bookmark clicked: " + bookmarkModel.getBookmarkUrl());
				if (onBookmarkItemClick != null) {
					onBookmarkItemClick.onBookmarkClick(bookmarkModel);
				}
			});

			// Handle long-click: show bookmark options
			convertView.setOnLongClickListener(view -> {
				logger.d("Bookmark long-clicked: " + bookmarkModel.getBookmarkUrl());
				if (onBookmarkItemLongClick != null) {
					onBookmarkItemLongClick.onBookmarkLongClick(bookmarkModel, position, view);
				}
				return true; // consume event
			});

			// Load favicon asynchronously (background thread)
			executeInBackground(() -> {
				AIOFavicons aioFavicon = INSTANCE.getAIOFavicon();
				String faviconCachedPath = aioFavicon.getFavicon(bookmarkModel.getBookmarkUrl());

				// If favicon exists on disk, update ImageView on main thread
				if (faviconCachedPath != null && !faviconCachedPath.isEmpty()) {
					File faviconImg = new File(faviconCachedPath);
					if (faviconImg.exists()) {
						executeOnMainThread(() -> {
							holder.bookmarkFavicon.setImageURI(Uri.fromFile(faviconImg));
							logger.d("Favicon loaded for: " + bookmarkModel.getBookmarkUrl());
						});
					}
				}
			});
		}

		// Return the prepared/reused view
		return convertView;
	}

	/**
	 * Loads more bookmarks into the adapter's displayed list.
	 * - Supports optional fuzzy search filtering.
	 * - Loads up to 400 bookmarks per call to avoid UI lag.
	 * - Updates adapter state and notifies UI when done.
	 *
	 * @param searchTerms Optional search query. If null/empty, loads all bookmarks.
	 */
	public void loadMoreBookmarks(@Nullable String searchTerms) {
		AIOBookmarks aioBookmarks = INSTANCE.getAIOBookmarks();
		ArrayList<BookmarkModel> fullList;

		// If a search term exists, use fuzzy search; otherwise load full bookmark library
		if (searchTerms != null && !searchTerms.isEmpty()) {
			fullList = new ArrayList<>(aioBookmarks.searchBookmarksFuzzy(searchTerms));
		} else {
			fullList = aioBookmarks.getBookmarkLibrary();
		}

		// If we've already loaded all bookmarks, exit early
		if (currentIndex >= fullList.size()) {
			logger.d("No more bookmarks to load.");
			return;
		}

		// Determine how many items to load in this batch (max 400 per call)
		int itemsToLoad = Math.min(400, fullList.size() - currentIndex);
		int endIndex = currentIndex + itemsToLoad;

		// Add bookmarks from the source list into displayed list
		for (int index = currentIndex; index < endIndex; index++) {
			try {
				displayedBookmarks.add(fullList.get(index));
			} catch (Exception error) {
				// If something went wrong while accessing data, reload storage
				error.printStackTrace();
				INSTANCE.getAIOBookmarks().readObjectFromStorage(true);
			}
		}

		// Update the current index marker for next load
		currentIndex = endIndex;

		// Log status and notify UI adapter to refresh the list view
		logger.d("Loaded " + itemsToLoad + " bookmarks. Current index: " + currentIndex);
		notifyDataSetChanged();
	}

	/**
	 * Resets the adapter state by clearing all displayed bookmarks and resetting the current index.
	 * This is typically called when search criteria change or when data needs to be refreshed.
	 */
	public void resetBookmarkAdapter() {
		logger.d("Resetting BookmarkAdapter. Clearing " + displayedBookmarks.size() + " items.");
		currentIndex = 0;
		displayedBookmarks.clear();
		notifyDataSetChanged();
	}

	/**
	 * Checks if the adapter is currently empty (no bookmarks displayed).
	 *
	 * @return true if no bookmarks are displayed, false otherwise.
	 */
	public boolean isEmpty() {
		return displayedBookmarks.isEmpty();
	}

	/**
	 * Callback interface for bookmark item click events.
	 */
	public interface OnBookmarkItemClick {
		/**
		 * Called when a bookmark item is clicked.
		 *
		 * @param bookmarkModel the bookmark model that was clicked.
		 */
		void onBookmarkClick(@NonNull BookmarkModel bookmarkModel);
	}

	/**
	 * Callback interface for bookmark item long-click events.
	 */
	public interface OnBookmarkItemLongClick {
		/**
		 * Called when a bookmark item is long-clicked.
		 *
		 * @param bookmarkModel the bookmark model that was long-clicked.
		 * @param position      the position of the item in the list.
		 * @param listView      the ListView containing the item.
		 */
		void onBookmarkLongClick(@NonNull BookmarkModel bookmarkModel,
								 int position, @NonNull View listView);
	}

	/**
	 * ViewHolder pattern class for caching view references in list items.
	 * Improves performance by avoiding frequent findViewById calls.
	 */
	private static class ViewHolder {
		/** ImageView for displaying the bookmark's favicon */
		ImageView bookmarkFavicon;

		/** TextView for displaying the bookmark title */
		TextView bookmarkTitle;

		/** TextView for displaying the bookmark URL */
		TextView bookmarkUrl;

		/** TextView for displaying the bookmark creation/visit date */
		TextView bookmarkDate;
	}
}