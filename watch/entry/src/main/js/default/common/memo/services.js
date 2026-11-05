/*
 * memo-sync-poc / watch — 设备侧服务装配（pages 共用）
 *
 * 把 repo(HLC clock 注入) + transport(FeatureAbility) + SyncClient 组装成单例：
 *   Services.get().init(function (err) { ... })   // 页面 onInit 用；幂等，可并发调用
 *   Services.get().isReady()
 *   Services.get().repo                          // 仓储
 *   Services.get().clock                         // HLC 时钟
 *   Services.get().syncClient()                  // 同步客户端
 *   Services.get().requestSync('list')           // 触发一次同步请求
 *
 * 本文件 import 页面/设备相关模块（file-storage/transport），只在手表运行时加载。
 */

import Config from './config';
import Hlc from './hlc';
import Repo from './repo';
import Sync from './sync';
import FileStorage from './file-storage';
import WearTransport from './transport';

var singleton = null;

function createServices() {
    var storage = FileStorage.create();
    var repo = Repo.create({ storage: storage });
    var transport = WearTransport.create();
    var clock = null;
    var client = null;
    var ready = false;
    var initializing = false;
    var callbacks = [];
    var lastError = null;

    function complete(error) {
        ready = !error;
        lastError = error || null;
        var pending = callbacks;
        callbacks = [];
        for (var i = 0; i < pending.length; i++) {
            pending[i](lastError);
        }
    }

    function ensureClient() {
        if (client) return client;
        client = Sync.createClient({
            repo: repo,
            transport: transport,
            deviceId: repo.getDeviceId(),
            onState: function (state) {
                if (typeof console !== 'undefined' && console.info) {
                    console.info('[sync] ' + state.phase + (state.reason ? ' (' + state.reason + ')' : ''));
                }
            },
            onError: function (error) {
                if (typeof console !== 'undefined' && console.warn) {
                    console.warn('[sync] error ' + error.code + ': ' + error.message);
                }
            }
        });
        return client;
    }

    return {
        repo: repo,
        transport: transport,

        init: function (callback) {
            if (ready) {
                callback(null);
                return;
            }
            callbacks.push(callback);
            if (initializing) return;
            initializing = true;

            repo.init(function (error) {
                if (!error) {
                    clock = Hlc.createClock({
                        deviceId: repo.getDeviceId(),
                        now: function () { return Date.now(); }
                    });
                    repo.setClock(clock);
                    ensureClient();
                    transport.subscribe(); // App 级收消息，只注册一次
                }
                initializing = false;
                complete(error || null);
            });
        },

        isReady: function () {
            return ready;
        },

        clock: function () {
            return clock;
        },

        syncClient: function () {
            return ensureClient();
        },

        requestSync: function (reason) {
            if (!ready) return false;
            return ensureClient().request(reason || 'app');
        }
    };
}

export default {
    get: function () {
        if (!singleton) singleton = createServices();
        return singleton;
    }
};
