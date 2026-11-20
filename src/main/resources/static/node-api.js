'use strict';
(() => {
    let token = '';
    // 调用方未指定方法时，这些会改变节点状态的接口默认使用 POST。
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
        const links = [
            {label: '链数据查询', href: '/bitcoin/system', match: path => path === '/bitcoin/system' || path === '/home/index.html'},
            {label: '区块可视化', href: '/block-visualization.html', match: path => path.endsWith('/block-visualization.html')},
            {label: 'P2P 网络', href: '/bitcoin/system/p2p', match: path => path === '/bitcoin/system/p2p' || path.endsWith('/p2p-monitor.html')},
            {label: '同步监控', href: '/sync-monitor.html', match: path => path.endsWith('/sync-monitor.html')},
            {label: '交易池', href: '/txpool-monitor.html', match: path => path.endsWith('/txpool-monitor.html')}
        ];
        const nav = document.createElement('nav');
        nav.className = 'node-navigation';
        nav.setAttribute('aria-label', '节点页面');
        links.forEach(item => {
            const link = Object.assign(document.createElement('a'), {href: item.href, textContent: item.label});
            if (item.match(location.pathname)) link.setAttribute('aria-current', 'page');
            nav.append(link);
        });
        document.body.prepend(nav);

        const form = document.createElement('form');
        form.className = 'node-token-form';
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
            form.dataset.active = token ? 'true' : 'false';
        });
        clear.addEventListener('click', () => { token = ''; input.value = ''; status.textContent = '令牌已清除'; form.dataset.active = 'false'; });
        nav.after(form);
    });
})();
