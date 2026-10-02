@file:Suppress("UNCHECKED_CAST")

package lib.process

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

object CopyObjectUtils {

	private val logger = LogHelperUtils.from(javaClass)

	@JvmStatic
	fun <T : Serializable?> deepCopy(`object`: T): T? {
		return try {
			val bos = ByteArrayOutputStream()
			val oos = ObjectOutputStream(bos)
			oos.writeObject(`object`)
			oos.flush()
			oos.close()
			bos.close()

			val bis = ByteArrayInputStream(bos.toByteArray())
			val ois = ObjectInputStream(bis)
			val copy = ois.readObject() as T
			ois.close()
			bis.close()

			copy
		} catch (error: Exception) {
			logger.e("Error while deep copping an object:", error)
			null
		}
	}
}