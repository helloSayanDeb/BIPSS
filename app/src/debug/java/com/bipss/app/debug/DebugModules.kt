package com.bipss.app.debug

import com.bipss.app.debug.sim.SimulatedBand
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/** Debug-only Koin bindings. The release source set has an empty counterpart. */
val debugModules: List<Module> = listOf(
    module {
        single { SimulatedBand(get(), get(), androidContext()) }
    }
)
