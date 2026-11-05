/*
 * memo-sync-poc / watch — 列表页（首页）
 *
 * 数据流：Services.init -> repo.listMemos()（只读 index.json 的内存副本）-> 渲染。
 * 导航：list -> detail（Router.replace；Lite Wearable 没有可靠的返回栈，页面自己带“返回”按钮）
 * 同步：保存后由 edit 页触发；列表页提供手动“同步”按钮。
 */

import Router from '@system.router';
import Config from '../../common/memo/config';
import Services from '../../common/memo/services';

function pad2(value) {
    return value < 10 ? '0' + value : '' + value;
}

function formatTime(hlc) {
    if (!hlc || hlc.length < 13) return '';
    var ms = parseInt(hlc.substring(0, 13), 10);
    if (!isFinite(ms) || ms <= 0) return '';
    var date = new Date(ms);
    return pad2(date.getMonth() + 1) + '-' + pad2(date.getDate()) + ' ' +
        pad2(date.getHours()) + ':' + pad2(date.getMinutes());
}

export default {
    data: {
        memos: [],
        status: '加载中…',
        deviceId: ''
    },

    onInit: function () {
        var self = this;
        Services.get().init(function (err) {
            if (err) {
                self.status = '存储初始化失败: ' + err.message;
                return;
            }
            self.deviceId = Services.get().repo.getDeviceId();
            self.refresh();
        });
    },

    onShow: function () {
        if (Services.get().isReady()) {
            this.refresh();
        }
    },

    refresh: function () {
        var repo = Services.get().repo;
        var entries = repo.listMemos();
        var rows = [];
        for (var i = 0; i < entries.length; i++) {
            var entry = entries[i];
            rows.push({
                id: entry.id,
                displayTitle: entry.title ? entry.title : '(无标题)',
                displayPreview: entry.preview ? entry.preview : '(空)',
                metaText: formatTime(entry.updatedHlc) + (entry.dirty ? '  ·  待同步' : '  ·  已同步')
            });
        }
        this.memos = rows;
        this.status = '共 ' + rows.length + ' 条' + (rows.length ? '' : '，点“新建”开始');
    },

    openMemo: function (id) {
        Router.replace({
            uri: Config.PAGES.DETAIL,
            params: { id: id }
        });
    },

    onNew: function () {
        var self = this;
        var repo = Services.get().repo;
        self.status = '新建中…';
        repo.createMemo({ title: '', body: '' }, function (err, record) {
            if (err) {
                self.status = '新建失败: ' + err.message;
                return;
            }
            Router.replace({
                uri: Config.PAGES.EDIT,
                params: { id: record.id }
            });
        });
    },

    onSync: function () {
        var self = this;
        if (!Services.get().isReady()) {
            self.status = '还没初始化完成';
            return;
        }
        var client = Services.get().syncClient();
        if (client.isBusy()) {
            self.status = '同步进行中…';
            return;
        }
        var started = Services.get().requestSync('list_manual');
        self.status = started ? '同步请求已发送' : '同步未启动';
        setTimeout(function () {
            var state = client.getState();
            if (state.phase === 'idle' && state.error) {
                self.status = '同步失败: ' + state.error.message;
            } else if (state.phase === 'idle') {
                self.status = '同步完成，共 ' + Services.get().repo.listMemos().length + ' 条';
                self.refresh();
            } else {
                self.status = '同步中: ' + state.phase;
            }
        }, 1500);
    }
};
