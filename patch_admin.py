import re
import os

with open('templates/admin.html', 'r', encoding='utf-8') as f:
    html = f.read()

# 1. Update Tab Switching with LocalStorage
html = html.replace('function switchAdminTab(tab) {', '''function switchAdminTab(tab) {
        localStorage.setItem('adminActiveTab', tab);''')

html = html.replace('''    document.addEventListener('DOMContentLoaded', () => {
        generateQRCodes();
    });''', '''    document.addEventListener('DOMContentLoaded', () => {
        const activeTab = localStorage.getItem('adminActiveTab') || 'kitchen';
        switchAdminTab(activeTab);
        generateQRCodes();
    });''')

# 2. Add New Room Form and Button to Tab 3 Header
new_room_ui = '''                <div>
                    <h2 class="panel-title">📱 Odalar & Mutfak QR Kod Oluşturucu</h2>
                    <p class="panel-subtitle" style="margin-bottom: 0;">Mutfak paneli ve odalar için özel QR kodlar
                        hazırlandı.</p>
                </div>
                <div style="display:flex; gap: 8px;">
                    <button type="button" class="btn-gradient-primary" style="width: auto; padding: 8px 16px; font-size: 0.85rem; background: #10b981;" onclick="document.getElementById('newRoomForm').style.display = 'flex'">
                        ➕ Yeni Oda Ekle
                    </button>
                    <button type="button" class="btn-gradient-primary"
                        style="width: auto; padding: 8px 16px; font-size: 0.85rem;" onclick="window.print()">
                        🖨️ Tüm QR Kodları Yazdır
                    </button>
                </div>
            </div>
            
            <form id="newRoomForm" onsubmit="createNewRoom(event)" style="display: none; gap: 10px; margin-top: 15px; padding: 15px; background: #f8fafc; border: 1px dashed #cbd5e1; border-radius: 10px;">
                <input type="text" id="newRoomName" placeholder="Oda Adı (Örn: Toplantı)" class="form-input-custom inline-input" required>
                <input type="text" id="newRoomSlug" placeholder="Link Adı (Örn: toplanti)" class="form-input-custom inline-input" required>
                <button type="submit" class="btn-save-mini" style="background: #10b981;">Ekle</button>
                <button type="button" class="btn-save-mini" style="background: #ef4444;" onclick="document.getElementById('newRoomForm').style.display='none'">İptal</button>
            </form>'''

html = re.sub(r'<div>\s*<h2 class="panel-title">.*?Odalar.*?Oluşturucu</h2>\s*<p class="panel-subtitle".*?hazırlandı\.</p>\s*</div>\s*<button type="button" class="btn-gradient-primary".*?Yazdır\s*</button>\s*</div>', new_room_ui, html, flags=re.DOTALL)


# 3. Make room names clickable and remove "Sayfayı Aç"
html = html.replace('''<span class="room-current-title" style="color: #4338ca; font-weight: 800;">Mutfak
                                    Paneli</span>''', 
                    '''<a href="/mutfak" class="room-current-title" style="color: #4338ca; font-weight: 800; text-decoration: none;">Mutfak Paneli</a>''')

html = html.replace('''<a href="/mutfak" target="_blank" class="btn-open-url"
                            style="text-decoration: none; background: #4338ca; color: white; border: none;">Sayfayı Aç
                            ↗</a>''', '')

html = html.replace('''<span class="room-current-title">{{ r.name }}</span>''',
                    '''<a href="/{{ r.slug }}" class="room-current-title" style="text-decoration:none; color:inherit; cursor:pointer;">{{ r.name }}</a>''')

html = html.replace('''<a href="/{{ r.slug }}" target="_blank" class="btn-open-url"
                            style="text-decoration: none;">Sayfayı Aç ↗</a>''', '')

# 4. Add Delete Room Button
html = html.replace('''<button type="submit" class="btn-save-mini">Kaydet</button>
                        </form>''',
                    '''<button type="submit" class="btn-save-mini">Kaydet</button>
                            <button type="button" class="btn-save-mini" style="background: #ef4444;" onclick="deleteRoom({{ r.id }})">Sil</button>
                        </form>''')

# 5. Add JS functions for creating and deleting rooms
js_functions = '''
    async function createNewRoom(e) {
        e.preventDefault();
        const name = document.getElementById('newRoomName').value;
        const slug = document.getElementById('newRoomSlug').value;
        const fd = new FormData();
        fd.append('name', name);
        fd.append('slug', slug);
        const res = await fetch('/api/rooms/create', { method: 'POST', body: fd });
        const data = await res.json();
        if (data.error) alert(data.error);
        else window.location.reload();
    }
    
    async function deleteRoom(id) {
        if(!confirm('Bu odayı silmek istediğinize emin misiniz?')) return;
        const res = await fetch('/api/rooms/' + id, { method: 'DELETE' });
        const data = await res.json();
        if (data.error) alert(data.error);
        else window.location.reload();
    }
</script>'''

html = html.replace('</script>', js_functions)

with open('templates/admin.html', 'w', encoding='utf-8') as f:
    f.write(html)
