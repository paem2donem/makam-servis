let isKitchenMuted = localStorage.getItem('kitchen_muted') === 'true';
let knownOrderIds = new Set();
let isInitialLoad = true;

document.addEventListener('DOMContentLoaded', () => {
    const appEl = document.getElementById('kitchenApp');
    const currentFloor = appEl ? (appEl.dataset.floor || 'makam') : 'makam';
    
    updateMuteButtonUI();
    fetchOrders(currentFloor);
    setupWebSocket(currentFloor);
    armAudioPermanently();
});

function updateMuteButtonUI() {
    const btn = document.getElementById('btnMuteToggle');
    const badge = document.getElementById('soundBadge');
    
    if (btn) {
        if (isKitchenMuted) {
            btn.innerHTML = '🔇 Sesli Uyarı: Kapalı';
            btn.style.background = '#dc2626';
        } else {
            btn.innerHTML = '🔊 Sesli Uyarı: Açık';
            btn.style.background = '#16a34a';
        }
    }

    if (badge) {
        if (isKitchenMuted) {
            badge.innerHTML = '🔇 Sesli Uyarı Kapalı';
            badge.style.background = '#dc2626';
            badge.style.color = '#ffffff';
        } else {
            badge.innerHTML = '🔊 Sesli Uyarı Açık';
            badge.style.background = '#16a34a';
            badge.style.color = '#ffffff';
        }
    }
}

function toggleKitchenMute() {
    isKitchenMuted = !isKitchenMuted;
    localStorage.setItem('kitchen_muted', isKitchenMuted ? 'true' : 'false');
    updateMuteButtonUI();
    if (!isKitchenMuted) {
        // Test sound briefly when turning sound back ON
        playSound();
    }
}

// Pre-arm audio on first touch/click anywhere so browser autoplay never blocks sound
function armAudioPermanently(forcePlay = false) {
    const banner = document.getElementById('audioUnlockBanner');
    
    const unlockAudio = () => {
        const audio = document.getElementById('notificationSound');
        if (audio) {
            audio.play().then(() => {
                if (!forcePlay) {
                    audio.pause();
                    audio.currentTime = 0;
                }
            }).catch(() => {});
        }
        
        try {
            const AudioCtx = window.AudioContext || window.webkitAudioContext;
            if (AudioCtx) {
                const ctx = new AudioCtx();
                if (ctx.state === 'suspended') {
                    ctx.resume();
                }
            }
        } catch (_) {}

        if (banner) banner.style.display = 'none';

        document.removeEventListener('click', unlockAudio);
        document.removeEventListener('touchstart', unlockAudio);
        document.removeEventListener('keydown', unlockAudio);
    };

    if (forcePlay) {
        unlockAudio();
    } else {
        document.addEventListener('click', unlockAudio);
        document.addEventListener('touchstart', unlockAudio);
        document.addEventListener('keydown', unlockAudio);
    }
}

function setupWebSocket(currentFloor) {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/mutfak/${currentFloor}`;
    let ws = null;
    let reconnectTimeout = null;

    function connect() {
        if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
            return;
        }

        try {
            ws = new WebSocket(wsUrl);

            ws.onopen = () => {
                console.log("Mutfak WebSocket bağlantısı kuruldu:", currentFloor);
            };

            ws.onmessage = (event) => {
                if (event.data === 'new_order' || event.data === 'order_completed') {
                    fetchOrders(currentFloor);
                }
            };

            ws.onerror = (err) => {
                console.warn("WebSocket hatası:", err);
            };

            ws.onclose = () => {
                clearTimeout(reconnectTimeout);
                reconnectTimeout = setTimeout(connect, 3000);
            };
        } catch (e) {
            clearTimeout(reconnectTimeout);
            reconnectTimeout = setTimeout(connect, 3000);
        }
    }

    connect();

    // Kesintisiz 3 saniyede bir otomatik polling garantisi (Ağ/WS kopsa dahi sipariş anında ekrana düşer)
    setInterval(() => {
        fetchOrders(currentFloor);
    }, 3000);
}

function playSound() {
    if (isKitchenMuted) {
        console.log("Sesli uyarı kapalı. Ses ve titreşim çalınmadı.");
        return;
    }

    // 1. HTML5 Audio element
    const audio = document.getElementById('notificationSound');
    if (audio) {
        audio.currentTime = 0;
        audio.play().catch(e => {
            console.log("Audio tag play prevented, using WebAudio chime:", e);
        });
    }

    // 2. Web Audio API synthesizer (Çevrimdışı & CDN bağımsız %100 ding-dong)
    playWebAudioChime();

    // 3. Titreşim (Mobil destekli)
    if (navigator.vibrate) {
        try {
            navigator.vibrate([400, 200, 400]);
        } catch (_) {}
    }
}

function playWebAudioChime() {
    try {
        const AudioCtx = window.AudioContext || window.webkitAudioContext;
        if (!AudioCtx) return;
        const ctx = new AudioCtx();
        if (ctx.state === 'suspended') {
            ctx.resume();
        }

        const now = ctx.currentTime;
        // Birinci ton: 659.25Hz (E5)
        const osc1 = ctx.createOscillator();
        const gain1 = ctx.createGain();
        osc1.type = 'sine';
        osc1.frequency.setValueAtTime(659.25, now);
        gain1.gain.setValueAtTime(0.85, now);
        gain1.gain.exponentialRampToValueAtTime(0.001, now + 0.6);
        osc1.connect(gain1);
        gain1.connect(ctx.destination);
        osc1.start(now);
        osc1.stop(now + 0.6);

        // İkinci sıcak ton: 523.25Hz (C5)
        const osc2 = ctx.createOscillator();
        const gain2 = ctx.createGain();
        osc2.type = 'sine';
        osc2.frequency.setValueAtTime(523.25, now + 0.22);
        gain2.gain.setValueAtTime(0.95, now + 0.22);
        gain2.gain.exponentialRampToValueAtTime(0.001, now + 1.2);
        osc2.connect(gain2);
        gain2.connect(ctx.destination);
        osc2.start(now + 0.22);
        osc2.stop(now + 1.2);
    } catch (err) {
        console.log("WebAudio error:", err);
    }
}

async function fetchOrders(currentFloor) {
    try {
        const floorParam = currentFloor ? `?floor=${encodeURIComponent(currentFloor)}` : '';
        const res = await fetch(`/api/orders/active${floorParam}`);
        if (!res.ok) return;
        const orders = await res.json();

        // Yeni gelen sipariş kontrolü
        let hasBrandNewOrder = false;
        orders.forEach(order => {
            if (!knownOrderIds.has(order.id)) {
                if (!isInitialLoad) {
                    hasBrandNewOrder = true;
                }
                knownOrderIds.add(order.id);
            }
        });

        if (hasBrandNewOrder) {
            playSound();
        }

        isInitialLoad = false;
        renderOrders(orders, currentFloor);
    } catch (e) {
        console.error("Orders fetch error:", e);
    }
}

function renderOrders(orders, currentFloor) {
    const container = document.getElementById('ordersContainer');
    const countPill = document.getElementById('kitchenOrderCount');
    if (countPill) countPill.textContent = orders.length;

    if (!container) return;
    container.innerHTML = '';

    if (orders.length === 0) {
        container.innerHTML = `
            <div class="empty-kitchen-state">
                <div class="empty-icon">☕</div>
                <h3>Bekleyen Sipariş Bulunmuyor</h3>
                <p>Bu katın mutfağı şu an sakin. Katınızdaki odalardan yeni sipariş geldiğinde anlık sesli uyarı ile buraya düşecektir.</p>
            </div>
        `;
        return;
    }

    orders.forEach(order => {
        const d = new Date(order.created_at + 'Z'); 
        const timeStr = d.toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'});

        const card = document.createElement('div');
        card.className = 'order-card kitchen-card';
        
        let itemsHtml = '<ul class="kitchen-items">';
        let notesHtml = '';
        order.items.forEach(item => {
            itemsHtml += `<li><span>${item.product_name}</span> <span class="qty-badge">x${item.quantity}</span></li>`;
            if (item.notes) {
                notesHtml = `<div class="order-notes"><strong>Not:</strong> ${item.notes}</div>`;
            }
        });
        itemsHtml += '</ul>';

        card.innerHTML = `
            <div class="order-header">
                <span class="room-badge">${order.room_name}</span>
                <span class="time-badge">⏱️ ${timeStr}</span>
            </div>
            ${itemsHtml}
            ${notesHtml}
            <button class="btn-success btn-complete" onclick="completeOrder(${order.id}, '${currentFloor}')">✅ Teslim Edildi İşaretle</button>
        `;
        container.appendChild(card);
    });
}

async function completeOrder(orderId, currentFloor) {
    try {
        const res = await fetch(`/api/orders/${orderId}/complete`, { method: 'POST' });
        if (res.ok) {
            fetchOrders(currentFloor);
        }
    } catch (e) {
        console.error("Order completion error:", e);
    }
}
