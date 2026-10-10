// Minimal demo client for the Digital Wallet APIs. No framework, no build step.
(() => {
    'use strict';

    const WALLET_API = '';  // this page is served by wallet-service itself
    const REWARDS_API = `${location.protocol}//${location.hostname}:8082`;  // cross-origin: needs CORS
    const VIEWS = ['home', 'add', 'send', 'qr', 'rewards', 'admin'];

    // sessionStorage is per tab, so two tabs can be logged in as two different users
    const session = {
        get token() { return sessionStorage.getItem('token'); },
        get user() { return JSON.parse(sessionStorage.getItem('user') || 'null'); },
        save(auth) {
            sessionStorage.setItem('token', auth.accessToken);
            sessionStorage.setItem('user', JSON.stringify({ email: auth.email, role: auth.role, userId: auth.userId }));
        },
        clear() { sessionStorage.removeItem('token'); sessionStorage.removeItem('user'); }
    };

    const $ = (id) => document.getElementById(id);
    const money = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' });
    const dateTime = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
    const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g,
        (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const newIdempotencyKey = () => (crypto.randomUUID ? crypto.randomUUID()
        : 'k' + Date.now().toString(36) + Math.random().toString(36).slice(2, 12));
    const initial = (text) => (text || '?').trim().charAt(0).toUpperCase();

    // ------------------------------------------------------------------ API helper

    class ApiError extends Error {
        constructor(message, status, code) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    async function api(base, path, { method = 'GET', body, idempotent = false } = {}) {
        const headers = { 'Content-Type': 'application/json' };
        if (session.token) headers.Authorization = `Bearer ${session.token}`;
        // one key per user action: a retried request is replayed by the server, never charged twice
        if (idempotent) headers['Idempotency-Key'] = newIdempotencyKey();

        let response;
        try {
            response = await fetch(base + path, { method, headers, body: body ? JSON.stringify(body) : undefined });
        } catch {
            throw new ApiError(base === REWARDS_API ? 'Rewards service is not reachable' : 'Wallet service is not reachable');
        }
        if (response.status === 401 && session.token) {
            showAuth('login');
            throw new ApiError('Your session has ended, please log in again', 401);
        }
        const text = await response.text();
        const data = text ? JSON.parse(text) : null;
        if (!response.ok) {
            const fieldErrors = data && data.errors
                ? Object.entries(data.errors).map(([field, msg]) => `${field} ${msg}`).join(', ') : null;
            throw new ApiError(fieldErrors || (data && data.detail) || response.statusText, response.status, data && data.code);
        }
        return data;
    }

    // ------------------------------------------------------------------ UI helpers

    let toastTimer;
    function toast(message, kind = 'info') {
        const el = $('toast');
        el.textContent = message;
        el.className = `toast ${kind}`;
        el.hidden = false;
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => { el.hidden = true; }, 4500);
    }

    /** Runs an async action with the button disabled and errors shown as a toast. */
    async function busy(button, action) {
        if (button) button.disabled = true;
        try {
            await action();
        } catch (e) {
            toast(e.message, 'error');
        } finally {
            if (button) button.disabled = false;
        }
    }

    const formData = (form) => Object.fromEntries(new FormData(form).entries());

    // ------------------------------------------------------------------ login, register, forgot password

    const AUTH_FORMS = { login: 'loginForm', register: 'registerForm', forgot: 'forgotForm', reset: 'resetForm' };

    function showAuthPane(name) {
        Object.entries(AUTH_FORMS).forEach(([pane, id]) => { $(id).hidden = pane !== name; });
        $('authTabs').hidden = !(name === 'login' || name === 'register');
        document.querySelectorAll('#authTabs .tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === name));
    }

    document.querySelectorAll('#authTabs .tab').forEach((tab) =>
        tab.addEventListener('click', () => showAuthPane(tab.dataset.tab)));
    document.querySelectorAll('[data-back]').forEach((link) => link.addEventListener('click', (e) => {
        e.preventDefault();
        showAuthPane('login');
    }));
    $('forgotLink').addEventListener('click', (e) => {
        e.preventDefault();
        $('forgotForm').email.value = $('loginForm').email.value;
        showAuthPane('forgot');
    });

    $('loginForm').addEventListener('submit', (e) => {
        e.preventDefault();
        busy(e.submitter, async () => {
            session.save(await api(WALLET_API, '/api/auth/login', { method: 'POST', body: formData(e.target) }));
            e.target.reset();
            showApp();
        });
    });

    $('registerForm').addEventListener('submit', (e) => {
        e.preventDefault();
        busy(e.submitter, async () => {
            try {
                session.save(await api(WALLET_API, '/api/auth/register', { method: 'POST', body: formData(e.target) }));
            } catch (err) {
                if (err.code === 'EMAIL_ALREADY_REGISTERED') {
                    // one account per email: send the user to log in instead
                    $('loginForm').email.value = e.target.email.value;
                    showAuthPane('login');
                    $('loginForm').password.focus();
                    toast('You already have an account with this email. Please log in.', 'error');
                    return;
                }
                throw err;
            }
            e.target.reset();
            toast('Account created, your wallet is ready', 'success');
            showApp();
        });
    });

    $('forgotForm').addEventListener('submit', (e) => {
        e.preventDefault();
        busy(e.submitter, async () => {
            const { email } = formData(e.target);
            const result = await api(WALLET_API, '/api/auth/password/forgot', { method: 'POST', body: { email } });
            $('resetForm').reset();
            $('resetForm').email.value = email;
            $('resetEmailLabel').textContent = email;
            showAuthPane('reset');
            toast(result.message, 'success');
        });
    });

    $('resetForm').addEventListener('submit', (e) => {
        e.preventDefault();
        busy(e.submitter, async () => {
            const data = formData(e.target);
            const result = await api(WALLET_API, '/api/auth/password/reset', { method: 'POST', body: data });
            $('loginForm').email.value = data.email;
            e.target.reset();
            showAuthPane('login');
            toast(result.message, 'success');
        });
    });

    $('logoutBtn').addEventListener('click', async () => {
        try {
            await api(WALLET_API, '/api/auth/logout', { method: 'POST' });
        } catch { /* the token is discarded either way */ }
        showAuth('login');
    });

    function showAuth(pane) {
        session.clear();
        stopCamera();
        $('appView').hidden = true;
        $('authView').hidden = false;
        showAuthPane(pane);
    }

    function showApp() {
        const user = session.user;
        $('authView').hidden = true;
        $('appView').hidden = false;
        $('userEmail').textContent = user.email;
        $('userAvatar').textContent = initial(user.email);
        $('roleBadge').textContent = user.role;
        $('adminNav').hidden = user.role !== 'ADMIN';
        renderRoute();
        refreshAll();
    }

    // ------------------------------------------------------------------ navigation (#home, #send, ...)

    function currentView() {
        const view = location.hash.slice(1);
        if (view === 'admin' && !(session.user && session.user.role === 'ADMIN')) return 'home';
        return VIEWS.includes(view) ? view : 'home';
    }

    function renderRoute() {
        const view = currentView();
        document.querySelectorAll('.view').forEach((v) => { v.hidden = v.dataset.view !== view; });
        document.querySelectorAll('#nav a').forEach((a) => a.classList.toggle('active', a.dataset.view === view));
        if (view !== 'qr') stopCamera();
        window.scrollTo(0, 0);
    }

    function navigate(view) {
        if (location.hash === `#${view}`) renderRoute();
        else location.hash = view;
    }

    window.addEventListener('hashchange', () => { if (session.token) renderRoute(); });

    document.querySelectorAll('[data-qr-tab]').forEach((link) =>
        link.addEventListener('click', () => showQrTab(link.dataset.qrTab)));

    // ------------------------------------------------------------------ data

    async function refreshAll() {
        const tasks = [loadWallet(), loadActivity(), loadRewards(), loadLeaderboard()];
        if (session.user && session.user.role === 'ADMIN') tasks.push(loadRules());
        const results = await Promise.allSettled(tasks);
        const failed = results.find((r) => r.status === 'rejected');
        if (failed && session.token) toast(failed.reason.message, 'error');
    }

    /** Cashback is granted asynchronously over Kafka, so look again a little later. */
    function refreshSoon() {
        refreshAll();
        setTimeout(refreshAll, 2000);
        setTimeout(refreshAll, 5000);
    }

    async function loadWallet() {
        const wallet = await api(WALLET_API, '/api/wallets/me');
        $('balance').textContent = money.format(wallet.balance);
    }

    async function loadActivity() {
        const page = await api(WALLET_API, '/api/wallets/me/statement?size=15');
        $('activityCount').textContent = page.totalElements ? `${page.totalElements} total` : '';
        $('activityList').innerHTML = page.content.length === 0
            ? '<li class="empty-state">No activity yet. Add money to get started.</li>'
            : page.content.map((e) => {
                const credit = e.direction === 'CREDIT';
                const kind = e.type === 'REWARD' ? 'reward' : credit ? 'credit' : 'debit';
                const symbol = e.type === 'REWARD' ? '★' : credit ? '↓' : '↑';
                const label = { TOP_UP: 'Top-up', TRANSFER: credit ? 'Received' : 'Sent', REWARD: 'Cashback' }[e.type] || e.type;
                return `<li>
                    <span class="icon ${kind}">${symbol}</span>
                    <div class="what">
                        <div class="title">${escapeHtml(e.description)}</div>
                        <div class="muted">${label} · ${escapeHtml(dateTime.format(new Date(e.createdAt)))}</div>
                    </div>
                    <div class="money">
                        <div class="amount ${credit ? 'credit' : 'debit'}">${credit ? '+' : '−'}${money.format(e.amount)}</div>
                        <div class="muted">Bal ${money.format(e.balanceAfter)}</div>
                    </div>
                </li>`;
            }).join('');
    }

    async function loadRewards() {
        const [summary, page] = await Promise.all([
            api(REWARDS_API, '/api/rewards/me/summary'),
            api(REWARDS_API, '/api/rewards/me?size=10')
        ]);
        $('cashbackTotal').textContent = money.format(summary.totalCashback);
        $('rewardsTotal').textContent = money.format(summary.totalCashback);
        $('rewardsCount').textContent = summary.rewardCount;
        $('rewardsList').innerHTML = page.content.length === 0
            ? '<li class="empty-state">No cashback yet. Send ₹500 or more to earn 2%.</li>'
            : page.content.map((r) => `<li>
                <div><strong>${escapeHtml(r.ruleCode.replaceAll('_', ' '))}</strong>
                    <div class="muted">on ${money.format(r.transactionAmount)} · ${escapeHtml(dateTime.format(new Date(r.createdAt)))}</div></div>
                <span class="amount credit">+${money.format(r.rewardAmount)}</span>
            </li>`).join('');
    }

    async function loadLeaderboard() {
        const board = await api(REWARDS_API, '/api/rewards/leaderboard?limit=5');
        const me = session.user && session.user.userId;
        $('leaderboardList').innerHTML = board.entries.length === 0
            ? '<li class="empty-state">No cashback earned this month yet.</li>'
            : board.entries.map((e) => `<li>
                <span class="rank">${e.rank}</span>
                <span class="who ${e.userId === me ? 'me' : ''}">${e.userId === me ? 'You' : `User #${e.userId}`}</span>
                <strong>${money.format(e.totalCashback)}</strong>
            </li>`).join('');
    }

    async function loadRules() {
        const rules = await api(REWARDS_API, '/api/rules');
        $('rulesBody').innerHTML = rules.map((r) => `<tr>
            <td><strong>${escapeHtml(r.code)}</strong><div class="muted">${escapeHtml(r.name)}</div></td>
            <td>${escapeHtml(r.transactionType.replace('_', ' '))}</td>
            <td class="num">${money.format(r.minAmount)}</td>
            <td class="num">${r.cashbackPercent}%</td>
            <td class="num">${money.format(r.maxReward)}</td>
            <td class="num">${r.dailyLimitPerUser ?? '∞'}</td>
            <td><button class="btn ghost small" data-rule="${r.id}" data-active="${r.active}" type="button">
                <span class="${r.active ? 'status-on' : 'status-off'}">●</span> ${r.active ? 'Active · turn off' : 'Off · turn on'}</button></td>
        </tr>`).join('');
    }

    $('rulesBody').addEventListener('click', (e) => {
        const button = e.target.closest('button[data-rule]');
        if (!button) return;
        busy(button, async () => {
            const active = button.dataset.active !== 'true';
            await api(REWARDS_API, `/api/rules/${button.dataset.rule}/status`, { method: 'PATCH', body: { active } });
            toast(`Rule ${active ? 'activated' : 'deactivated'}`, 'success');
            await loadRules();
        });
    });

    $('refreshBtn').addEventListener('click', refreshAll);

    // ------------------------------------------------------------------ add & send money

    document.querySelectorAll('.chip-btn[data-amount]').forEach((chip) => chip.addEventListener('click', () => {
        $('topUpForm').amount.value = chip.dataset.amount;
    }));

    $('topUpForm').addEventListener('submit', (e) => {
        e.preventDefault();
        busy(e.submitter, async () => {
            const { amount } = formData(e.target);
            await api(WALLET_API, '/api/wallets/me/top-ups', { method: 'POST', body: { amount: Number(amount) }, idempotent: true });
            e.target.reset();
            toast(`${money.format(amount)} added to your wallet`, 'success');
            navigate('home');
            refreshSoon();
        });
    });

    $('transferForm').addEventListener('submit', (e) => {
        e.preventDefault();
        busy(e.submitter, async () => {
            const data = formData(e.target);
            await api(WALLET_API, '/api/wallets/me/transfers', {
                method: 'POST', idempotent: true,
                body: { recipientEmail: data.recipientEmail, amount: Number(data.amount), note: data.note || null }
            });
            e.target.reset();
            toast(`Sent ${money.format(data.amount)} to ${data.recipientEmail}`, 'success');
            navigate('home');
            refreshSoon();
        });
    });

    // ------------------------------------------------------------------ QR: receive

    function showQrTab(tab) {
        document.querySelectorAll('.qr-tab').forEach((t) => t.classList.toggle('active', t.dataset.qr === tab));
        $('qrReceivePanel').hidden = tab !== 'receive';
        $('qrPayPanel').hidden = tab !== 'pay';
        if (tab !== 'pay') stopCamera();
    }

    document.querySelectorAll('.qr-tab').forEach((t) => t.addEventListener('click', () => showQrTab(t.dataset.qr)));

    $('qrCreateForm').addEventListener('submit', (e) => {
        e.preventDefault();
        busy(e.submitter, async () => {
            const data = formData(e.target);
            const qr = await api(WALLET_API, '/api/qr', {
                method: 'POST',
                body: {
                    amount: data.amount ? Number(data.amount) : null,
                    singleUse: data.singleUse === 'on',
                    validityMinutes: Number(data.validityMinutes)
                }
            });
            const src = `data:image/png;base64,${qr.pngBase64}`;
            $('qrImage').src = src;
            $('qrDownload').href = src;
            $('qrCopy').dataset.payload = qr.payload;
            $('qrInfo').textContent = `${qr.amount ? money.format(qr.amount) : 'Any amount'} · `
                + `${qr.singleUse ? 'single use' : 'reusable'} · expires ${dateTime.format(new Date(qr.expiresAt))}`;
            $('qrResult').hidden = false;
        });
    });

    $('qrCopy').addEventListener('click', async (e) => {
        try {
            await navigator.clipboard.writeText(e.target.dataset.payload);
            toast('QR code text copied', 'success');
        } catch {
            toast('Copy failed: download the image instead', 'error');
        }
    });

    // ------------------------------------------------------------------ QR: scan & pay

    let scannedQr = null;

    function decodeQr(imageData) {
        const result = window.jsQR(imageData.data, imageData.width, imageData.height);
        return result ? result.data : null;
    }

    $('qrFile').addEventListener('change', (e) => {
        const file = e.target.files[0];
        e.target.value = '';
        if (!file) return;
        const image = new Image();
        image.onload = () => {
            const canvas = document.createElement('canvas');
            canvas.width = image.naturalWidth;
            canvas.height = image.naturalHeight;
            const ctx = canvas.getContext('2d');
            ctx.drawImage(image, 0, 0);
            URL.revokeObjectURL(image.src);
            const payload = decodeQr(ctx.getImageData(0, 0, canvas.width, canvas.height));
            if (!payload) {
                toast('No QR code found in that image', 'error');
                return;
            }
            $('qrPayload').value = payload;
            checkQr();
        };
        image.onerror = () => toast('That file is not an image', 'error');
        image.src = URL.createObjectURL(file);
    });

    // Webcam scanning: read video frames and look for a QR code with jsQR (all in the browser).
    // Browsers allow the camera only on https or localhost, and only after the user agrees.
    let cameraStream = null;

    $('cameraBtn').addEventListener('click', () => (cameraStream ? stopCamera() : startCamera()));

    async function startCamera() {
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
            toast('This browser cannot use the camera here. Upload the QR image instead.', 'error');
            return;
        }
        try {
            cameraStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false });
        } catch (err) {
            cameraStream = null;
            toast(err.name === 'NotAllowedError' ? 'Camera permission was denied' : 'No camera found. Upload the QR image instead.', 'error');
            return;
        }
        const video = $('camera');
        video.srcObject = cameraStream;
        $('cameraBox').hidden = false;
        $('cameraLabel').textContent = 'Stop camera';
        $('qrPreview').hidden = true;
        await video.play();
        scanFrames(video);
    }

    function scanFrames(video) {
        const canvas = document.createElement('canvas');
        const ctx = canvas.getContext('2d', { willReadFrequently: true });
        const tick = () => {
            if (!cameraStream) return;
            if (video.readyState >= video.HAVE_CURRENT_DATA && video.videoWidth) {
                const scale = Math.min(1, 640 / video.videoWidth);   // smaller frames decode faster
                canvas.width = Math.round(video.videoWidth * scale);
                canvas.height = Math.round(video.videoHeight * scale);
                ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
                const payload = decodeQr(ctx.getImageData(0, 0, canvas.width, canvas.height));
                if (payload) {
                    stopCamera();
                    $('qrPayload').value = payload;
                    checkQr();
                    return;
                }
            }
            setTimeout(tick, 150);   // about 6 checks per second is plenty
        };
        tick();
    }

    function stopCamera() {
        if (cameraStream) cameraStream.getTracks().forEach((track) => track.stop());
        cameraStream = null;
        $('cameraBox').hidden = true;
        $('cameraLabel').textContent = 'Scan with camera';
    }

    $('qrCheckBtn').addEventListener('click', () => checkQr());

    async function checkQr() {
        const payload = $('qrPayload').value.trim();
        $('qrPreview').hidden = true;
        scannedQr = null;
        if (!payload) {
            toast('Scan, upload or paste a QR code first', 'error');
            return;
        }
        try {
            const preview = await api(WALLET_API, '/api/qr/preview', { method: 'POST', body: { payload } });
            if (preview.alreadyPaid) {
                $('qrPayload').value = '';
                toast('This QR code has already been paid', 'error');
                return;
            }
            scannedQr = { payload, preview };
            $('previewAvatar').textContent = initial(preview.payeeName);
            $('previewName').textContent = preview.payeeName;
            $('previewEmail').textContent = preview.payeeEmail;
            $('previewFixed').textContent = preview.amount ? money.format(preview.amount) : '';
            $('previewAmountLabel').hidden = Boolean(preview.amount);
            $('previewAmount').value = '';
            $('previewMeta').textContent = `${preview.singleUse ? 'Single-use' : 'Reusable'} code · valid until `
                + dateTime.format(new Date(preview.expiresAt));
            $('qrPreview').hidden = false;
        } catch (e) {
            toast(e.message, 'error');
        }
    }

    $('qrPayBtn').addEventListener('click', (e) => {
        if (!scannedQr) return;
        busy(e.target, async () => {
            const fixed = scannedQr.preview.amount;
            const amount = fixed ? null : Number($('previewAmount').value);
            if (!fixed && !(amount > 0)) throw new Error('Enter the amount to pay');
            await api(WALLET_API, '/api/qr/pay', {
                method: 'POST', idempotent: true, body: { payload: scannedQr.payload, amount }
            });
            toast(`Paid ${money.format(fixed || amount)} to ${scannedQr.preview.payeeName}`, 'success');
            scannedQr = null;
            $('qrPreview').hidden = true;
            $('qrPayload').value = '';
            navigate('home');
            refreshSoon();
        });
    });

    // ------------------------------------------------------------------ start

    if (session.token && session.user) {
        showApp();
    } else {
        showAuth('login');
    }
})();
