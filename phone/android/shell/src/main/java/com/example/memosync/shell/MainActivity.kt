package com.example.memosync.shell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.moriafly.salt.ui.Button
import com.moriafly.salt.ui.Item
import com.moriafly.salt.ui.SaltConfigs
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.UnstableSaltUiApi
import com.moriafly.salt.ui.screen.BasicScreen
import com.moriafly.salt.ui.screen.ScreenCard

/**
 * Buildable SaltUI shell used by the GitHub Actions phone build.
 *
 * This deliberately uses only APIs verified from SaltUI 3.0.0 sources:
 *   SaltTheme / SaltConfigs, BasicScreen, ScreenCard, Item, Button, SaltTheme.dimens.
 *
 * The full sync/UI skeleton lives in :app and is wired to the tested Java core once the vendor
 * Wear Engine SDK and Room KSP are available.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SaltTheme(
                configs = SaltConfigs.default(isDarkTheme = isSystemInDarkTheme()),
            ) {
                ShellApp()
            }
        }
    }
}

@OptIn(UnstableSaltUiApi::class)
@Composable
private fun ShellApp() {
    BasicScreen(
        actionButton = null,
        title = "备忘录 POC",
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(contentPadding.calculateTopPadding()))

            ScreenCard(
                header = "同步状态",
                footer = "POC shell：真实 Wear Engine 待接入。",
            ) {
                Item(text = "手表：未连接", onClick = {})
                Item(text = "立即同步", onClick = {})
            }

            ScreenCard(
                header = "备忘录",
                footer = "共 2 条",
            ) {
                Item(text = "购物清单", onClick = {})
                Item(text = "会议记录", onClick = {})
            }

            Column(modifier = Modifier.padding(SaltTheme.dimens.padding)) {
                Button(onClick = {}, text = "新建备忘录")
            }

            Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
        }
    }
}
