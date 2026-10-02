package lib.process;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;

public final class AsyncJobUtils<TaskResult> {

    private final LogHelperUtils logger = LogHelperUtils.from(getClass());
    private static final Handler UI_HANDLER = new Handler(Looper.getMainLooper());

    private WeakReference<BackgroundTask<TaskResult>> backgroundTaskRef;
    private WeakReference<ResultTask<TaskResult>> resultTaskRef;
    private WeakReference<ProgressUpdateTask> progressUpdateTaskRef;
    private ExecutorService executorService;
    private Thread backgroundThread;
    private FutureTask<?> backgroundFutureTask;
    private TaskResult result;

    public static void executeOnMainThread(final @NonNull UITask uiTask) {
        UI_HANDLER.post(uiTask::runOnUIThread);
    }

    public static void executeInBackground(
        final @NonNull BackgroundTaskNoResult backgroundTask
    ) {
        new Thread(backgroundTask::runInBackground).start();
    }

    @NonNull
    public static FutureTask<?> executeInBackground(
        final @NonNull BackgroundTaskNoResult backgroundTask,
        @NonNull ExecutorService executor) {
        FutureTask<?> task = new FutureTask<>(
            backgroundTask::runInBackground, null
        );
        executor.submit(task);
        return task;
    }

    public void start() {
        final BackgroundTask<TaskResult> taskInstance =
            backgroundTaskRef != null ? backgroundTaskRef.get() : null;

        if (taskInstance != null) {
            Runnable task = () -> {
                ProgressUpdateTask progressTaskInstance =
                    progressUpdateTaskRef != null ? progressUpdateTaskRef.get() : null;

                result = taskInstance.runInBackground(progress -> {
                    if (progressTaskInstance != null) {
                        UI_HANDLER.post(() ->
                            progressTaskInstance.onProgressUpdate(progress));
                    }
                });
                deliverResult();
            };

            if (executorService != null) {
                backgroundFutureTask = new FutureTask<>(task, null);
                executorService.submit(backgroundFutureTask);
            } else {
                backgroundThread = new Thread(task);
                backgroundThread.start();
            }
        }
    }

    private void deliverResult() {
        final ResultTask<TaskResult> resultTaskInstance =
            resultTaskRef != null ? resultTaskRef.get() : null;

        if (resultTaskInstance != null) {
            UI_HANDLER.post(() -> resultTaskInstance.onResult(result));
        }
    }

    public void cancel() {
        if (executorService != null && backgroundFutureTask != null) {
            backgroundFutureTask.cancel(true);
        } else if (backgroundThread != null) {
            backgroundThread.interrupt();
        }
    }

    @NonNull
    public ExecutorService getExecutorService() {
        return executorService;
    }

    public void setExecutorService(@NonNull ExecutorService executorService) {
        this.executorService = executorService;
    }

    public BackgroundTask<TaskResult> getBackgroundTask() {
        return backgroundTaskRef != null ? backgroundTaskRef.get() : null;
    }

    public void setBackgroundTask(
        @NonNull BackgroundTask<TaskResult> backgroundTask
    ) {
        this.backgroundTaskRef = new WeakReference<>(backgroundTask);
    }

    public ResultTask<TaskResult> getResultTask() {
        return resultTaskRef != null ? resultTaskRef.get() : null;
    }

    public void setResultTask(@NonNull ResultTask<TaskResult> resultTask) {
        this.resultTaskRef = new WeakReference<>(resultTask);
    }

    public ProgressUpdateTask getProgressUpdateTask() {
        return progressUpdateTaskRef != null ? progressUpdateTaskRef.get() : null;
    }

    public void setProgressUpdateTask(
        @NonNull ProgressUpdateTask progressUpdateTask
    ) {
        this.progressUpdateTaskRef = new WeakReference<>(progressUpdateTask);
    }

    public interface BackgroundTask<Result> {
        Result runInBackground(@NonNull ProgressCallback progressCallback);
    }

    public interface ResultTask<Result> {
        void onResult(Result result);
    }

    public interface UITask {
        void runOnUIThread();
    }

    public interface BackgroundTaskNoResult {
        void runInBackground();
    }

    public interface ProgressUpdateTask {
        void onProgressUpdate(int progress);
    }

    public interface ProgressCallback {
        void onProgress(int progress);
    }

    public static class Builder<JobResult> {

        private BackgroundTask<JobResult> backgroundTask;
        private ResultTask<JobResult> resultTask;
        private ProgressUpdateTask progressUpdateTask;
        private ExecutorService executorService;

        @NonNull
        public Builder<JobResult> withBackgroundTask(
            @NonNull BackgroundTask<JobResult> task
        ) {
            this.backgroundTask = task;
            return this;
        }

        @NonNull
        public Builder<JobResult> withResultTask(
            @NonNull ResultTask<JobResult> task
        ) {
            this.resultTask = task;
            return this;
        }

        @NonNull
        public Builder<JobResult> withProgressUpdateTask(
            @NonNull ProgressUpdateTask task
        ) {
            this.progressUpdateTask = task;
            return this;
        }

        @NonNull
        public Builder<JobResult> withExecutorService(
            @NonNull ExecutorService executor
        ) {
            this.executorService = executor;
            return this;
        }

        @NonNull
        public AsyncJobUtils<JobResult> build() {
            AsyncJobUtils<JobResult> asyncJobUtils = new AsyncJobUtils<>();
            asyncJobUtils.setBackgroundTask(backgroundTask);
            asyncJobUtils.setResultTask(resultTask);
            asyncJobUtils.setProgressUpdateTask(progressUpdateTask);
            asyncJobUtils.setExecutorService(executorService);
            return asyncJobUtils;
        }
    }
}