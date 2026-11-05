/*
 * memo-sync-poc / watch — 编辑页（crabKeyboard 接入的关键页）
 *
 * 背景（设计文档 §6.3/§6.4）:
 *   - crabKeyboard 是独立页面，通过 Router.replace 打开；完成后 Router.replace 回 targetPage，
 *     并且【只回传 targetKey 一个字段】，其他参数会丢；
 *   - 取消和“清空后完成”都回传空串，无法区分；
 *   - 所以这里：
 *       1) 打开键盘前把 {memoId, field, original, maxLen} 写 @system.storage 'editCtx'；
 *       2) 给键盘 patch 了 editAction: 'done' | 'cancel'（见 pages/keyboard_rect/keyboard.js）；
 *       3) 返回后 onInit 先读 'editCtx'，再根据 draftText/editAction 决定保存还是丢弃。
 *
 * data 里必须声明 id / draftText / editAction —— Lite Wearable 只注入 data 中声明过的路由参数。
 */

import Router from '@system.router';
import Storage from '@system.storage';
import Config from '../../common/memo/config';
import Text from '../../common/memo/text';
import Services from '../../common/memo/services';

var EDIT_CTX_KEY = 'editCtx';

function fieldLabel(field) {
    return field === 'title' ? '标题' : '正文';
}

/*
 * @system.storage.get 的 success 回调在 Lite Wearable / 倒数日真机上直接返回「值」本身（字符串），
 * 不是 { value: '...' } 包装；这里做一个兼容读取，避免真机上 editCtx 上下文被丢掉。
 * （Node 侧没有 storage，只在静态检查里保证这个 helper 存在。）
 */
function readStoredValue(data) {
    if (data && typeof data === 'object' && data.value !== undefined) return data.value;
    return data;
}

export default {
    data: {
        // 路由参数（返回键盘时由键盘 Router.replace 注入 draftText/editAction）
        id: null,
        draftText: null,
        editAction: null,

        displayTitle: '(空标题)',
        displayBody: '(空正文)',
        titleHint: '',
        bodyHint: '',
        status: ''
    },

    onInit: function () {
        var self = this;
        Services.get().init(function (err) {
            if (err) {
                self.status = '存储初始化失败: ' + err.message;
                return;
            }
            Storage.get({
                key: EDIT_CTX_KEY,
                success: function (data) {
                    var raw = readStoredValue(data);
                    var context = null;
                    if (raw) {
                        try {
                            context = JSON.parse(raw);
                        } catch (parseError) {
                            context = null;
                        }
                    }
                    if (context && context.memoId) {
                        self.consumeKeyboardResult(context);
                    } else {
                        self.loadMemo();
                    }
                },
                fail: function () {
                    self.loadMemo();
                }
            });
        });
    },

    // 从键盘返回：draftText / editAction 由键盘 Router.replace 注入
    consumeKeyboardResult: function (context) {
        var self = this;
        var repo = Services.get().repo;
        var hasDraft = (this.draftText !== null && this.draftText !== undefined);

        if (!hasDraft) {
            // 理论上不会发生；容错：清上下文后按普通编辑处理
            self.clearContext();
            self.loadMemo();
            return;
        }
        if (this.editAction === 'cancel') {
            self.clearContext();
            self.status = '已取消编辑';
            self.goDetail(context.memoId);
            return;
        }
        repo.saveField(context.memoId, context.field, this.draftText, function (err) {
            self.clearContext();
            if (err) {
                self.status = '保存失败: ' + err.message;
                return;
            }
            Services.get().requestSync('save'); // 保存后尝试同步（手机没开时静默失败，下次打开再试）
            self.status = fieldLabel(context.field) + '已保存';
            self.goDetail(context.memoId);
        });
    },

    loadMemo: function () {
        var self = this;
        var repo = Services.get().repo;
        if (!this.id) {
            this.status = '缺少 memo id';
            return;
        }
        repo.getMemo(this.id, function (err, record) {
            if (err) {
                self.status = '读取失败: ' + err.message;
                return;
            }
            if (!record) {
                self.status = '记录不存在';
                return;
            }
            self.displayTitle = record.title ? record.title : '(空标题)';
            self.displayBody = record.body ? record.body : '(空正文)';
            self.titleHint = '字节 ' + Text.utf8Bytes(record.title) + '/' + Config.LIMIT.TITLE_BYTES + '（标题上限 64 字节）';
            self.bodyHint = '字节 ' + Text.utf8Bytes(record.body) + '/' + Config.LIMIT.BODY_BYTES + '（正文上限 6144 字节）';
            self.status = '点下方按钮打开 crabKeyboard';
        });
    },

    editTitle: function () {
        this.openKeyboard('title');
    },

    editBody: function () {
        this.openKeyboard('body');
    },

    // 关键流程：写上下文 -> Router.replace 打开键盘页
    openKeyboard: function (field) {
        var self = this;
        if (!this.id) {
            this.status = '缺少 memo id';
            return;
        }
        var repo = Services.get().repo;
        var isTitle = field === 'title';
        var maxLen = isTitle ? Config.LIMIT.TITLE_BYTES : Config.LIMIT.BODY_BYTES;
        var original = isTitle ? this.displayTitle : this.displayBody;
        var context = {
            memoId: this.id,
            field: field,
            original: original,
            maxLen: maxLen,
            savedAt: Date.now()
        };
        this.status = '打开键盘…';

        // 设计文档 §3.4 的 draft.json：键盘页面/host 被意外销毁后可恢复；POC 里仅做保存
        repo.saveDraft(context, function () {});

        Storage.set({
            key: EDIT_CTX_KEY,
            value: JSON.stringify(context),
            success: function () {
                Router.replace({
                    uri: Config.KEYBOARD_PAGE, // Fit 3: /pages/keyboard_rect/keyboard
                    params: {
                        targetPage: Config.PAGES.EDIT,
                        backPage: Config.PAGES.EDIT,
                        targetKey: Config.KEYBOARD_TARGET_KEY, // 'draftText'
                        prompt: isTitle ? '请输入标题' : '请输入正文',
                        maxLen: maxLen,
                        forbidden: '',
                        allowNewline: !isTitle,
                        keyType: 0
                    }
                });
            },
            fail: function () {
                self.status = 'storage.set 失败，无法打开键盘';
            }
        });
    },

    clearContext: function () {
        Storage.delete({ key: EDIT_CTX_KEY, success: function () {}, fail: function () {} });
        Services.get().repo.clearDraft(function () {});
    },

    onBack: function () {
        var self = this;
        self.clearContext();
        if (!this.id) {
            Router.replace({ uri: Config.PAGES.LIST });
            return;
        }
        Services.get().repo.getMemo(this.id, function (err, record) {
            var empty = !err && record && !record.deleted &&
                (!record.title || record.title.length === 0) &&
                (!record.body || record.body.length === 0);
            if (empty) {
                // 新建后没输入任何内容：按设计文档删掉这条空草稿
                Services.get().repo.deleteMemo(self.id, function () {
                    Router.replace({ uri: Config.PAGES.LIST });
                });
                return;
            }
            self.goDetail(self.id);
        });
    },

    goDetail: function (id) {
        Router.replace({
            uri: Config.PAGES.DETAIL,
            params: { id: id }
        });
    }
};
