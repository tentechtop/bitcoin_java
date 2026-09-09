'use strict';
(() => {
    let token = '';
    const writes = new Set([
        '/chain/startSync', '/chain/sync/start', '/chain/sync/startHeadersFirst', '/chain/startSyncHeadersFirst',
        '/chain/startMining', '/chain/stopMining', '/chain/mingOneBlock', '/chain/mingOneBlockByCount',
        '/chain/broadcastResource', '/chain/broadcastTxPool', '/p2p/connect', '/p2p/disconnect',
        '/peer/queryNode', '/peer/findNode', '/peer/dht/refresh', '/peer/dht/shareHints'
    ]);
    window.NodeApi = Object.freeze({
        escape: value => String(value ?? '').replace(/[&<>"']/g, char => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[char])),
        fetch: async (input, options = {}) => {
            const url = new URL(input, location.href);
            if (url.origin !== location.origin) throw new Error('只允许访问当前节点');
            const headers = new Headers(options.headers);
            if (token) headers.set('Authorization', `Bearer ${token}`);
            return fetch(url, {...options, headers, credentials: 'omit', redirect: 'error',
                method: options.method || (writes.has(url.pathname) ? 'POST' : 'GET')});
        }
    });
    document.addEventListener('DOMContentLoaded', () => {
        const form = document.createElement('form');
        form.style.cssText = 'display:flex;gap:8px;align-items:center;flex-wrap:wrap;padding:10px 18px;background:#f1f5f9;color:#172033;font:14px system-ui;position:relative;z-index:20';
        const label = Object.assign(document.createElement('label'), {textContent: '节点访问令牌 ', htmlFor: 'node-api-token'});
        const input = Object.assign(document.createElement('input'), {id: 'node-api-token', type: 'password', autocomplete: 'off', placeholder: '只保存在当前页面内存中'});
        input.maxLength = 4096;
        const apply = Object.assign(document.createElement('button'), {type: 'submit', textContent: '应用'});
        const clear = Object.assign(document.createElement('button'), {type: 'button', textContent: '清除'});
        const status = Object.assign(document.createElement('span'), {textContent: '未设置令牌；私有节点需要读权限或管理权限'});
        status.setAttribute('role', 'status');
        form.append(label, input, apply, clear, status);
        form.addEventListener('submit', event => {
            event.preventDefault(); token = input.value.trim(); input.value = '';
            status.textContent = token ? '令牌已应用；刷新或关闭页面后清除' : '令牌已清除';
        });
        clear.addEventListener('click', () => { token = ''; input.value = ''; status.textContent = '令牌已清除'; });
        document.body.prepend(form);
    });
})();
