document.addEventListener('DOMContentLoaded', () => {
    fetchOrders();
    setupWebSocket();
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

function setupWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${protocol}//${window.location.host}/ws/mutfak`);

    ws.onmessage = (event) => {
        if (event.data === 'new_order') {
            playSound();
            fetchOrders();
        } else if (event.data === 'order_completed') {
            fetchOrders();
        }
    };

    ws.onclose = () => {
        setTimeout(setupWebSocket, 3000);
    };
}

function playSound() {
    const audio = document.getElementById('notificationSound');
    if (audio) {
        audio.currentTime = 0;
        audio.play().catch(e => {
            console.log("Retrying audio play:", e);
            setTimeout(() => { audio.play().catch(()=>{}); }, 500);
        });
    }
}

async function fetchOrders() {
    try {
        const res = await fetch('/api/orders/active');
        const orders = await res.json();
        renderOrders(orders);
    } catch (e) {
        console.error("Orders fetch error:", e);
    }
}

function renderOrders(orders) {
    const container = document.getElementById('ordersContainer');
    const countPill = document.getElementById('kitchenOrderCount');
    if (countPill) countPill.textContent = orders.length;

    container.innerHTML = '';

    if (orders.length === 0) {
        container.innerHTML = `
            <div class="empty-kitchen-state">
                <div class="empty-icon">☕</div>
                <h3>Bekleyen Sipariş Bulunmuyor</h3>
                <p>Mutfak şu an sakin. Yeni siparişler anlık sesli uyarı ile buraya düşecektir.</p>
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
            <button class="btn-success btn-complete" onclick="completeOrder(${order.id})">✅ Teslim Edildi İşaretle</button>
        `;
        container.appendChild(card);
    });
}

async function completeOrder(orderId) {
    try {
        const res = await fetch(`/api/orders/${orderId}/complete`, { method: 'POST' });
        if (res.ok) {
            fetchOrders();
        }
    } catch (e) {
        console.error("Order completion error:", e);
    }
}
