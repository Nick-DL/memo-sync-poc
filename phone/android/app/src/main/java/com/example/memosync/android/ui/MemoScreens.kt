package com.example.memosync.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import com.example.memosync.android.sync.SyncManager
import com.moriafly.salt.ui.Button
import com.moriafly.salt.ui.Icon
import com.moriafly.salt.ui.Item
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.Text
import com.moriafly.salt.ui.UnstableSaltUiApi
import com.moriafly.salt.ui.icons.ChevronRight
import com.moriafly.salt.ui.icons.Check
import com.moriafly.salt.ui.icons.SaltIcons
import com.moriafly.salt.ui.screen.BasicScreen
import com.moriafly.salt.ui.screen.ScreenCard
import com.moriafly.salt.ui.screen.TitleBarButton

/**
 * NOT COMPILED IN THIS POC - Android source skeleton only.
 *
 * SaltUI screens for the phone app: list / edit / conflict / settings.
 * Built from the documented SaltUI primitives:
 *   - BasicScreen  (page skeleton + title bar): https://moriafly.com/program/salt-ui/screen.html
 *   - ScreenCard   (page sections):            https://moriafly.com/program/salt-ui/screen.html
 *   - Item         (settings/list rows):       https://moriafly.com/program/salt-ui/components/items.html
 *   - Button       (primary actions):          https://moriafly.com/program/salt-ui/components/primitives.html
 *   - Text/Icon/SaltIcons:                     https://moriafly.com/program/salt-ui/components/primitives.html
 *   - SaltTheme    (colors/text styles):       https://moriafly.com/program/salt-ui/foundations/theme.html
 *
 * BasicScreen/ScreenCard are marked UnstableSaltUiApi by SaltUI, hence the @OptIn annotations.
 * Anything whose exact signature could not be confirmed from the docs is marked TODO(SaltUi)
 * instead of being invented by this skeleton.
 *
 * The memo list below is UI-only demo data; wiring it to Room + the tested Java sync core is a
 * TODO(Android) (see MemoSyncApp and ../README.md).
 */

enum class AppScreen { LIST, EDIT, CONFLICT, SETTINGS }

/** Presentation model for one memo row (not the storage entity). */
data class MemoUiModel(
    val id: String,
    val title: String,
    val preview: String,
    val updatedLabel: String,
    val conflictCount: Int = 0,
)

/** Presentation model for a body conflict (two candidate versions). */
data class ConflictUiModel(
    val memoId: String,
    val localTitle: String,
    val localBody: String,
    val remoteTitle: String,
    val remoteBody: String,
)

// ---------------------------------------------------------------------------
// App navigation shell
// ---------------------------------------------------------------------------

/**
 * SaltUI app shell with minimal state navigation.
 *
 * TODO(Android): replace the demo list and callbacks with a ViewModel + Room Flow, and wire
 * edits through the tested core (HLC next() + MergeEngine + SyncManager.requestSync("save")).
 * TODO(SaltUi): if a navigation stack is wanted, SaltUI offers salt-ui-navigation
 * (https://moriafly.com/program/salt-ui/navigation.html); the POC skeleton keeps state
 * navigation so no extra module is required.
 */
@OptIn(UnstableSaltUiApi::class)
@Composable
fun MemoSyncApp(syncManager: SyncManager) {
    var screen by rememberSaveable { mutableStateOf(AppScreen.LIST) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editTitle by rememberSaveable { mutableStateOf("") }
    var editBody by rememberSaveable { mutableStateOf("") }
    var syncLabel by remember { mutableStateOf("未知") }
    var lastMergeSummary by remember { mutableStateOf("") }
    val conflictCount = remember { mutableIntStateOf(1) }

    // UI-only demo data (TODO(Android): collect from Room).
    val memos = remember {
        mutableStateListOf(
            MemoUiModel("m-demo-1", "购物清单", "牛奶、鸡蛋、面包", "刚刚"),
            MemoUiModel("m-demo-2", "[冲突] 会议记录", "与手表版本存在冲突", "昨天", conflictCount.intValue),
        )
    }

    LaunchedEffect(screen) {
        syncLabel = if (syncManager.isWatchReady()) "手表已连接" else "手表未连接"
        lastMergeSummary = syncManager.lastMergeSummary()
    }

    when (screen) {
        AppScreen.LIST -> MemoListScreen(
            memos = memos,
            syncLabel = syncLabel,
            onOpen = { memo ->
                editingId = memo.id
                editTitle = memo.title
                editBody = memo.preview
                screen = AppScreen.EDIT
            },
            onNew = {
                editingId = "m-new-" + System.currentTimeMillis()
                editTitle = ""
                editBody = ""
                screen = AppScreen.EDIT
            },
            onSync = { syncManager.requestSync("manual") },
            onSettings = { screen = AppScreen.SETTINGS },
        )

        AppScreen.EDIT -> MemoEditScreen(
            title = editTitle,
            body = editBody,
            onTitleChange = { editTitle = it },
            onBodyChange = { editBody = it },
            onBack = { screen = AppScreen.LIST },
            onSave = {
                // TODO(Android): persist via Room (HLC next() per changed field), then
                // syncManager.requestSync("save").
                syncManager.requestSync("save")
                screen = AppScreen.LIST
            },
            onDelete = {
                // TODO(SaltUi): confirmation dialog (SaltUI Dialog / AdaptiveDialog, docs
                // overlays) before marking the tombstone.
                screen = AppScreen.LIST
            },
        )

        AppScreen.CONFLICT -> ConflictScreen(
            conflict = ConflictUiModel(
                memoId = "m-demo-2",
                localTitle = "会议记录（手机版）",
                localBody = "手机侧编辑的内容",
                remoteTitle = "[冲突] 会议记录",
                remoteBody = "手表侧编辑的内容",
            ),
            onBack = { screen = AppScreen.LIST },
            onKeepLocal = {
                // TODO(Android): resolve via conflicts table + pull canonical.
                conflictCount.intValue = 0
                screen = AppScreen.LIST
            },
            onKeepRemote = {
                conflictCount.intValue = 0
                screen = AppScreen.LIST
            },
        )

        AppScreen.SETTINGS -> SettingsScreen(
            watchReady = syncManager.isWatchReady(),
            syncStateLabel = syncManager.state().name,
            lastMergeSummary = lastMergeSummary.ifBlank { "尚未同步" },
            protocolVersion = 1,
            onSync = { syncManager.requestSync("manual") },
            onBack = { screen = AppScreen.LIST },
        )
    }
}

// ---------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------

@OptIn(UnstableSaltUiApi::class)
@Composable
fun MemoListScreen(
    memos: List<MemoUiModel>,
    syncLabel: String,
    onOpen: (MemoUiModel) -> Unit,
    onNew: () -> Unit,
    onSync: () -> Unit,
    onSettings: () -> Unit,
) {
    // Root page: actionButton = null removes the default back button (documented overload).
    BasicScreen(
        actionButton = null,
        title = "备忘录",
        toolButtons = {
            TitleBarButton(
                onClick = onSync,
                icon = {
                    Icon(
                        painter = rememberVectorPainter(SaltIcons.Check),
                        contentDescription = "同步",
                    )
                },
            )
            TitleBarButton(
                onClick = onSettings,
                icon = {
                    Icon(
                        painter = rememberVectorPainter(SaltIcons.ChevronRight),
                        contentDescription = "设置",
                    )
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(contentPadding.calculateTopPadding()))

            ScreenCard(header = "同步状态", footer = "手表同步由华为运动健康通道承载，无需蓝牙权限。") {
                Item(text = "状态：$syncLabel", onClick = onSync)
                Item(text = "立即同步", onClick = onSync)
            }

            ScreenCard(header = "全部备忘录", footer = "共 ${memos.size} 条") {
                if (memos.isEmpty()) {
                    Item(text = "还没有备忘录，点下方按钮新建", onClick = onNew)
                } else {
                    memos.forEach { memo ->
                        Item(
                            text = if (memo.conflictCount > 0) {
                                "${memo.title}（有冲突）"
                            } else {
                                memo.title
                            },
                            onClick = { onOpen(memo) },
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(SaltTheme.dimens.padding)) {
                Button(onClick = onNew, text = "新建备忘录")
            }

            Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
        }
    }
}

// ---------------------------------------------------------------------------
// Edit
// ---------------------------------------------------------------------------

@OptIn(UnstableSaltUiApi::class)
@Composable
fun MemoEditScreen(
    title: String,
    body: String,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    BasicScreen(
        onBack = onBack,
        title = "编辑备忘录",
        toolButtons = {
            TitleBarButton(
                onClick = onSave,
                icon = {
                    Icon(
                        painter = rememberVectorPainter(SaltIcons.Check),
                        contentDescription = "保存",
                    )
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(contentPadding.calculateTopPadding()))

            ScreenCard(header = "标题", footer = "标题建议不超过 64 字节。") {
                SaltTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    placeholder = "请输入标题",
                    minHeight = 48.dp,
                )
            }

            ScreenCard(header = "正文", footer = "正文与手表双向合并；冲突时生成冲突副本。") {
                SaltTextField(
                    value = body,
                    onValueChange = onBodyChange,
                    placeholder = "请输入正文",
                    minHeight = 240.dp,
                )
            }

            Column(modifier = Modifier.padding(SaltTheme.dimens.padding)) {
                Button(onClick = onSave, text = "保存")
                Spacer(Modifier.height(SaltTheme.dimens.padding))
                // TODO(SaltUi): "destructive" button style / confirmation dialog. SaltUI overlay
                // docs: https://moriafly.com/program/salt-ui/components/overlays.html
                Button(onClick = onDelete, text = "删除")
            }

            Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
        }
    }
}

/**
 * Text input styled from SaltTheme. Uses Compose Foundation's BasicTextField (stable API) and
 * pulls colors/text styles from SaltUI so it visually matches the Salt design language.
 *
 * TODO(SaltUi): if SaltUI exposes a first-class text field in the pinned 3.0.0 version, replace
 * this with it; the docs list ItemEdit for single-line settings edits but no general text area.
 */
@Composable
private fun SaltTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minHeight: androidx.compose.ui.unit.Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SaltTheme.dimens.padding),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight),
            textStyle = SaltTheme.textStyles.main.copy(color = SaltTheme.colors.text),
            cursorBrush = SolidColor(SaltTheme.colors.highlight),
        )
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = SaltTheme.textStyles.sub,
                color = SaltTheme.colors.subText,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Conflict
// ---------------------------------------------------------------------------

@OptIn(UnstableSaltUiApi::class)
@Composable
fun ConflictScreen(
    conflict: ConflictUiModel,
    onKeepLocal: (String) -> Unit,
    onKeepRemote: (String) -> Unit,
    onBack: () -> Unit,
) {
    BasicScreen(onBack = onBack, title = "冲突处理") { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(contentPadding.calculateTopPadding()))

            ScreenCard(
                header = "手机版（本地）",
                footer = "保留此版本会覆盖手表侧的同字段内容。",
            ) {
                Item(text = conflict.localTitle, onClick = { onKeepLocal(conflict.memoId) })
                Item(text = conflict.localBody, onClick = { onKeepLocal(conflict.memoId) })
            }

            ScreenCard(
                header = "手表版（远端）",
                footer = "保留此版本会覆盖手机侧的同字段内容。",
            ) {
                Item(text = conflict.remoteTitle, onClick = { onKeepLocal(conflict.memoId) })
                Item(text = conflict.remoteBody, onClick = { onKeepLocal(conflict.memoId) })
            }

            Column(modifier = Modifier.padding(SaltTheme.dimens.padding)) {
                Button(onClick = { onKeepLocal(conflict.memoId) }, text = "保留手机版")
                Spacer(Modifier.height(SaltTheme.dimens.padding))
                Button(onClick = { onKeepRemote(conflict.memoId) }, text = "保留手表版")
            }

            Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
        }
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

@OptIn(UnstableSaltUiApi::class)
@Composable
fun SettingsScreen(
    watchReady: Boolean,
    syncStateLabel: String,
    lastMergeSummary: String,
    protocolVersion: Int,
    onSync: () -> Unit,
    onBack: () -> Unit,
) {
    BasicScreen(onBack = onBack, title = "设置") { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(contentPadding.calculateTopPadding()))

            ScreenCard(header = "手表连接", footer = "链路由华为运动健康维护；应用不申请蓝牙权限。") {
                Item(
                    text = if (watchReady) "手表已连接" else "手表未连接（请打开手表 App）",
                    onClick = onSync,
                )
                Item(text = "立即同步", onClick = onSync)
                Item(text = "同步状态：$syncStateLabel", onClick = onSync)
            }

            ScreenCard(header = "合并", footer = "手机是合并权威；手表只推 dirty、收 canonical。") {
                Item(text = "最近一次合并：$lastMergeSummary", onClick = onSync)
            }

            ScreenCard(header = "关于") {
                Item(text = "协议版本 v$protocolVersion", onClick = {})
                Item(text = "HMS appid：YOUR_HMS_APP_ID", onClick = {})
                Item(text = "数据仅保存在手机/手表本地", onClick = {})
            }

            Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
        }
    }
}
