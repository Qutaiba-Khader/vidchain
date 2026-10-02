package lib.process;

import android.util.Log;
import androidx.annotation.NonNull;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import app.core.AIOApp;

public final class LogHelperUtils implements Serializable {

	private final Class<?> class_;
	private final boolean isDebuggingMode;

	private LogHelperUtils(Class<?> class_) {
		this.class_ = class_;
		this.isDebuggingMode = AIOApp.Companion.getIS_DEBUG_MODE_ON();
	}

	@NonNull
	public static LogHelperUtils from(@NonNull Class<?> class_) {
		return new LogHelperUtils(class_);
	}

	@NonNull
	public static String toString(final @NonNull Throwable throwable) {
		final StringWriter sw = new StringWriter();
		final PrintWriter pw = new PrintWriter(sw, true);
		throwable.printStackTrace(pw);
		return sw.getBuffer().toString();
	}

	public void e(@NonNull String message) {
		if (isDebuggingMode) Log.e(class_.getSimpleName(), toMessage(message));
	}

	public void e(@NonNull Throwable error) {
		if (isDebuggingMode) Log.e(class_.getSimpleName(), toString(error));
	}

	public void d(@NonNull String message) {
		if (isDebuggingMode) Log.d(class_.getSimpleName(), toMessage(message));
	}

	public void v(@NonNull String message) {
		if (isDebuggingMode) Log.v(class_.getSimpleName(), toMessage(message));
	}

	public void v(@NonNull String methodName, @NonNull String message) {
		v(methodName + message);
	}

	public void v(@NonNull Throwable err) {
		if (isDebuggingMode) Log.v(class_.getSimpleName(), toString(err));
	}

	public void w(@NonNull String message) {
		if (isDebuggingMode) Log.w(class_.getSimpleName(), toMessage(message));
	}

	public void w(@NonNull String message, @NonNull Throwable throwable) {
		if (isDebuggingMode) Log.w(class_.getSimpleName(), toMessage(message), throwable);
	}

	public void d(@NonNull String methodName, @NonNull String message) {
		d(methodName + message);
	}

	public void d(@NonNull Throwable err) {
		if (isDebuggingMode) Log.d(class_.getSimpleName(), toString(err));
	}

	public void i(@NonNull String message) {
		if (isDebuggingMode) Log.i(class_.getSimpleName(), toMessage(message));
	}

	public void i(@NonNull Throwable err) {
		if (isDebuggingMode) Log.i(class_.getSimpleName(), toString(err));
	}

	public void i(@NonNull String methodName, @NonNull String message) {
		i(methodName + message);
	}

	public void e(@NonNull String message, @NonNull Throwable throwable) {
		if (isDebuggingMode) {
			Log.e(class_.getSimpleName(), toMessage(message));
			logThrowableStackTrace(throwable);
		}
	}

	private String toMessage(String message) {
		return message == null ? "Error Message = NULL!!" : message;
	}

	private void logThrowableStackTrace(Throwable throwable) {
		if (isDebuggingMode) {
			StringWriter stringWriter = new StringWriter();
			PrintWriter printWriter = new PrintWriter(stringWriter, true);
			throwable.printStackTrace(printWriter);
			Log.e(class_.getSimpleName(), stringWriter.toString());
		}
	}
}
