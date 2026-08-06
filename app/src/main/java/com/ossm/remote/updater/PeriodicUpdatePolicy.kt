package com.ossm.remote.updater

object PeriodicUpdatePolicy {
    const val UNIQUE_WORK_NAME = "periodic-update-check"
    const val INTERVAL_HOURS = 12L
    const val BACKOFF_MINUTES = 30L

    fun shouldRun(enabled: Boolean): Boolean = enabled

    fun shouldNotify(result: UpdateCheckResult): Boolean = result is UpdateCheckResult.Available
}
