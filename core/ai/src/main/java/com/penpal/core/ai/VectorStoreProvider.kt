package com.penpal.core.ai

/**
 * Simple holder to provide VectorStoreRepository to modules that cannot
 * directly access the Application instance (e.g., WorkManager workers).
 *
 * Set this in your Application.onCreate() before any workers run.
 */
object VectorStoreProvider {
    var instance: VectorStoreRepository? = null
}
