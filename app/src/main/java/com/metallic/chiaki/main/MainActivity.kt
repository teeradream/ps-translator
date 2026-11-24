// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.R
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.putRevealExtra
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityMainBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.manualconsole.EditManualConsoleActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.stream.AppUpdater
import com.metallic.chiaki.stream.StreamActivity
import com.metallic.chiaki.stream.TranslationCacheDb
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity()
{
	companion object {
		private const val REQUEST_CODE_EXPORT_BACKUP = 9101
		private const val REQUEST_CODE_IMPORT_BACKUP = 9102
	}

	private lateinit var viewModel: MainViewModel

	private lateinit var binding: ActivityMainBinding
	private var discoveryMenuItem: MenuItem? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
			android.util.Log.e("PSTranslator", "Uncaught exception on thread $thread", throwable)
		}

		try
		{
			binding = ActivityMainBinding.inflate(layoutInflater)
			setContentView(binding.root)

			title = ""
			setSupportActionBar(binding.toolbar)

			binding.floatingActionButton.setOnClickListener {
				expandFloatingActionButton(!binding.floatingActionButton.isExpanded)
			}
			binding.floatingActionButtonDialBackground.setOnClickListener {
				expandFloatingActionButton(false)
			}

			binding.addManualButton.setOnClickListener { addManualConsole() }
			binding.addManualLabelButton.setOnClickListener { addManualConsole() }

			binding.registerButton.setOnClickListener { showRegistration() }
			binding.registerLabelButton.setOnClickListener { showRegistration() }

			viewModel = ViewModelProvider(this, viewModelFactory { MainViewModel(getDatabase(this), Preferences(this)) })
				.get(MainViewModel::class.java)

			val recyclerViewAdapter = DisplayHostRecyclerViewAdapter(this::hostTriggered, this::wakeupHost, this::editHost, this::deleteHost)
			binding.hostsRecyclerView.adapter = recyclerViewAdapter
			binding.hostsRecyclerView.layoutManager = LinearLayoutManager(this)
			viewModel.displayHosts.observe(this, Observer {
				val top = binding.hostsRecyclerView.computeVerticalScrollOffset() == 0
				recyclerViewAdapter.hosts = it
				if(top)
					binding.hostsRecyclerView.scrollToPosition(0)
				updateEmptyInfo()
			})

			viewModel.discoveryActive.observe(this, Observer { active ->
				discoveryMenuItem?.let { updateDiscoveryMenuItem(it, active) }
				updateEmptyInfo()
			})

			checkForAppUpdateOnLaunch()

			// Auto-restore PS5 hosts & app settings if DB/prefs are empty (e.g. after clean install)
			lifecycleScope.launch {
				val restored = AppStateBackupManager.autoRestoreIfNeeded(applicationContext)
				if (restored && !isFinishing && !isDestroyed) {
					Toast.makeText(this@MainActivity, "กู้คืนการตั้งค่าและข้อมูล PS5 อัตโนมัติแล้ว", Toast.LENGTH_LONG).show()
				}
			}
		}
		catch(e: Throwable)
		{
			android.util.Log.e("PSTranslator", "Error in onCreate", e)
			MaterialAlertDialogBuilder(this)
				.setTitle("Launch Error")
				.setMessage("An initialization error occurred:\n${e.message}\n${android.util.Log.getStackTraceString(e)}")
				.setPositiveButton("OK", null)
				.show()
		}
	}

	private fun updateEmptyInfo()
	{
		if(!::viewModel.isInitialized || (viewModel.displayHosts.value?.isEmpty() ?: true))
		{
			binding.emptyInfoLayout.visibility = View.VISIBLE
			val discoveryActive = if(::viewModel.isInitialized) (viewModel.discoveryActive.value ?: false) else false
			binding.emptyInfoImageView.setImageResource(if(discoveryActive) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
			binding.emptyInfoTextView.setText(if(discoveryActive) R.string.display_hosts_empty_discovery_on_info else R.string.display_hosts_empty_discovery_off_info)
		}
		else
			binding.emptyInfoLayout.visibility = View.GONE
	}

	private fun expandFloatingActionButton(expand: Boolean)
	{
		binding.floatingActionButton.isExpanded = expand
		binding.floatingActionButton.isActivated = binding.floatingActionButton.isExpanded
	}

	override fun onStart()
	{
		super.onStart()
		if(::viewModel.isInitialized)
			viewModel.discoveryManager.resume()
	}

	override fun onStop()
	{
		super.onStop()
		if(::viewModel.isInitialized)
			viewModel.discoveryManager.pause()
	}

	override fun onBackPressed()
	{
		if(binding.floatingActionButton.isExpanded)
		{
			expandFloatingActionButton(false)
			return
		}
		super.onBackPressed()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean
	{
		menuInflater.inflate(R.menu.main, menu)
		val discoveryItem = menu.findItem(R.id.action_discover)
		discoveryMenuItem = discoveryItem
		val discoveryActive = if(::viewModel.isInitialized) (viewModel.discoveryActive.value ?: false) else false
		updateDiscoveryMenuItem(discoveryItem, discoveryActive)
		return true
	}

	private fun updateDiscoveryMenuItem(item: MenuItem, active: Boolean)
	{
		item.isChecked = active
		item.setIcon(if(active) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when(item.itemId)
	{
		R.id.action_discover ->
		{
			viewModel.discoveryManager.active = !(viewModel.discoveryActive.value ?: false)
			true
		}

		R.id.action_update_app ->
		{
			showUpdateOptionsDialog()
			true
		}

		R.id.action_settings ->
		{
			Intent(this, SettingsActivity::class.java).also {
				startActivity(it)
			}
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	private fun checkForAppUpdateOnLaunch() {
		lifecycleScope.launch {
			val info = AppUpdater.fetchLatestReleaseInfo() ?: return@launch
			val currentTag = "v${BuildConfig.VERSION_NAME}"
			if (info.tagName.isNotEmpty() && !info.tagName.equals(currentTag, ignoreCase = true) && !isFinishing && !isDestroyed) {
				MaterialAlertDialogBuilder(this@MainActivity)
					.setTitle("พบอัปเดตเวอร์ชันใหม่ (${info.tagName})")
					.setMessage("${info.title}\n\nต้องการดาวน์โหลดและอัปเดตในแอปทันทีโดยไม่ต้องโหลดไฟล์เองหรือไม่?")
					.setPositiveButton("อัปเดตทันที") { _, _ ->
						startInAppApkUpdate(info.apkUrl)
					}
					.setNegativeButton("ไว้ทีหลัง", null)
					.show()
			}
		}
	}

	private fun showUpdateOptionsDialog() {
		val items = arrayOf(
			"☁️ ซิงค์คลังซับล่าสุดจากคลาวด์ (ไม่ต้องลงแอปใหม่)",
			"🔄 ดาวน์โหลดและอัปเดตตัวแอปเป็นเวอร์ชันล่าสุด",
			"💾 สำรองการตั้งค่าและข้อมูล PS5 (ส่งออกไฟล์)",
			"📂 กู้คืนการตั้งค่าและข้อมูล PS5 (นำเข้าไฟล์)"
		)
		MaterialAlertDialogBuilder(this)
			.setTitle("อัปเดต & สำรองข้อมูล PS Translator (v${BuildConfig.VERSION_NAME})")
			.setItems(items) { _, which ->
				when (which) {
					0 -> {
						Toast.makeText(this, "กำลังซิงค์คลังซับล่าสุดจากคลาวด์...", Toast.LENGTH_SHORT).show()
						lifecycleScope.launch {
							val db = TranslationCacheDb(applicationContext)
							val count = AppUpdater.syncCloudSubtitleDatabase(this@MainActivity, db)
							val total = db.count()
							try { db.close() } catch (_: Exception) {}
							val msg = if (count > 0) {
								"ซิงค์คลังซับสำเร็จ! (รวมในเครื่อง $total ประโยค)"
							} else {
								"ซิงค์ไม่สำเร็จ กรุณาตรวจสอบอินเทอร์เน็ต"
							}
							Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
						}
					}
					1 -> {
						lifecycleScope.launch {
							val info = AppUpdater.fetchLatestReleaseInfo()
							startInAppApkUpdate(info?.apkUrl ?: "https://github.com/teeradream/ps-translator/releases/latest/download/PS-Translator.apk")
						}
					}
					2 -> {
						val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
							addCategory(Intent.CATEGORY_OPENABLE)
							type = "application/json"
							putExtra(Intent.EXTRA_TITLE, "ps_translator_backup.json")
						}
						@Suppress("DEPRECATION")
						startActivityForResult(intent, REQUEST_CODE_EXPORT_BACKUP)
					}
					3 -> {
						val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
							addCategory(Intent.CATEGORY_OPENABLE)
							type = "*/*"
						}
						@Suppress("DEPRECATION")
						startActivityForResult(intent, REQUEST_CODE_IMPORT_BACKUP)
					}
				}
			}
			.setNegativeButton("ยกเลิก", null)
			.show()
	}

	@Suppress("DEPRECATION")
	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)
		if (resultCode != RESULT_OK) return
		val uri = data?.data ?: return
		when (requestCode) {
			REQUEST_CODE_EXPORT_BACKUP -> {
				lifecycleScope.launch {
					val ok = AppStateBackupManager.exportToUri(this@MainActivity, uri)
					Toast.makeText(
						this@MainActivity,
						if (ok) "สำรองการตั้งค่าและข้อมูล PS5 สำเร็จแล้ว!" else "สำรองข้อมูลไม่สำเร็จ",
						Toast.LENGTH_LONG
					).show()
				}
			}
			REQUEST_CODE_IMPORT_BACKUP -> {
				lifecycleScope.launch {
					val ok = AppStateBackupManager.importFromUri(this@MainActivity, uri)
					Toast.makeText(
						this@MainActivity,
						if (ok) "กู้คืนการตั้งค่าและข้อมูล PS5 สำเร็จแล้ว!" else "ไฟล์สำรองไม่ถูกต้องหรือไม่สามารถกู้คืนได้",
						Toast.LENGTH_LONG
					).show()
				}
			}
		}
	}

	private fun startInAppApkUpdate(apkUrl: String) {
		val progressDialog = MaterialAlertDialogBuilder(this)
			.setTitle("กำลังดาวน์โหลดอัปเดต...")
			.setMessage("กำลังสำรองการตั้งค่าและข้อมูล PS5...")
			.setCancelable(false)
			.show()
		lifecycleScope.launch {
			// Always flush a fresh backup of settings + PS5 hosts right before updating
			AppStateBackupManager.autoBackupNow(applicationContext)
			val ok = AppUpdater.downloadAndInstallApk(this@MainActivity, apkUrl) { pct ->
				if (progressDialog.isShowing) {
					progressDialog.setMessage("กำลังดาวน์โหลด $pct% ...")
				}
			}
			if (progressDialog.isShowing) {
				try { progressDialog.dismiss() } catch (_: Exception) {}
			}
			if (!ok && !isFinishing && !isDestroyed) {
				Toast.makeText(this@MainActivity, "ดาวน์โหลดอัปเดตไม่สำเร็จ กรุณาลองใหม่", Toast.LENGTH_LONG).show()
			}
		}
	}

	private fun addManualConsole()
	{
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putRevealExtra(binding.addManualButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun showRegistration()
	{
		Intent(this, RegistActivity::class.java).also {
			it.putRevealExtra(binding.registerButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun hostTriggered(host: DisplayHost)
	{
		val registeredHost = host.registeredHost
		if(registeredHost != null)
		{
			fun connect() {
				// Permanently remember this PS5 IP + registration so it stays saved after exiting & updating
				viewModel.rememberConnectedHost(host.host, registeredHost.id)
				AppStateBackupManager.autoBackupAsync(applicationContext)
				val connectInfo = ConnectInfo(host.isPS5, host.host, registeredHost.rpRegistKey, registeredHost.rpKey, Preferences(this).videoProfile)
				Intent(this, StreamActivity::class.java).let {
					it.putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
					startActivity(it)
				}
			}

			if(host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY)
			{
				MaterialAlertDialogBuilder(this)
					.setMessage(R.string.alert_message_standby_wakeup)
					.setPositiveButton(R.string.action_wakeup) { _, _ ->
						wakeupHost(host)
					}
					.setNeutralButton(R.string.action_connect_immediately) { _, _ ->
						connect()
					}
					.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> }
					.create()
					.show()
			}
			else
				connect()
		}
		else
		{
			Intent(this, RegistActivity::class.java).let {
				it.putExtra(RegistActivity.EXTRA_HOST, host.host)
				it.putExtra(RegistActivity.EXTRA_BROADCAST, false)
				if(host is ManualDisplayHost)
					it.putExtra(RegistActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, host.manualHost.id)
				startActivity(it)
			}
		}
	}

	private fun wakeupHost(host: DisplayHost)
	{
		val registeredHost = host.registeredHost ?: return
		viewModel.discoveryManager.sendWakeup(host.host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
	}

	private fun editHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putExtra(EditManualConsoleActivity.EXTRA_MANUAL_HOST_ID, host.manualHost.id)
			startActivity(it)
		}
	}

	private fun deleteHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.alert_message_delete_manual_host, host.manualHost.host))
			.setPositiveButton(R.string.action_delete) { _, _ ->
				viewModel.deleteManualHost(host.manualHost)
			}
			.setNegativeButton(R.string.action_keep) { _, _ -> }
			.create()
			.show()
	}
}
