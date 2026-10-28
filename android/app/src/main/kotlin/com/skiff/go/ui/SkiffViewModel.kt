// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Skiff Go contributors. Based on the Skiff firmware (illuminus5) and Felucca (Hügelton Instruments);
// see NOTICE.md.
package com.skiff.go.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.skiff.go.device.DeviceController

class SkiffViewModel(app: Application) : AndroidViewModel(app) {
    val controller = DeviceController(app)

    fun refreshDevices() = controller.refreshDevices()
    fun connectPreferred() = controller.connectPreferred()
    fun say(message: String) = controller.say(message)

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }
}
