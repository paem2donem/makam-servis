# 🚀 Oracle Cloud Dağıtım (Deployment) Kılavuzu

Bu belge, **Makam Servis (Kat Mutfakları Sürümü)** projesini Oracle Cloud Compute Instance (Sanal Sunucu) üzerinde kalıcı, güvenli ve 7/24 kesintisiz şekilde yayına almanız için hazırlanmıştır.

---

## 📌 1. Adım: Projeyi GitHub Deposuna Gönderme

Yerel bilgisayarınızda (proje klasöründe terminal veya PowerShell açarak):

1. **Git deposunu başlatın ve commit yapın:**
   ```bash
   git init
   git add .
   git commit -m "Makam Servis - Kat Mutfaklari ve Cloud Dagitim Surumu"
   ```

2. **GitHub Deponuz:**
   `https://github.com/paem2donem/makam-servis.git`

3. **Yerel deponuzu GitHub'a push edin:**
   ```bash
   git push origin main
   ```

---

## 📌 2. Adım: Oracle Cloud Ağ & Port Ayarları (ÇOK ÖNEMLİ)

Oracle Cloud sunucularında web sayfasına dışarıdan erişilebilmesi için hem **Oracle VCN Güvenlik Listesi**'nden hem de **Sunucu İçi Güvenlik Duvarı**'ndan portların açılması gerekir.

### A) Oracle Cloud Panelinden Portları Açma (Ingress Rules):
1. Oracle Cloud konsoluna giriş yapın: **Networking > Virtual Cloud Networks (VCN)**.
2. Sunucunuzun bağlı olduğu VCN'e ve ardından alt ağa (**Subnet**) tıklayın.
3. **Default Security List for...** öğesine tıklayın.
4. **Add Ingress Rules** butonuna basın ve şu kuralları ekleyin:
   - **Source CIDR:** `0.0.0.0/0`
   - **IP Protocol:** `TCP`
   - **Destination Port Range:** `80,443,8000`
   - **Description:** `HTTP, HTTPS ve Makam Servis Portlari`
5. Kaydedin.

### B) Sunucu İçi Güvenlik Duvarını (iptables) Açma:
Oracle Linux veya Ubuntu sanal sunucularda işletim sisteminin dahili iptables kuralları dışarıdan gelen HTTP isteklerini engelleyebilir. SSH ile sunucunuza bağlanıp şu komutları çalıştırın:

```bash
# Port 80 ve 443'e gelen trafiğe izin ver
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 8000 -j ACCEPT

# Ayarları kalıcı hale getir
sudo apt-get install -y iptables-persistent
sudo netfilter-persistent save
```

---

## 📌 3. Adım: Sunucuda Docker ile Tek Komutla Çalıştırma (ÖNERİLEN)

Projeniz için `Dockerfile` ve `docker-compose.yml` hazırlandı.

1. **Sunucunuza SSH ile bağlanın:**
   ```bash
   ssh -i oci_key.pem ubuntu@SUNUCU_IP_ADRESINIZ
   ```

2. **Docker ve Git'i yükleyin (eğer kurulu değilse):**
   ```bash
   sudo apt update
   sudo apt install -y git docker.io docker-compose-v2
   sudo usermod -aG docker $USER
   newgrp docker
   ```

3. **Projeyi GitHub'dan çekin:**
   ```bash
   git clone https://github.com/paem2donem/makam-servis.git makam_servis
   cd makam_servis
   ```

4. **Uygulamayı başlatın:**
   ```bash
   docker compose up -d --build
   ```

5. **Kontrol Edin:**
   - Tarayıcınızdan `http://SUNUCU_IP_ADRESINIZ` adresine gidin.
   - Makam Servis ve Kat Mutfakları paneli hemen canlıya geçecektir!

---

## 📌 4. Adım: Projeyi Güncelleme (Yeni Kod Gönderildiğinde)

Kodlarınızda güncelleme yapıp GitHub'a gönderdikten sonra sunucuda güncellemek için:

```bash
cd makam_servis
git pull
docker compose up -d --build
```
Veritabanınız (`./data` klasöründe) ve yüklenen ürün fotoğraflarınız container yeniden başlasa dahi **asla silinmez**, güvenle korunur.

---

## 📌 5. Adım: İsteğe Bağlı Domain (Alan Adı) ve Ücretsiz SSL (HTTPS) Kurulumu

Eğer bir domain adresiniz varsa (örn: `ikram.sirketiniz.com`):

1. Domain DNS ayarlarından sunucunuzun IP adresine bir **A Kaydı** (A Record) yönlendirin.
2. Nginx ve Certbot kurun:
   ```bash
   sudo apt install -y nginx certbot python3-certbot-nginx
   ```
3. Projedeki `nginx.conf` dosyasını aktif edin:
   ```bash
   sudo cp nginx.conf /etc/nginx/sites-available/makam_servis
   sudo ln -s /etc/nginx/sites-available/makam_servis /etc/nginx/sites-enabled/
   sudo rm /etc/nginx/sites-enabled/default
   sudo nginx -t
   sudo systemctl reload nginx
   ```
4. Ücretsiz SSL sertifikasını otomatik kurun:
   ```bash
   sudo certbot --nginx -d ikram.sirketiniz.com
   ```
Artık tabletlerde ve telefonlarda `https://ikram.sirketiniz.com` üzerinden sesli bildirimler ve kamera QR okuma tam güvenlikle çalışacaktır.
