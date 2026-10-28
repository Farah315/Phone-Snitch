package com.farah.phonesnitch

import android.app.Application
import com.farah.phonesnitch.work.ClassifyScheduler

class PhoneSnitchApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Daily background run + a catch-up run at every cold start.
        // The use case skips itself if today's classification already happened.
        ClassifyScheduler.schedulePeriodic(this)
        ClassifyScheduler.runNow(this, force = false)
    }
}
