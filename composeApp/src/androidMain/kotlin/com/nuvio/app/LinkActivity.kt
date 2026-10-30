package com.nuvio.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.nuvio.app.features.settings.AppIconPlatform

class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.dataString?.let { link ->
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = AppIconPlatform.currentLauncherComponent(this@LinkActivity)
                    putExtra(EXTRA_LINK, link)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_LINK = "link"
    }
}
