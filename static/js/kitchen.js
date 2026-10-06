document.addEventListener('DOMContentLoaded', () => {
    const appEl = document.getElementById('kitchenApp');
    const currentFloor = appEl ? (appEl.dataset.floor || 'makam') : 'makam';
    
    fetchOrders(currentFloor);
    setupWebSocket(currentFloor);
    armAudioPermanently();
});

// Pre-arm audio on first touch/click anywhere so browser autoplay never blocks sound
function armAudioPermanently() {
    const unlockAudio = () => {
        const audio = document.getElementById('notificationSound');
        if (audio) {
            audio.play().then(() => {
                audio.pause();
                audio.currentTime = 0;
            }).catch(() => {});
        }
        document.removeEventListener('click', unlockAudio);
        document.removeEventListener('touchstart', unlockAudio);
        document.removeEventListener('keydown', unlockAudio);
    };

    document.addEventListener('click', unlockAudio);
    document.addEventListener('touchstart', unlockAudio);
    document.addEventListener('keydown', unlockAudio);
}

function setupWebSocket(currentFloor) {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${protocol}//${window.location.host}/ws/mutfak/${currentFloor}`);

    ws.onmessage = (event) => {
        if (event.data === 'new_order') {
            playSound();
            fetchOrders(currentFloor);
        } else if (event.data === 'order_completed') {
            fetchOrders(currentFloor);
        }
    };

    ws.onclose = () => {
        setTimeout(() => setupWebSocket(currentFloor), 3000);
    };
}

function playSound() {
    // 1. Try HTML5 Audio element
    const audio = document.getElementById('notificationSound');
    if (audio) {
        audio.currentTime = 0;
        audio.play().catch(e => {
            console.log("Audio tag play prevented, using WebAudio:", e);
        });
    }

    // 2. Play Web Audio API synthetic ding-dong bell (works 100% offline, no CDN needed)
    playWebAudioChime();

    // 3. Vibrate device if supported
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
        // High chime (E5 - 659.25Hz)
        const osc1 = ctx.createOscillator();
        const gain1 = ctx.createGain();
        osc1.type = 'sine';
        osc1.frequency.setValueAtTime(659.25, now);
        gain1.gain.setValueAtTime(0.8, now);
        gain1.gain.exponentialRampToValueAtTime(0.001, now + 0.6);
        osc1.connect(gain1);
        gain1.connect(ctx.destination);
        osc1.start(now);
        osc1.stop(now + 0.6);

        // Warm lower bell chime (C5 - 523.25Hz)
        const osc2 = ctx.createOscillator();
        const gain2 = ctx.createGain();
        osc2.type = 'sine';
        osc2.frequency.setValueAtTime(523.25, now + 0.22);
        gain2.gain.setValueAtTime(0.9, now + 0.22);
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
        const orders = await res.json();
        renderOrders(orders, currentFloor);
    } catch (e) {
        console.error("Orders fetch error:", e);
    }
}

function renderOrders(orders, currentFloor) {
    const container = document.getElementById('ordersContainer');
    const countPill = document.getElementById('kitchenOrderCount');
    if (countPill) countPill.textContent = orders.length;

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
