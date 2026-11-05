/*
 * memo-sync-poc / watch — @system.file -> repo storage 适配
 *
 * repo.js 只依赖一个可注入的 storage 接口：
 *   readText(uri, cb(err, text|null))
 *   writeText(uri, text, cb(err))
 *   deleteFile(uri, cb(err))    // 可选
 *
 * 这里把它映射到轻手表的 @system.file。文件读不到（不存在）时返回 null 而不是错误，
 * 让 repo 把“文件不存在”当作空状态处理；真正的 IO 错误（权限/磁盘）也按空处理并在 fail 回调里忽略，
 * POC 阶段优先保证页面不崩（正式版应区分错误码）。
 *
 * 注意：本文件 import @system.*，只能在手表运行时加载，Node 测试请用 test/fakes.mjs。
 */

import File from '@system.file';

function createFileStorage() {
    return {
        readText: function (uri, callback) {
            File.readText({
                uri: uri,
                success: function (data) {
                    callback(null, (data && data.text) ? data.text : null);
                },
                fail: function () {
                    callback(null, null);
                }
            });
        },

        writeText: function (uri, text, callback) {
            File.writeText({
                uri: uri,
                text: text,
                success: function () {
                    if (callback) callback(null);
                },
                fail: function (data, code) {
                    if (callback) callback(new Error('writeText failed: ' + uri + ' code=' + code));
                }
            });
        },

        deleteFile: function (uri, callback) {
            File.delete({
                uri: uri,
                success: function () {
                    if (callback) callback(null);
                },
                fail: function () {
                    // 文件本来就不存在：按成功处理
                    if (callback) callback(null);
                }
            });
        }
    };
}

export default {
    create: createFileStorage
};
