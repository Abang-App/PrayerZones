package com.abang.prayerzones.ui.screens

import com.abang.prayerzones.util.GeofenceManager
import com.abang.prayerzones.util.InMosqueModeManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint used inside Composables (via EntryPointAccessors.fromApplication)
 * to retrieve the GeofenceManager and InMosqueModeManager singletons without ViewModel injection.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GeofenceEntryPoint {
    fun geofenceManager(): GeofenceManager
    fun inMosqueModeManager(): InMosqueModeManager
}

