package com.metallic.chiaki.stream

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Lightweight on-device SQLite translation cache for instant (0ms) subtitle & popup lookup.
 * Keys are isolated by provider group, translation tone, and game context glossary hash
 * so switching AI providers or updating the glossary never serves stale translations.
 */
class TranslationCacheDb(context: Context) : SQLiteOpenHelper(
	context.applicationContext,
	DB_NAME,
	null,
	DB_VERSION
) {
	companion object {
		private const val DB_NAME = "ps_translator_cache.db"
		private const val DB_VERSION = 1
		private const val TABLE_NAME = "translation_cache"
		private const val MAX_ROWS = 30000

		fun normalizeEnglish(text: String): String {
			return text.lowercase()
				.replace('’', '\'')
				.replace('‘', '\'')
				.replace('“', '"')
				.replace('”', '"')
				.replace(Regex("\\s+"), " ")
				.trim()
				.trim('"', '\'', '.', '!', '?', ',', ';', ':', ' ')
		}
	}

	override fun onCreate(db: SQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS $TABLE_NAME (
				cache_key TEXT NOT NULL,
				normalized_en TEXT NOT NULL,
				thai_text TEXT NOT NULL,
				updated_at INTEGER NOT NULL,
				PRIMARY KEY (cache_key, normalized_en)
			)
			""".trimIndent()
		)
		db.execSQL("CREATE INDEX IF NOT EXISTS idx_cache_updated_at ON $TABLE_NAME(updated_at)")
	}

	override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
		db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
		onCreate(db)
	}

	@Synchronized
	fun get(cacheKey: String, englishText: String): String? {
		val norm = normalizeEnglish(englishText)
		if (norm.isEmpty()) return null
		return try {
			readableDatabase.rawQuery(
				"SELECT thai_text FROM $TABLE_NAME WHERE cache_key = ? AND normalized_en = ? LIMIT 1",
				arrayOf(cacheKey, norm)
			).use { cursor ->
				if (cursor.moveToFirst()) cursor.getString(0) else null
			}
		} catch (_: Exception) {
			null
		}
	}

	@Synchronized
	fun put(cacheKey: String, englishText: String, thaiText: String) {
		val norm = normalizeEnglish(englishText)
		val cleanThai = thaiText.trim()
		if (norm.isEmpty() || cleanThai.isEmpty()) return
		// Only cache genuine Thai translations (never cache raw English fallback or broken outputs)
		if (!cleanThai.any { it in '\u0E00'..'\u0E7F' }) return

		try {
			val db = writableDatabase
			val values = ContentValues().apply {
				put("cache_key", cacheKey)
				put("normalized_en", norm)
				put("thai_text", cleanThai)
				put("updated_at", System.currentTimeMillis())
			}
			db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)

			// Periodically prune oldest rows if database exceeds cap
			if (System.currentTimeMillis() % 50L == 0L) {
				pruneIfNeeded(db)
			}
		} catch (_: Exception) {}
	}

	private fun pruneIfNeeded(db: SQLiteDatabase) {
		try {
			val total = db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null).use { c ->
				if (c.moveToFirst()) c.getLong(0) else 0L
			}
			if (total > MAX_ROWS) {
				val toDelete = total - (MAX_ROWS - 2000)
				db.execSQL(
					"DELETE FROM $TABLE_NAME WHERE rowid IN (SELECT rowid FROM $TABLE_NAME ORDER BY updated_at ASC LIMIT ?)",
					arrayOf(toDelete)
				)
			}
		} catch (_: Exception) {}
	}

	@Synchronized
	fun count(): Long {
		return try {
			readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null).use { c ->
				if (c.moveToFirst()) c.getLong(0) else 0L
			}
		} catch (_: Exception) {
			0L
		}
	}

	@Synchronized
	fun clearAll() {
		try {
			writableDatabase.delete(TABLE_NAME, null, null)
		} catch (_: Exception) {}
	}
}
