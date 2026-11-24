package com.metallic.chiaki.common

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceManager
import com.metallic.chiaki.lib.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists all PS Translator settings (SharedPreferences) and PS5 host registrations/connections
 * (Room AppDatabase: registered_host + manual_host) so user settings and PS5 pairing survive
 * app exits, updates, and manual backup/restore across reinstalls.
 */
object AppStateBackupManager {
	private const val TAG = "AppStateBackup"
	private const val CROP_PREFS_NAME = "ps_translator_crop"
	private const val BACKUP_FILENAME = "ps_translator_backup.json"
	private const val BACKUP_RELATIVE_DIR = "Documents/PS_Translator"

	private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	fun autoBackupAsync(context: Context) {
		val appCtx = context.applicationContext
		ioScope.launch {
			autoBackupNow(appCtx)
		}
	}

	suspend fun autoBackupNow(context: Context): Boolean = withContext(Dispatchers.IO) {
		try {
			val appCtx = context.applicationContext
			val json = exportToJsonString(appCtx)
			if (json.isBlank()) return@withContext false

			// 1. Internal backup copy (survives in-place updates)
			try {
				File(appCtx.filesDir, BACKUP_FILENAME).writeText(json, Charsets.UTF_8)
			} catch (e: Exception) {
				Log.w(TAG, "Internal backup write failed: ${e.message}")
			}

			// 2. Shared Documents/PS_Translator copy (persists outside /data/data/)
			writeSharedDocumentsBackup(appCtx, json)
			true
		} catch (e: Exception) {
			Log.w(TAG, "autoBackupNow failed: ${e.message}")
			false
		}
	}

	suspend fun autoRestoreIfNeeded(context: Context): Boolean = withContext(Dispatchers.IO) {
		try {
			val appCtx = context.applicationContext
			val db = getDatabase(appCtx)
			val existingReg = try {
				db.registeredHostDao().getAll().firstOrError().blockingGet()
			} catch (_: Exception) {
				emptyList()
			}
			val cropPrefs = appCtx.getSharedPreferences(CROP_PREFS_NAME, Context.MODE_PRIVATE)
			val needsHostRestore = existingReg.isEmpty()
			val needsSettingsRestore = cropPrefs.all.size < 2

			if (!needsHostRestore && !needsSettingsRestore) {
				return@withContext false
			}

			val json = readSharedDocumentsBackup(appCtx)
				?: File(appCtx.filesDir, BACKUP_FILENAME).takeIf { it.exists() }?.readText(Charsets.UTF_8)
				?: return@withContext false

			importFromJsonString(
				context = appCtx,
				jsonString = json,
				restoreSettings = needsSettingsRestore,
				restoreHosts = needsHostRestore
			)
		} catch (e: Exception) {
			Log.w(TAG, "autoRestoreIfNeeded failed: ${e.message}")
			false
		}
	}

	suspend fun exportToUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
		try {
			val json = exportToJsonString(context.applicationContext)
			context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
				out.write(json.toByteArray(Charsets.UTF_8))
				out.flush()
			} ?: return@withContext false
			true
		} catch (e: Exception) {
			Log.e(TAG, "exportToUri failed", e)
			false
		}
	}

	suspend fun importFromUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
		try {
			val json = context.contentResolver.openInputStream(uri)?.use { input ->
				input.readBytes().toString(Charsets.UTF_8)
			} ?: return@withContext false
			val ok = importFromJsonString(
				context = context.applicationContext,
				jsonString = json,
				restoreSettings = true,
				restoreHosts = true
			)
			if (ok) {
				autoBackupNow(context.applicationContext)
			}
			ok
		} catch (e: Exception) {
			Log.e(TAG, "importFromUri failed", e)
			false
		}
	}

	fun exportToJsonString(context: Context): String {
		val appCtx = context.applicationContext
		val root = JSONObject()
		root.put("format", "ps-translator-backup")
		root.put("version", 1)
		root.put("timestamp", System.currentTimeMillis())

		// 1. PS Translator settings (ps_translator_crop)
		val cropPrefs = appCtx.getSharedPreferences(CROP_PREFS_NAME, Context.MODE_PRIVATE)
		root.put("crop_prefs", serializeSharedPrefs(cropPrefs.all))

		// 2. Chiaki default settings (video profile, FPS, bitrate, codec, discovery, etc.)
		val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(appCtx)
		root.put("default_prefs", serializeSharedPrefs(defaultPrefs.all))

		// 3. PS5 / PS4 Registered Hosts & Manual Hosts from Room DB
		val db = getDatabase(appCtx)
		val registeredHosts = try {
			db.registeredHostDao().getAll().firstOrError().blockingGet()
		} catch (_: Exception) {
			emptyList()
		}
		val manualHosts = try {
			db.manualHostDao().getAll().firstOrError().blockingGet()
		} catch (_: Exception) {
			emptyList()
		}

		val regArr = JSONArray()
		for (h in registeredHosts) {
			val obj = JSONObject()
			obj.put("target", h.target.value)
			obj.put("ap_ssid", h.apSsid ?: JSONObject.NULL)
			obj.put("ap_bssid", h.apBssid ?: JSONObject.NULL)
			obj.put("ap_key", h.apKey ?: JSONObject.NULL)
			obj.put("ap_name", h.apName ?: JSONObject.NULL)
			obj.put("server_mac", h.serverMac.value)
			obj.put("server_nickname", h.serverNickname ?: JSONObject.NULL)
			obj.put("rp_regist_key", Base64.encodeToString(h.rpRegistKey, Base64.NO_WRAP))
			obj.put("rp_key_type", h.rpKeyType)
			obj.put("rp_key", Base64.encodeToString(h.rpKey, Base64.NO_WRAP))
			regArr.put(obj)
		}
		root.put("registered_hosts", regArr)

		val idToMac = registeredHosts.associate { it.id to it.serverMac.value }
		val manualArr = JSONArray()
		for (m in manualHosts) {
			val obj = JSONObject()
			obj.put("host", m.host)
			val mac = m.registeredHost?.let { idToMac[it] }
			if (mac != null) {
				obj.put("server_mac", mac)
			} else {
				obj.put("server_mac", JSONObject.NULL)
			}
			manualArr.put(obj)
		}
		root.put("manual_hosts", manualArr)

		return root.toString(2)
	}

	fun importFromJsonString(
		context: Context,
		jsonString: String,
		restoreSettings: Boolean = true,
		restoreHosts: Boolean = true
	): Boolean {
		val appCtx = context.applicationContext
		val root = JSONObject(jsonString)
		if (root.optString("format") != "ps-translator-backup") {
			return false
		}

		var restoredAnything = false

		if (restoreSettings) {
			root.optJSONArray("crop_prefs")?.let { arr ->
				if (arr.length() > 0) {
					val prefs = appCtx.getSharedPreferences(CROP_PREFS_NAME, Context.MODE_PRIVATE)
					deserializeSharedPrefs(arr, prefs)
					restoredAnything = true
				}
			}
			root.optJSONArray("default_prefs")?.let { arr ->
				if (arr.length() > 0) {
					val prefs = PreferenceManager.getDefaultSharedPreferences(appCtx)
					deserializeSharedPrefs(arr, prefs)
					restoredAnything = true
				}
			}
		}

		if (restoreHosts) {
			val db = getDatabase(appCtx)
			val existingReg = try {
				db.registeredHostDao().getAll().firstOrError().blockingGet()
			} catch (_: Exception) {
				emptyList()
			}
			val existingMacToId = existingReg.associate { it.serverMac.value to it.id }.toMutableMap()

			val regArr = root.optJSONArray("registered_hosts")
			if (regArr != null) {
				for (i in 0 until regArr.length()) {
					val obj = regArr.optJSONObject(i) ?: continue
					val macVal = obj.optLong("server_mac", -1L)
					if (macVal < 0L || existingMacToId.containsKey(macVal)) continue
					val targetVal = obj.optInt("target", 1000)
					val registKeyB64 = obj.optString("rp_regist_key", "")
					val rpKeyB64 = obj.optString("rp_key", "")
					if (registKeyB64.isEmpty() || rpKeyB64.isEmpty()) continue

					val regHost = RegisteredHost(
						target = Target.fromValue(targetVal),
						apSsid = obj.optNullableString("ap_ssid"),
						apBssid = obj.optNullableString("ap_bssid"),
						apKey = obj.optNullableString("ap_key"),
						apName = obj.optNullableString("ap_name"),
						serverMac = MacAddress(macVal),
						serverNickname = obj.optNullableString("server_nickname"),
						rpRegistKey = Base64.decode(registKeyB64, Base64.DEFAULT),
						rpKeyType = obj.optInt("rp_key_type", 0),
						rpKey = Base64.decode(rpKeyB64, Base64.DEFAULT)
					)
					val newId = db.registeredHostDao().insert(regHost).blockingGet()
					existingMacToId[macVal] = newId
					restoredAnything = true
				}
			}

			val existingManual = try {
				db.manualHostDao().getAll().firstOrError().blockingGet()
			} catch (_: Exception) {
				emptyList()
			}
			val existingManualIps = existingManual.map { it.host }.toMutableSet()

			val manualArr = root.optJSONArray("manual_hosts")
			if (manualArr != null) {
				for (i in 0 until manualArr.length()) {
					val obj = manualArr.optJSONObject(i) ?: continue
					val hostIp = obj.optString("host", "").trim()
					if (hostIp.isEmpty() || existingManualIps.contains(hostIp)) continue
					val macVal = if (obj.isNull("server_mac")) null else obj.optLong("server_mac", -1L).takeIf { it >= 0L }
					val regId = macVal?.let { existingMacToId[it] }
					db.manualHostDao().insert(ManualHost(host = hostIp, registeredHost = regId)).blockingAwait()
					existingManualIps.add(hostIp)
					restoredAnything = true
				}
			}
		}

		return restoredAnything
	}

	private fun serializeSharedPrefs(map: Map<String, *>): JSONArray {
		val arr = JSONArray()
		for ((k, v) in map) {
			if (v == null) continue
			val item = JSONObject()
			item.put("k", k)
			when (v) {
				is Boolean -> {
					item.put("t", "B")
					item.put("v", v)
				}
				is Int -> {
					item.put("t", "I")
					item.put("v", v)
				}
				is Long -> {
					item.put("t", "L")
					item.put("v", v)
				}
				is Float -> {
					item.put("t", "F")
					item.put("v", v.toDouble())
				}
				is String -> {
					item.put("t", "S")
					item.put("v", v)
				}
				else -> continue
			}
			arr.put(item)
		}
		return arr
	}

	private fun deserializeSharedPrefs(arr: JSONArray, prefs: android.content.SharedPreferences) {
		val editor = prefs.edit()
		for (i in 0 until arr.length()) {
			val item = arr.optJSONObject(i) ?: continue
			val k = item.optString("k", "")
			if (k.isEmpty()) continue
			when (item.optString("t", "")) {
				"B" -> editor.putBoolean(k, item.optBoolean("v"))
				"I" -> editor.putInt(k, item.optInt("v"))
				"L" -> editor.putLong(k, item.optLong("v"))
				"F" -> editor.putFloat(k, item.optDouble("v").toFloat())
				"S" -> editor.putString(k, item.optString("v"))
			}
		}
		editor.commit()
	}

	private fun JSONObject.optNullableString(name: String): String? {
		return if (!has(name) || isNull(name)) null else optString(name, "")
	}

	private fun writeSharedDocumentsBackup(context: Context, json: String) {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				val resolver = context.contentResolver
				val collection = MediaStore.Files.getContentUri("external")
				val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
				val selectionArgs = arrayOf(BACKUP_FILENAME, "%PS_Translator%")
				var existingUri: Uri? = null
				resolver.query(
					collection,
					arrayOf(MediaStore.MediaColumns._ID),
					selection,
					selectionArgs,
					null
				)?.use { cursor ->
					if (cursor.moveToFirst()) {
						val id = cursor.getLong(0)
						existingUri = Uri.withAppendedPath(collection, id.toString())
					}
				}
				val targetUri = existingUri ?: resolver.insert(collection, ContentValues().apply {
					put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_FILENAME)
					put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
					put(MediaStore.MediaColumns.RELATIVE_PATH, BACKUP_RELATIVE_DIR)
				})
				if (targetUri != null) {
					resolver.openOutputStream(targetUri, "wt")?.use { out ->
						out.write(json.toByteArray(Charsets.UTF_8))
						out.flush()
					}
				}
			} else {
				@Suppress("DEPRECATION")
				val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "PS_Translator")
				if (!dir.exists()) dir.mkdirs()
				File(dir, BACKUP_FILENAME).writeText(json, Charsets.UTF_8)
			}
		} catch (e: Exception) {
			Log.w(TAG, "Shared Documents backup skipped: ${e.message}")
		}
	}

	private fun readSharedDocumentsBackup(context: Context): String? {
		return try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				val resolver = context.contentResolver
				val collection = MediaStore.Files.getContentUri("external")
				val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
				val selectionArgs = arrayOf(BACKUP_FILENAME, "%PS_Translator%")
				resolver.query(
					collection,
					arrayOf(MediaStore.MediaColumns._ID),
					selection,
					selectionArgs,
					"${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
				)?.use { cursor ->
					if (cursor.moveToFirst()) {
						val id = cursor.getLong(0)
						val uri = Uri.withAppendedPath(collection, id.toString())
						resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
					} else null
				}
			} else {
				@Suppress("DEPRECATION")
				val file = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "PS_Translator"), BACKUP_FILENAME)
				if (file.exists()) file.readText(Charsets.UTF_8) else null
			}
		} catch (e: Exception) {
			Log.w(TAG, "Shared Documents read skipped: ${e.message}")
			null
		}
	}
}
