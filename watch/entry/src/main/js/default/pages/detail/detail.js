/*
 * memo-sync-poc / watch — 详情页
 *
 * onInit 从路由参数拿 id（data 里必须声明 id，Lite Wearable 才会注入），
 * 然后 repo.getMemo(id) 按需读 memo_<id>.json（列表页只读 index.json）。
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

function splitParagraphs(body) {
    if (!body) return [];
    var lines = String(body).split('\n');
    var paragraphs = [];
    for (var i = 0; i < lines.length; i++) {
        var line = lines[i].replace(/\r/g, '');
        paragraphs.push(line.length ? line : ' ');
    }
    while (paragraphs.length && paragraphs[paragraphs.length - 1] === ' ') {
        paragraphs.pop();
    }
    return paragraphs;
}

export default {
    data: {
        id: null,          // 路由参数
        displayTitle: '',
        metaText: '',
        paragraphs: [],
        status: ''
    },

    onInit: function () {
        var self = this;
        Services.get().init(function (err) {
            if (err) {
                self.status = '存储初始化失败: ' + err.message;
                return;
            }
            self.load();
        });
    },

    load: function () {
        var self = this;
        if (!this.id) {
            this.status = '缺少 memo id';
            return;
        }
        Services.get().repo.getMemo(this.id, function (err, record) {
            if (err) {
                self.status = '读取失败: ' + err.message;
                return;
            }
            if (!record) {
                self.status = '记录不存在（可能已在手机端删除）';
                return;
            }
            self.displayTitle = record.title ? record.title : '(无标题)';
            self.metaText = formatTime(record.updatedHlc) + (record.deleted ? '  ·  已删除' : '');
            self.paragraphs = splitParagraphs(record.body);
            self.status = record.deleted ? '该记录已删除' : '回车返回列表';
        });
    },

    onEdit: function () {
        Router.replace({
            uri: Config.PAGES.EDIT,
            params: { id: this.id }
        });
    },

    onBack: function () {
        Router.replace({ uri: Config.PAGES.LIST });
    },

    onDelete: function () {
        var self = this;
        Services.get().repo.deleteMemo(this.id, function (err) {
            if (err) {
                self.status = '删除失败: ' + err.message;
                return;
            }
            Router.replace({ uri: Config.PAGES.LIST });
        });
    }
};
