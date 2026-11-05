package com.example.memosync.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import com.example.memosync.android.sync.SyncManager
import com.example.memosync.android.ui.MemoSyncApp
import com.moriafly.salt.ui.SaltConfigs
import com.moriafly.salt.ui.SaltTheme

/**
 * NOT COMPILED IN THIS POC - Android source skeleton only.
 *
 * App entry point. SaltUI is the UI stack (requirement: use SaltUI, not plain Material):
 *  - [SaltTheme] wraps the whole app root (docs: 安装与接入 / 主题);
 *  - all screens read colors/text styles from SaltTheme, so dark/light mode and the Salt
 *    design language are applied consistently.
 *
 * Lifecycle -> sync triggers (design doc §2.3 / §5.4):
 *  - onStart: requestSync("app_start")
 *  - saving a memo: SyncManager.requestSync("save")
 *  - P2P receive / reconnect: handled by the sync layer.
 *
 * The tested logic lives in the Java 17 core (phone/core); this file is UI wiring only.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // SaltUI root theme. Docs:
            //   https://moriafly.com/program/salt-ui/getting-started/installation.html
            //   https://moriafly.com/program/salt-ui/foundations/theme.html
            // TODO(SaltUi): optional SaltDynamicColors / custom textStyles once the pinned
            // SaltUI 3.0.0 sources are available; defaults are used for the POC skeleton.
            SaltTheme(
                configs = SaltConfigs.default(isDarkTheme = isSystemInDarkTheme()),
            ) {
                MemoSyncApp(syncManager = SyncManager.get(applicationContext))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // SaltUI screens are ready; ask the tested hub to sync.
        SyncManager.get(applicationContext).requestSync("app_start")
    }

    override fun onStop() {
        super.onStop()
        // Design doc §5.4: no guaranteed background sync; the next onStart syncs again.
    }
}
