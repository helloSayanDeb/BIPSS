package com.bipss.app.debug

import org.koin.core.module.Module

/** Release builds have no simulator. See the debug source set. */
val debugModules: List<Module> = emptyList()
