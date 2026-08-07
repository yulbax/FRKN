package io.github.yulbax.frkn

import android.app.Application
import io.github.yulbax.frkn.di.AppModule
import io.github.yulbax.frkn.util.Telemetry
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@KoinApplication(modules = [AppModule::class])
class FrknApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.install()
        startKoin<FrknApplication> {
            androidContext(this@FrknApplication)
        }
    }
}