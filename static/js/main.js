let cart = {};

function updateQty(productId, change) {
    const qtySpan = document.getElementById(`qty-${productId}`);
    let currentQty = parseInt(qtySpan.textContent);
    
    currentQty += change;
    if (currentQty < 0) currentQty = 0;
    
    qtySpan.textContent = currentQty;
    
    const productCard = document.querySelector(`.product-card[data-id="${productId}"]`);
    const productName = productCard.dataset.name;
    
    if (currentQty > 0) {
        cart[productId] = { name: productName, qty: currentQty };
    } else {
        delete cart[productId];
    }
    
    renderCart();
}

function renderCart() {
    const cartList = document.getElementById('cartItems');
    const orderControls = document.getElementById('orderControls');
    
    cartList.innerHTML = '';
    
    const itemIds = Object.keys(cart);
    if (itemIds.length === 0) {
        cartList.innerHTML = '<li class="empty-text">Sepetiniz boş.</li>';
        orderControls.style.display = 'none';
        return;
    }
    
    orderControls.style.display = 'block';
    
    itemIds.forEach(id => {
        const item = cart[id];
        const li = document.createElement('li');
        li.innerHTML = `<span>${item.name}</span> <strong>x${item.qty}</strong>`;
        cartList.appendChild(li);
    });
}

async function submitOrder() {
    const roomId = document.getElementById('roomId').value;
    const notes = document.getElementById('orderNotes').value;
    
    const items = Object.keys(cart).map(id => ({
        product_id: parseInt(id),
        quantity: cart[id].qty,
        notes: notes // For now we attach notes to the order generally, but backend expects per item, so we attach it to the first item
    }));
    
    if (items.length === 0) return;

    const payload = {
        room_id: parseInt(roomId),
        items: items.map((it, idx) => ({ ...it, notes: idx === 0 ? notes : "" }))
    };

    const res = await fetch('/api/orders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    });

    if (res.ok) {
        // Reset cart
        cart = {};
        document.querySelectorAll('.qty').forEach(el => el.textContent = '0');
        document.getElementById('orderNotes').value = '';
        renderCart();
        
        // Show Toast
        const toast = document.getElementById('toast');
        toast.className = 'toast show';
        setTimeout(() => { toast.className = toast.className.replace('show', ''); }, 3000);
    } else {
        alert("Sipariş gönderilirken bir hata oluştu.");
    }
}
