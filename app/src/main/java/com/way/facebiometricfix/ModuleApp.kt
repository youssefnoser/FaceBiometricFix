package com.way.facebiometricfix

import android.app.Application
import android.os.Handler
import android.os.Looper
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * Receives the LSPosed service binder. The framework delivers it once per process, so it's
 * held here rather than in an Activity, which may be recreated.
 */
class ModuleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                mainHandler.post { setService(service) }
            }

            override fun onServiceDied(service: XposedService) {
                mainHandler.post { if (ModuleApp.service === service) setService(null) }
            }
        })
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val observers = mutableSetOf<(XposedService?) -> Unit>()

        /** The connected service, or null if LSPosed hasn't bound to this app. Main thread only. */
        var service: XposedService? = null
            private set

        private fun setService(value: XposedService?) {
            service = value
            observers.toList().forEach { it(value) }
        }

        /** Calls [observer] now and on every change. Main thread only. */
        fun observe(observer: (XposedService?) -> Unit) {
            observers.add(observer)
            observer(service)
        }

        fun stopObserving(observer: (XposedService?) -> Unit) {
            observers.remove(observer)
        }
    }
}
