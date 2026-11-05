/*
 * memo-sync-poc / watch — Wear Engine P2P 传输适配（骨架）
 *
 * 真实链路（见 daysmatter-analysis/倒数日-手机与鸿蒙轻手表通信分析.md §4）：
 *   - 手表侧 FeatureAbility 是 Lite Wearable 运行时注入的全局对象（不是 import）；
 *   - system.wearengine 负责 setPackageName / setFingerprint / 版本查询；
 *   - FeatureAbility.sendMsg({deviceId:'remote', bundleName:手机包名, abilityName:'', message})；
 *   - FeatureAbility.subscribeMsg 回调 data.message 是字符串 JSON；
 *   - FeatureAbility.detect 判断手机 App 是否在运行。
 *
 * 本文件只做“字节 <-> JSON”和生命周期封装，不含业务；SyncClient 依赖的接口：
 *   transport.detect(cb(ok))
 *   transport.send(obj, cb(err))
 *   transport.onMessage(handler)
 *   transport.subscribe()      // App 启动时调用一次
 *
 * 注意：Node 测试不加载本文件（它 import @system.wearengine），测试用 test/fakes.mjs。
 */

import WearEngine from '@system.wearengine';
import Config from './config';

var subscribed = false;
var handlers = [];

function log(message) {
    if (typeof console !== 'undefined' && console.info) {
        console.info('[transport] ' + message);
    }
}

function createWearTransport() {
    return {
        // 手机端 App 是否在运行（fail = 没装/没开）
        detect: function (callback) {
            if (typeof FeatureAbility === 'undefined' || !FeatureAbility.detect) {
                callback(false);
                return;
            }
            FeatureAbility.detect({
                bundleName: Config.PEER.PHONE_PKG,
                success: function () {
                    callback(true);
                },
                fail: function () {
                    callback(false);
                }
            });
        },

        // 发送一条 JSON 消息给手机 App
        send: function (message, callback) {
            var payload;
            try {
                payload = JSON.stringify(message);
            } catch (error) {
                if (callback) callback(error);
                return;
            }
            if (typeof FeatureAbility === 'undefined' || !FeatureAbility.sendMsg) {
                if (callback) callback(new Error('FeatureAbility 不可用（不是 Lite Wearable 运行时）'));
                return;
            }
            FeatureAbility.sendMsg({
                deviceId: 'remote',
                bundleName: Config.PEER.PHONE_PKG,
                abilityName: '',
                message: payload,
                success: function () {
                    if (callback) callback(null);
                },
                fail: function (data, code) {
                    if (callback) callback(new Error('sendMsg failed, code=' + code));
                }
            });
        },

        onMessage: function (handler) {
            handlers.push(handler);
        },

        // App 级注册一次；不要在页面 onDestroy 里取消，否则回列表后收不到手机消息
        subscribe: function () {
            if (subscribed) return;
            subscribed = true;

            if (typeof WearEngine !== 'undefined' && WearEngine.setPackageName) {
                try {
                    // 版本门槛约 302/303：低版本只记录日志，真实联调时按文档降级
                    WearEngine.setPackageName({ appName: Config.PEER.PHONE_PKG });
                    if (WearEngine.setFingerprint) {
                        WearEngine.setFingerprint({
                            appName: Config.PEER.PHONE_PKG,
                            appCert: Config.PEER.PHONE_CERT
                        });
                    }
                } catch (error) {
                    log('setPackageName/setFingerprint failed: ' + error);
                }
            }

            if (typeof FeatureAbility === 'undefined' || !FeatureAbility.subscribeMsg) {
                log('FeatureAbility.subscribeMsg 不可用');
                return;
            }
            FeatureAbility.subscribeMsg({
                success: function (data) {
                    if (!data) return;
                    if (data.isRegister) {
                        log('subscribeMsg registered');
                        return;
                    }
                    if (data.isFileType) return; // 本 App 不用文件传输
                    var message = null;
                    try {
                        message = JSON.parse(data.message);
                    } catch (error) {
                        log('drop non-json message');
                        return;
                    }
                    for (var i = 0; i < handlers.length; i++) {
                        handlers[i](message);
                    }
                },
                fail: function () {
                    log('subscribeMsg failed');
                }
            });
        }
    };
}

export default {
    create: createWearTransport
};
