// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import androidx.lifecycle.ViewModel
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.toLiveData
import com.metallic.chiaki.discovery.DiscoveryManager
import com.metallic.chiaki.discovery.serverMac
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers

class MainViewModel(val database: AppDatabase, val preferences: Preferences): ViewModel()
{
	private val disposable = CompositeDisposable()

	val discoveryManager = DiscoveryManager().also {
		it.active = preferences.discoveryEnabled
		it.discoveryActive
			.observeOn(AndroidSchedulers.mainThread())
			.subscribe { preferences.discoveryEnabled = it }
			.addTo(disposable)
	}

	val displayHosts by lazy {
		Observables.combineLatest(
			database.manualHostDao().getAll().toObservable(),
			database.registeredHostDao().getAll().toObservable(),
			discoveryManager.discoveredHosts)
			{ manualHosts, registeredHosts, discoveredHosts ->
				val macRegisteredHosts = registeredHosts.associateBy { it.serverMac }
				val idRegisteredHosts = registeredHosts.associateBy { it.id }
				val discoveredList = discoveredHosts.map {
					DiscoveredDisplayHost(it.serverMac?.let { mac -> macRegisteredHosts[mac] }, it)
				}
				val discoveredIps = discoveredList.map { it.host }.toSet()
				val discoveredRegIds = discoveredList.mapNotNull { it.registeredHost?.id }.toSet()
				val uniqueManualList = manualHosts
					.filter { m -> m.host !in discoveredIps && (m.registeredHost == null || m.registeredHost !in discoveredRegIds) }
					.map {
						ManualDisplayHost(it.registeredHost?.let { id -> idRegisteredHosts[id] }, it)
					}
				discoveredList + uniqueManualList
			}
			.toLiveData()
	}

	fun rememberConnectedHost(hostIp: String, registeredHostId: Long)
	{
		val cleanIp = hostIp.trim()
		if (cleanIp.isEmpty()) return
		database.manualHostDao()
			.getAll()
			.firstOrError()
			.flatMapCompletable { existing ->
				val byIp = existing.firstOrNull { it.host == cleanIp }
				val byReg = existing.firstOrNull { it.registeredHost == registeredHostId }
				when {
					byIp != null -> {
						if (byIp.registeredHost != registeredHostId) {
							database.manualHostDao().assignRegisteredHost(byIp.id, registeredHostId)
						} else {
							io.reactivex.Completable.complete()
						}
					}
					byReg != null -> {
						database.manualHostDao().update(byReg.copy(host = cleanIp))
					}
					else -> {
						database.manualHostDao().insert(ManualHost(host = cleanIp, registeredHost = registeredHostId))
					}
				}
			}
			.onErrorComplete()
			.subscribeOn(Schedulers.io())
			.subscribe()
			.addTo(disposable)
	}

	val discoveryActive by lazy {
		discoveryManager.discoveryActive.toLiveData()
	}

	fun deleteManualHost(manualHost: ManualHost)
	{
		database.manualHostDao()
			.delete(manualHost)
			.onErrorComplete()
			.subscribeOn(Schedulers.io())
			.subscribe()
			.addTo(disposable)
	}

	override fun onCleared()
	{
		super.onCleared()
		disposable.dispose()
		discoveryManager.dispose()
	}
}