package app.ui.main.fragments.downloads.fragments.finished;

import static android.view.LayoutInflater.from;
import static com.aio.R.layout;
import static lib.files.FileSystemUtility.addToMediaStore;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.core.AIOApp;
import app.core.engines.downloader.DownloadDataModel;
import app.core.engines.downloader.DownloadSystem;
import lib.process.LogHelperUtils;

public class FinishedTasksListAdapter extends BaseAdapter {

    private final LogHelperUtils logger = LogHelperUtils.from(getClass());
    private final WeakReference<FinishedTasksFragment> weakRefFinishedFrag;

    private LayoutInflater layoutInflater;
    private DownloadSystem downloadSystem;

    private int existingTaskCount;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> backgroundJob;

    private TaskFilter customFilter;
    private List<DownloadDataModel> originalList = new ArrayList<>();
    private List<DownloadDataModel> filteredList = new ArrayList<>();

    public interface TaskFilter {
        boolean accept(DownloadDataModel model);
    }

    public FinishedTasksListAdapter(@NonNull FinishedTasksFragment fragment) {
        try {
            weakRefFinishedFrag = new WeakReference<>(fragment);
            layoutInflater = from(fragment.getSafeBaseActivityRef());
            downloadSystem = AIOApp.INSTANCE.getDownloadManager();
            rebuildCache();
        } catch (Exception error) {
            logger.e("Adapter init failed", error);
            throw new RuntimeException(error);
        }
    }

    @Override
    public int getCount() {
        return (filteredList != null) ? filteredList.size() : 0;
    }

    @Override
    @Nullable
    public DownloadDataModel getItem(int index) {
        if (downloadSystem == null) return null;
        if (filteredList == null ||
            index < 0 || index >= filteredList.size()) {
            return null;
        }
        return filteredList.get(index);
    }

    @Override
    public long getItemId(int index) {
        return index;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        try {
            FinishedTasksViewHolder holder;
            if (convertView == null) {
                int layoutResId = layout.frag_down_4_finish_1_row_1;
                convertView = layoutInflater.inflate(layoutResId, parent, false);
                holder = new FinishedTasksViewHolder(convertView);
                convertView.setTag(holder);
                logger.d("Created new row view and ViewHolder");
            } else {
                holder = (FinishedTasksViewHolder) convertView.getTag();
                holder.cancelAll();
            }

            updateView(position, holder);
            return convertView;
        } catch (Exception error) {
            logger.e("getView error", error);
            throw error;
        }
    }

    @Override
    public void notifyDataSetChanged() {
        try {
            if (weakRefFinishedFrag.get() == null) return;

            rebuildCache();
            int newCount = getCount();
            if (newCount != existingTaskCount) {
                existingTaskCount = newCount;
                super.notifyDataSetChanged();
                scheduleMediaStoreUpdate();
            }
        } catch (Exception error) {
            logger.e("notifyDataSetChanged error", error);
        }
    }

    public boolean isFilterActive() {
        return customFilter != null &&
            filteredList.size() != originalList.size();
    }

    public void setFilter(@Nullable TaskFilter filter) {
        try {
            customFilter = filter;
            applyFilter();
            rebuildCache();
            super.notifyDataSetChanged();
            logger.d("Filter updated. Active: " + (filter != null));
        } catch (Exception error) {
            logger.e("setFilter error", error);
        }
    }

    private void applyFilter() {
        try {
            if (customFilter == null) {
                filteredList = new ArrayList<>(originalList);
                return;
            }

            List<DownloadDataModel> newList = new ArrayList<>();
            for (DownloadDataModel model : originalList) {
                if (model != null && customFilter.accept(model)) newList.add(model);
            }
            filteredList = newList;
        } catch (Exception error) {
            logger.e("applyFilter error", error);
        }
    }

    private void rebuildCache() {
        try {
            if (downloadSystem == null) return;
            originalList = downloadSystem.getFinishedDownloadDataModels();
            applyFilter();
        } catch (Exception error) {
            logger.e("rebuildCache error", error);
        }
    }

    private void scheduleMediaStoreUpdate() {
        try {
            if (backgroundJob != null && !backgroundJob.isDone()) {
                backgroundJob.cancel(true);
            }

            backgroundJob = executor.submit(() -> {
                int count = getCount();
                for (int index = 0; index < count; index++) {
                    if (Thread.currentThread().isInterrupted()) return;

                    DownloadDataModel model = getItem(index);
                    if (model == null) continue;
                    File file = model.getDestinationFile();
                    if (file.exists()) {
                        try {
                            addToMediaStore(file);
                        } catch (Exception fileError) {
                            logger.e("MediaStore file error", fileError);
                        }
                    }
                }
            });
        } catch (Exception error) {
            logger.e("scheduleMediaStoreUpdate error", error);
        }
    }

    public void notifyDataSetChangedOnSort(boolean forceRefresh) {
        try {
            if (forceRefresh) {
                rebuildCache();
                super.notifyDataSetChanged();
            } else {
                notifyDataSetChanged();
            }
        } catch (Exception error) {
            logger.e("notifyDataSetChangedOnSort error", error);
        }
    }

    private void updateViewHolder(View rowLayout, int position) {
        try {
            FinishedTasksViewHolder holder = (FinishedTasksViewHolder) rowLayout.getTag();
            updateView(position, holder);
        } catch (Exception error) {
            logger.e("updateViewHolder error", error);
        }
    }

    private void updateView(int position, FinishedTasksViewHolder holder) {
        FinishedTasksFragment fragment = weakRefFinishedFrag.get();
        if (fragment != null) {
            DownloadDataModel item = getItem(position);
            holder.updateView(item, fragment);
        }
    }

    public void clearResources() {
        try {
            weakRefFinishedFrag.clear();
            layoutInflater = null;
            downloadSystem = null;
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(60, TimeUnit.MILLISECONDS)) {
                    logger.d("Executor service did not terminate in time.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            logger.d("Resources cleared, background job stopped.");
        } catch (Exception error) {
            logger.e("clearResources error", error);
        }
    }
}