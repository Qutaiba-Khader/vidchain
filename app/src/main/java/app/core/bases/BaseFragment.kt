package app.core.bases

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import java.lang.ref.WeakReference

abstract class BaseFragment : Fragment() {

	private var weakReferenceOfActivity: WeakReference<BaseActivity>? = null
	private var weakReferenceOfBaseFragment: WeakReference<BaseFragment>? = null
	private var _fragmentLayout: View? = null

	open val safeBaseActivityRef: BaseActivity?
		get() = weakReferenceOfActivity?.get()

	open val safeBaseFragmentRef: BaseFragment?
		get() = weakReferenceOfBaseFragment?.get()

	open val safeFragmentLayoutRef: View?
		get() = _fragmentLayout

	open var isFragmentRunning: Boolean = false

	protected abstract fun getLayoutResId(): Int

	protected abstract fun onAfterLayoutLoad(layoutView: View, state: Bundle?)

	protected abstract fun onResumeFragment()

	protected abstract fun onPauseFragment()

	override fun onAttach(context: Context) {
		super.onAttach(context)
		weakReferenceOfActivity = WeakReference(context as BaseActivity)
		weakReferenceOfBaseFragment = WeakReference(this)
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? = inflater.inflate(getLayoutResId(), container, false).also {
		_fragmentLayout = it
	}

	override fun onViewCreated(view: View, bundle: Bundle?) {
		super.onViewCreated(view, bundle)
		onAfterLayoutLoad(view, bundle)
	}

	override fun onResume() {
		super.onResume()
		isFragmentRunning = true
		onResumeFragment()
	}

	override fun onPause() {
		super.onPause()
		isFragmentRunning = false
		onPauseFragment()
	}

	override fun onDestroyView() {
		super.onDestroyView()
		isFragmentRunning = false
		_fragmentLayout = null
		weakReferenceOfActivity?.clear()
		weakReferenceOfActivity = null

		weakReferenceOfBaseFragment?.clear()
		weakReferenceOfBaseFragment = null
	}
}
