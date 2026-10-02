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
import app.core.engines.browser.history.HistoryModel;
import app.core.engines.caches.AIOFavicons;
import lib.device.DateTimeUtils;
import lib.process.LogHelperUtils;

/**
 * {@code HistoryAdapter} is a custom adapter for displaying browsing history inside a {@link HistoryActivity}.
 * <p>
 * It provides:
 * <ul>
 *   <li>Efficient view recycling using the ViewHolder pattern.</li>
 *   <li>Lazy loading of history items in chunks of 50.</li>
 *   <li>Support for click and long-click callbacks via {@link OnHistoryItemClick} and {@link OnHistoryItemLongClick}.</li>
 *   <li>Favicon loading in a background thread with main-thread UI updates.</li>
 * </ul>
 */
public class HistoryAdapter extends BaseAdapter {

    private final LogHelperUtils logger = LogHelperUtils.from(getClass());
    private final WeakReference<BaseActivity> safeBaseActivityRef;
    private final OnHistoryItemClick onHistoryItemClick;
    private final OnHistoryItemLongClick onHistoryItemLongClick;

    /**
     * Tracks how many history items have been loaded so far.
     */
    private int currentIndex = 0;

    /**
     * Holds the currently displayed history entries.
     */
    private final ArrayList<HistoryModel> displayedHistory = new ArrayList<>();

    /**
     * Constructs a {@link HistoryAdapter}.
     *
     * @param historyActivity        The associated {@link HistoryActivity}.
     * @param onHistoryItemClick     Callback for handling item clicks.
     * @param onHistoryItemLongClick Callback for handling item long clicks.
     */
    public HistoryAdapter(@Nullable HistoryActivity historyActivity,
                          @Nullable OnHistoryItemClick onHistoryItemClick,
                          @Nullable OnHistoryItemLongClick onHistoryItemLongClick) {
        this.safeBaseActivityRef = new WeakReference<>(historyActivity);
        this.onHistoryItemClick = onHistoryItemClick;
        this.onHistoryItemLongClick = onHistoryItemLongClick;
        logger.d("HistoryAdapter initialized, loading initial history batch...");
        loadMoreHistory();
    }

    @Override
    public int getCount() {
        return displayedHistory.size();
    }

    @Nullable
    @Override
    public HistoryModel getItem(int position) {
        if (position >= 0 && position < displayedHistory.size()) {
            return displayedHistory.get(position);
        }
        return null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    /**
     * Binds a history item to the provided list view row.
     *
     * @param position    Position of the item in the adapter.
     * @param convertView Recycled view (if available).
     * @param parent      Parent view group.
     * @return Populated row view.
     */
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        BaseActivity activity = this.safeBaseActivityRef.get();
        if (activity == null) {
            logger.d("BaseActivity reference is null, returning empty view.");
            return convertView != null ? convertView : new View(parent.getContext());
        }

        ViewHolder holder;

        if (convertView == null) {
            LayoutInflater inflater = LayoutInflater.from(activity);
            convertView = inflater.inflate(R.layout.activity_browser_history_1_row_1, parent, false);

            holder = new ViewHolder();
            holder.historyFavicon = convertView.findViewById(R.id.img_history_url_favicon);
            holder.historyTitle = convertView.findViewById(R.id.txt_history_url_title);
            holder.historyDate = convertView.findViewById(R.id.txt_history_url_date);
            holder.historyUrl = convertView.findViewById(R.id.txt_history_url);

            convertView.setTag(holder);
            logger.d("Created new ViewHolder for position " + position);
        } else {
            holder = (ViewHolder) convertView.getTag();
            logger.d("Reusing ViewHolder for position " + position);
        }

        HistoryModel historyModel = getItem(position);
        if (historyModel != null) {
            holder.historyTitle.setText(historyModel.getHistoryTitle());
            holder.historyUrl.setText(removeWwwFromUrl(historyModel.getHistoryUrl()));

            Date visitedDate = historyModel.getHistoryVisitDateTime();
            String formattedDate = DateTimeUtils.formatDateWithSuffix(visitedDate);
            holder.historyDate.setText(formattedDate);

            convertView.setOnClickListener(view -> {
                logger.d("History item clicked: " + historyModel.getHistoryUrl());
                if (onHistoryItemClick != null) {
                    onHistoryItemClick.onHistoryItemClick(historyModel);
                }
            });

            convertView.setOnLongClickListener(view -> {
                logger.d("History item long-clicked: " + historyModel.getHistoryUrl());
                if (onHistoryItemLongClick != null) {
                    onHistoryItemLongClick.onHistoryItemLongClick(historyModel, position, view);
                }
                return true;
            });

            executeInBackground(() -> {
                try {
                    AIOFavicons aioFavicon = INSTANCE.getAIOFavicon();
                    String faviconCachedPath = aioFavicon.getFavicon(historyModel.getHistoryUrl());
                    if (faviconCachedPath != null && !faviconCachedPath.isEmpty()) {
                        File faviconImg = new File(faviconCachedPath);
                        if (faviconImg.exists()) {
                            executeOnMainThread(() ->
                                    holder.historyFavicon.setImageURI(Uri.fromFile(faviconImg)));
                            logger.d("Favicon loaded for: " + historyModel.getHistoryUrl());
                        }
                    }
                } catch (Exception e) {
                    logger.d("Failed to load favicon for " + historyModel.getHistoryUrl() + ": " + e.getMessage());
                }
            });
        }

        return convertView;
    }

    /**
     * Loads more history entries into the adapter.
     * Loads a maximum of 50 new items per call.
     */
    public void loadMoreHistory() {
        ArrayList<HistoryModel> fullList = INSTANCE.getAIOHistory().getHistoryLibrary();
        if (currentIndex >= fullList.size()) {
            logger.d("No more history items to load.");
            return;
        }

        int itemsToLoad = Math.min(50, fullList.size() - currentIndex);
        int endIndex = currentIndex + itemsToLoad;

        for (int index = currentIndex; index < endIndex; index++) {
			try {
				displayedHistory.add(fullList.get(index));
			} catch (Exception error) {
				error.printStackTrace();
                INSTANCE.getAIOHistory().readObjectFromStorage(true);
			}
		}

        currentIndex = endIndex;
        logger.d("Loaded " + itemsToLoad + " more history items, total now: " + displayedHistory.size());
        notifyDataSetChanged();
    }

    /**
     * Clears all displayed history and resets the adapter to its initial state.
     */
    public void resetHistoryAdapter() {
        logger.d("Resetting history adapter...");
        currentIndex = 0;
        displayedHistory.clear();
        notifyDataSetChanged();
    }

    /**
     * Callback interface for item click handling.
     */
    public interface OnHistoryItemClick {
        void onHistoryItemClick(@NonNull HistoryModel historyModel);
    }

    /**
     * Callback interface for item long-click handling.
     */
    public interface OnHistoryItemLongClick {
        void onHistoryItemLongClick(@NonNull HistoryModel historyModel,
                                    int position, @NonNull View listView);
    }

    /**
     * Holds references to views for a single history row.
     */
    private static class ViewHolder {
        ImageView historyFavicon;
        TextView historyTitle;
        TextView historyUrl;
        TextView historyDate;
    }
}