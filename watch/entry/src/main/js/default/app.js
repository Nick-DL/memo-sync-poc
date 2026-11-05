/*
 * memo-sync-poc / watch — App 入口
 *
 * 轻手表（Lite Wearable）JS FA 模型：app.js 的 onCreate 在 App 启动时执行一次。
 * 这里做 App 级初始化：
 *   1) 仓储初始化（device.json / index.json / outbox.json）
 *   2) 注册 FeatureAbility.subscribeMsg（App 级，只注册一次；页面 onDestroy 不要 unsubscribe，否则回列表后收不到手机消息）
 *   3) 注册 HLC 时钟 + SyncClient
 *
 * 注意：手表 App 不在前台时不能可靠后台同步，所以这里不主动 requestSync；
 * 由列表页 onInit / 保存后 / 手动“同步”按钮触发。
 */

import Services from './common/memo/services';

export default {
    onCreate() {
        // 预热：初始化仓储和传输层订阅（不需要等页面）
        Services.get().init(function (err) {
            if (err) {
                console.error('[app] init failed: ' + err.message);
            } else {
                console.info('[app] memo watch POC ready, device=' + Services.get().repo.getDeviceId());
            }
        });
    },
    onDestroy() {
        // 不要 FeatureAbility.unsubscribeMsg()：App 级订阅，避免从详情返回列表后漏消息
        console.info('[app] onDestroy');
    }
};
