package app.core.bases.interfaces

/**
 * Defines a callback contract for receiving and handling the results of Android runtime permission requests.
 *
 * This interface provides a standardized way to process the outcome of permission requests
 * initiated through the system's runtime permission dialog. It enables clean separation between
 * permission request logic and result handling, promoting better code organization and
 * maintainability in permission-dependent features.
 *
 * Implement this interface in activities, fragments, or dedicated handlers that need to
 * respond to permission grants or denials. The callback methods are typically invoked
 * after the user interacts with the system permission dialog, allowing the application
 * to enable or disable features based on the permission state.
 *
 * Common use cases include:
 * - Enabling camera features when CAMERA permission is granted
 * - Accessing location services when LOCATION permissions are approved
 * - Handling storage operations when READ/WRITE_EXTERNAL_STORAGE permissions are available
 * - Providing fallback behaviors or user education when permissions are denied
 * - Implementing permission escalation flows with rationale dialogs
 *
 * The interface design supports both simple boolean checks and detailed permission analysis,
 * making it suitable for both single-permission and multiple-permission request scenarios.
 */
interface PermissionsResult {

	/**
	 * Called when the system provides the result of a runtime permission request.
	 *
	 * This callback is invoked after the user has interacted with the system permission
	 * dialog, providing complete information about which permissions were granted and
	 * which were denied. The method should handle all possible permission outcomes and
	 * update the application state accordingly.
	 *
	 * Implementation guidelines:
	 * - For `isGranted = true`: Enable all permission-dependent functionality immediately
	 * - For `isGranted = false`: Analyze deniedList to determine appropriate user messaging
	 * - Check if critical permissions are missing and disable core features if needed
	 * - Consider showing educational dialogs for permissions marked "Don't ask again"
	 * - Update UI state to reflect current permission availability
	 * - Log permission outcomes for analytics and troubleshooting
	 *
	 * Example usage pattern:
	 * ```kotlin
	 * override fun onPermissionResultFound(
	 *     isGranted: Boolean,
	 *     grantedList: List<String>?,
	 *     deniedList: List<String>?
	 * ) {
	 *     if (isGranted) {
	 *         // All permissions granted - proceed with feature
	 *         startCameraPreview()
	 *     } else {
	 *         // Some permissions denied - handle appropriately
	 *         deniedList?.let { deniedPermissions ->
	 *             if (shouldShowPermissionRationale(deniedPermissions)) {
	 *                 showPermissionRationaleDialog(deniedPermissions)
	 *             } else {
	 *                 showPermissionPermanentlyDeniedMessage(deniedPermissions)
	 *             }
	 *         }
	 *         disablePermissionDependentFeatures()
	 *     }
	 * }
	 * ```
	 *
	 * @param isGranted `true` if all requested permissions were granted by the user,
	 *                  indicating that permission-dependent features can be enabled
	 *                  immediately. `false` if at least one permission was denied,
	 *                  requiring fallback behavior or user education.
	 *
	 * @param grantedList A list containing the permission strings that were successfully
	 *                    granted by the user, or `null` if no permissions were granted.
	 *                    This list is useful when requesting multiple permissions
	 *                    simultaneously and needing to enable specific features based
	 *                    on individual permission availability. The list contains
	 *                    permission constants from `Manifest.permission` (e.g.,
	 *                    "android.permission.CAMERA", "android.permission.ACCESS_FINE_LOCATION").
	 *
	 * @param deniedList A list containing the permission strings that were denied by
	 *                   the user, or `null` if all permissions were granted. This list
	 *                   is crucial for implementing targeted user education and
	 *                   determining whether to show permission rationale dialogs or
	 *                   direct users to app settings for permanently denied permissions.
	 *                   Use `Activity.shouldShowRequestPermissionRationale()` to
	 *                   determine if users selected "Don't ask again".
	 */
	fun onPermissionResultFound(
		isGranted: Boolean,
		grantedList: List<String>?,
		deniedList: List<String>?
	)
}