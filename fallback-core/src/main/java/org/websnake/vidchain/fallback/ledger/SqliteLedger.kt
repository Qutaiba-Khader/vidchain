package org.websnake.vidchain.fallback.ledger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap
import org.websnake.vidchain.fallback.classifier.UserIntent
import org.websnake.vidchain.fallback.trace.Redactor

/**
 * The persistent ledger: its own database file (vidchain-fallback.db), separate from the upstream ObjectBox store.
 * UNIQUE(parent_id, attempt_no) makes [claim] atomic; the handled table's primary key does the same for [markHandled].
 * Reasons pass through the Redactor before they are stored. Intents and child links are cached in memory (write-through):
 * the observer reads them for every download on every tick.
 */
class SqliteLedger(context: Context) : AttemptLedger {
	private val helper = object : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
		override fun onCreate(db: SQLiteDatabase) {
			db.execSQL("CREATE TABLE intents (download_id TEXT PRIMARY KEY, intent TEXT NOT NULL, seq INTEGER NOT NULL, time INTEGER NOT NULL)")
			db.execSQL("CREATE TABLE handled (download_id TEXT NOT NULL, signature TEXT NOT NULL, time INTEGER NOT NULL, PRIMARY KEY (download_id, signature))")
			db.execSQL("CREATE TABLE attempts (parent_id TEXT NOT NULL, attempt_no INTEGER NOT NULL, method TEXT NOT NULL, state TEXT NOT NULL, " +
				"child_id TEXT, reason TEXT, started INTEGER NOT NULL, ended INTEGER, UNIQUE (parent_id, attempt_no))")
			db.execSQL("CREATE TABLE children (child_id TEXT PRIMARY KEY, parent_id TEXT NOT NULL)")
			onUpgrade(db, 1, DB_VERSION)
		}

		override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
			if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS parents (parent_id TEXT PRIMARY KEY, effective_url TEXT)")
		}
	}

	private val db: SQLiteDatabase get() = helper.writableDatabase

	private val intentCache: ConcurrentHashMap<String, Pair<UserIntent, Long>> by lazy {
		ConcurrentHashMap<String, Pair<UserIntent, Long>>().also { m ->
			db.rawQuery("SELECT download_id, intent, seq FROM intents", null).use { c ->
				while (c.moveToNext()) m[c.getString(0)] = (runCatching { UserIntent.valueOf(c.getString(1)) }.getOrDefault(UserIntent.NONE)) to c.getLong(2)
			}
		}
	}
	private val childCache: ConcurrentHashMap<String, String> by lazy {
		ConcurrentHashMap<String, String>().also { m ->
			db.rawQuery("SELECT child_id, parent_id FROM children", null).use { c -> while (c.moveToNext()) m[c.getString(0)] = c.getString(1) }
		}
	}

	@Synchronized
	override fun recordIntent(downloadId: String, intent: UserIntent, timeMs: Long): Long {
		val cache = intentCache
		val d = db
		d.beginTransaction()
		try {
			val seq = intentSeqIn(d, downloadId) + 1
			d.insertWithOnConflict("intents", null, ContentValues().apply {
				put("download_id", downloadId); put("intent", intent.name); put("seq", seq); put("time", timeMs)
			}, SQLiteDatabase.CONFLICT_REPLACE)
			d.setTransactionSuccessful()
			cache[downloadId] = intent to seq
			return seq
		} finally {
			d.endTransaction()
		}
	}

	override fun intentOf(downloadId: String): UserIntent = intentCache[downloadId]?.first ?: UserIntent.NONE

	override fun intentSeq(downloadId: String): Long = intentCache[downloadId]?.second ?: 0L

	private fun intentSeqIn(d: SQLiteDatabase, downloadId: String): Long =
		d.rawQuery("SELECT seq FROM intents WHERE download_id = ?", arrayOf(downloadId)).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

	override fun markHandled(downloadId: String, signature: String, timeMs: Long): Boolean =
		db.insertWithOnConflict("handled", null, ContentValues().apply {
			put("download_id", downloadId); put("signature", signature); put("time", timeMs)
		}, SQLiteDatabase.CONFLICT_IGNORE) != -1L

	override fun claim(parentId: String, attemptNo: Int, method: String, timeMs: Long): Boolean =
		db.insertWithOnConflict("attempts", null, ContentValues().apply {
			put("parent_id", parentId); put("attempt_no", attemptNo); put("method", method)
			put("state", AttemptState.RUNNING.name); put("started", timeMs)
		}, SQLiteDatabase.CONFLICT_IGNORE) != -1L

	override fun update(parentId: String, attemptNo: Int, state: AttemptState, childId: String?, reason: String?, timeMs: Long) {
		db.update("attempts", ContentValues().apply {
			put("state", state.name); put("reason", Redactor.clean(reason)); put("ended", timeMs)
			if (childId != null) put("child_id", childId)
		}, "parent_id = ? AND attempt_no = ?", arrayOf(parentId, attemptNo.toString()))
	}

	override fun attempts(parentId: String): List<AttemptRow> =
		db.rawQuery("SELECT attempt_no, method, state, child_id, reason, started, ended FROM attempts WHERE parent_id = ? ORDER BY attempt_no",
			arrayOf(parentId)).use { c ->
			buildList {
				while (c.moveToNext()) add(AttemptRow(parentId, c.getInt(0), c.getString(1),
					runCatching { AttemptState.valueOf(c.getString(2)) }.getOrDefault(AttemptState.FAILED),
					c.getString(3), c.getString(4), c.getLong(5), if (c.isNull(6)) null else c.getLong(6)))
			}
		}

	override fun recentParents(limit: Int): List<String> =
		db.rawQuery("SELECT parent_id FROM attempts GROUP BY parent_id ORDER BY MAX(started) DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
			buildList { while (c.moveToNext()) add(c.getString(0)) }
		}

	override fun effectiveUrl(parentId: String): String? =
		db.rawQuery("SELECT effective_url FROM parents WHERE parent_id = ?", arrayOf(parentId)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

	override fun setEffectiveUrl(parentId: String, url: String) {
		db.insertWithOnConflict("parents", null, ContentValues().apply { put("parent_id", parentId); put("effective_url", url) }, SQLiteDatabase.CONFLICT_REPLACE)
	}

	override fun mapChild(childId: String, parentId: String) {
		childCache[childId] = parentId
		db.insertWithOnConflict("children", null, ContentValues().apply { put("child_id", childId); put("parent_id", parentId) },
			SQLiteDatabase.CONFLICT_REPLACE)
	}

	override fun parentOf(childId: String): String? = childCache[childId]

	companion object {
		const val DB_NAME = "vidchain-fallback.db"
		const val DB_VERSION = 2
	}
}
