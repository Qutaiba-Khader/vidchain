package app.core.engines.objectbox

import app.core.*
import app.core.bases.*
import app.core.engines.downloader.*
import app.ui.main.*
import com.parse.*
import com.parse.ParseInstallation.*
import lib.process.*
import org.json.*
import java.util.*

class UpStreamServerDownloadModelSync {
	
	/**
	 * Logger instance for this class, used for detailed operation logging.
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	fun uploadUserDetailsToSever(baseActivity: BaseActivity) {
		ThreadsUtility.executeInBackground(codeBlock = {
			AIOApp.downloadSystem.prefetchedEntireDownloadModels.ifEmpty {
				DownloadModelsDBManager.getAllDownloadsWithRelationsAssembled()
			}.forEachIndexed { index, dataModel ->
				val convertClassToJSON = dataModel.convertClassToJSON()
				logger.d("Index:$index, DownloadModel [${dataModel.downloadId}]: $convertClassToJSON")
				uploadJsonToParse(downloadId = dataModel.downloadId, dataModel.javaClass.simpleName, convertClassToJSON)
			}
		})
	}
	
	fun downStreamSyncFromServer(baseActivity: BaseActivity) {
		ThreadsUtility.executeInBackground(
			timeOutInMilli = 5000,
			codeBlock = {
				try {
					val parseQuery = ParseQuery.getQuery<ParseObject>("DownloadDataModel")
					parseQuery.whereEqualTo("installation_id", getCurrentInstallation().installationId)
					parseQuery.orderByDescending("createdAt")
					val results = parseQuery.find()
					
					if (results.isNotEmpty()) {
						logger.d("Found ${results.size} download model records from server")
						for (parseObject in results) {
							val rawJson = parseObject.getString("raw_json")
							
							if (!rawJson.isNullOrEmpty()) {
								DownloadDataModel.convertToClassFromJSON(rawJson)?.let {
									it.updateInStorage()
									logger.d("Restored DownloadModel ID${it.id}, DownloadId${it.downloadId}")
								}
								
								val createdAt = parseObject.createdAt
								logger.d("DownloadModel restored from record created at: $createdAt")
							}
						}
						
					} else {
						logger.d("No DownloadModel found on server for this installation")
					}
					AIOApp.downloadSystem.parseDownloadDataModelsAndSync(onComplete = {
						if (baseActivity is MotherActivity) {
							baseActivity.downloadFragment
								?.finishedTasksFragment
								?.finishedTasksListAdapter
								?.notifyDataSetChangedOnSort(true)
						}
						logger.d("Refresh finished")
					})
				} catch (error: Exception) {
					logger.d("downStreamSyncFromServer failed: ${error.message}")
				}
			}
		)
	}
	
	fun uploadJsonToParse(
		downloadId: Int? = null,
		tableName: String,
		jsonString: String,
		additionalData: Map<String, Any> = emptyMap(),
		storeRawJson: Boolean = true,
		rawJsonColumnName: String = "raw_json",
		updateIfExists: Boolean = true,
		onComplete: ((success: Boolean, message: String) -> Unit)? = null
	) {
		if (!AIOApp.IS_CLOUD_BACKUP_ENABLED) {
			onComplete?.invoke(false, "Cloud backup disabled")
			return
		}
		
		ThreadsUtility.executeInBackground(
			timeOutInMilli = 15000,
			codeBlock = {
				try {
					val installationId = getCurrentInstallation().installationId
					val jsonObject = JSONObject(jsonString)
					
					// Step 1: Find existing record (if needed)
					var existingObjectId: String? = null
					if (updateIfExists) {
						try {
							val parseQuery = ParseQuery.getQuery<ParseObject>(tableName)
							parseQuery.whereEqualTo("installation_id", installationId)
							downloadId?.let { parseQuery.whereEqualTo("downloadID", it) }
							parseQuery.orderByDescending("createdAt")
							parseQuery.limit = 1
							
							val result = parseQuery.find()
							if (result.isNotEmpty()) {
								existingObjectId = result[0].objectId
								logger.d("Existing record found: $existingObjectId")
							}
						} catch (e: Exception) {
							logger.d("Error checking existing record: ${e.message}")
							// Continue anyway
						}
					}
					
					// Step 2: Prepare ParseObject (new or existing)
					val cloudTable = if (existingObjectId != null) {
						createWithoutData(tableName, existingObjectId)
					} else {
						ParseObject(tableName)
					}
					
					// Step 3: Update all fields
					
					// Store raw JSON
					if (storeRawJson) {
						cloudTable.put(rawJsonColumnName, jsonString)
					}
					
					// Installation ID
					cloudTable.put("installation_id", installationId)
					
					// Process JSON fields
					val iterator = jsonObject.keys()
					while (iterator.hasNext()) {
						val key = iterator.next()
						val value = jsonObject.get(key)
						
						when {
							key == "id" -> cloudTable.put("original_id", value)
							key in listOf("objectId", "ACL", "createdAt", "updatedAt") -> {
								// Skip Parse reserved fields
							}
							value != null && value != JSONObject.NULL -> {
								cloudTable.put(key, value)
							}
						}
					}
					
					// Additional metadata
					for ((key, value) in additionalData) {
						cloudTable.put(key, value)
					}
					
					cloudTable.put("last_updated", Date())
					
					// Step 4: Save
					cloudTable.saveInBackground { error ->
						if (error != null) {
							val errorMsg = "Upload failed: ${error.message}"
							logger.d(errorMsg)
							onComplete?.invoke(false, errorMsg)
							
							// Fallback: Try to create new if update failed
							if (existingObjectId != null) {
								logger.d("Update failed, trying to create new record")
								uploadJsonToParse(
									tableName = tableName,
									jsonString = jsonString,
									additionalData = additionalData,
									storeRawJson = storeRawJson,
									rawJsonColumnName = rawJsonColumnName,
									updateIfExists = false, // Force create new
									onComplete = onComplete
								)
							}
						} else {
							val successMsg = if (existingObjectId != null) {
								"Settings updated successfully"
							} else {
								"Settings uploaded successfully"
							}
							logger.d(successMsg)
							onComplete?.invoke(true, successMsg)
						}
					}
					
				} catch (error: Exception) {
					val errorMsg = "Upload failed: ${error.message}"
					logger.d(errorMsg)
					onComplete?.invoke(false, errorMsg)
				}
			}
		)
	}
}