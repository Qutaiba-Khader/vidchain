package app.core.engines.downloader

import com.dslplatform.json.CompiledJson
import com.dslplatform.json.JsonAttribute
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import java.io.Serializable

/**
 * Represents metadata about a remote file used in the download system.
 *
 * This class holds all essential information about a file hosted on a remote server
 * without downloading the actual file contents. It is serializable with DSL-JSON
 * for easy storage, transfer, and caching operations within the download engine.
 *
 * ## Key Features
 *
 * ### 1. Access Control & Error Handling
 * - [isFileForbidden]: Indicates whether access to the file was denied by the server
 * - [errorMessage]: Provides details about any failure encountered while fetching file metadata
 *
 * ### 2. File Identification & Integrity
 * - [fileName]: The name of the file extracted from Content-Disposition headers or URL path
 * - [fileSize]: Size of the file in bytes; -1 indicates unknown size
 * - [fileChecksum]: Optional cryptographic checksum (e.g., SHA-256, MD5) for file integrity verification
 *
 * ### 3. Download Capabilities
 * - [isSupportsMultipart]: True if server supports partial content downloads (HTTP Range requests)
 * - [isSupportsResume]: True if download can be resumed using range requests, ETag, or Last-Modified headers
 *
 * ## Usage
 *
 * This class is designed to be lightweight and easily transportable between components.
 * It captures only the metadata required for managing downloads, tracking progress,
 * and performing integrity checks.
 *
 * @property id Unique identifier for ObjectBox database (fixed to 1 for singleton pattern)
 * @property downloadDataModelDBId Unique identifier linking to the associated download model
 * @property isFileForbidden True if server returned 403 Forbidden or similar access denial
 * @property errorMessage Descriptive error message when file info retrieval fails
 * @property fileName Name of the remote file with extension
 * @property fileSize File size in bytes, or -1 if unknown/unavailable
 * @property fileChecksum Cryptographic hash for integrity verification (optional)
 * @property isSupportsMultipart Indicates support for parallel/multipart downloads
 * @property isSupportsResume Indicates support for resuming interrupted downloads
 *
 * @see io.objectbox.annotation.Id for primary key configuration in ObjectBox
 * @see CompiledJson for DSL-JSON serialization configuration
 */
@CompiledJson
@Entity
class RemoteFileInfo() : Serializable {

	/**
	 * Unique identifier for the record in ObjectBox database.
	 * Fixed to 1 since there's typically only one instance per download operation.
	 */
	@Id
	@JvmField
	@JsonAttribute(name = "id")
	var id: Long = 0L

	/** Unique identifier for the associated download model in the system */
	@JvmField
	@JsonAttribute(name = "downloadDataModelId")
	var downloadDataModelDBId: Long = -1L

	/** True if the server forbids access to the file (HTTP 403, 401, etc.) */
	@JvmField
	@JsonAttribute(name = "isFileForbidden")
	var isFileForbidden: Boolean = false

	/** Error message explaining why the file metadata could not be retrieved */
	@JvmField
	@JsonAttribute(name = "errorMessage")
	var errorMessage: String = ""

	/** Name of the file extracted from Content-Disposition header or URL path */
	@JvmField
	@JsonAttribute(name = "fileName")
	var fileName: String = ""

	/** Size of the file in bytes; -1 indicates unknown or unavailable size */
	@JvmField
	@JsonAttribute(name = "fileSize")
	var fileSize: Long = 0L

	/** Optional cryptographic checksum (SHA-256, MD5) for file integrity verification */
	@JvmField
	@JsonAttribute(name = "fileChecksum")
	var fileChecksum: String = ""

	/** True if the server supports partial content downloads (HTTP Range requests) */
	@JvmField
	@JsonAttribute(name = "isSupportsMultipart")
	var isSupportsMultipart: Boolean = false

	/** True if the download can be resumed after interruption using range requests */
	@JvmField
	@JsonAttribute(name = "isSupportsResume")
	var isSupportsResume: Boolean = false
}