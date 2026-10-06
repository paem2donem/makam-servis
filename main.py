from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect, Depends, Form, File, UploadFile, HTTPException, status
from fastapi.responses import HTMLResponse, RedirectResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from sqlalchemy import text, func
from sqlalchemy.orm import Session
from database import engine, Base, get_db
import models
import os
import shutil
import datetime
import socket
import re

RESERVED_SLUGS = {"admin", "mutfak", "api", "static", "uploads", "login", "logout", "docs", "redoc", "openapi.json", "favicon.ico", "indir", "app"}

def normalize_slug(slug_str: str) -> str:
    if not slug_str:
        return ""
    s = slug_str.strip().lstrip("/")
    # Replace Turkish special characters
    tr_map = str.maketrans("çğıöşüÇĞİÖŞÜ", "cgiosuCGIOSU")
    s = s.translate(tr_map)
    # Replace spaces and underscores with hyphens
    s = re.sub(r'[\s_]+', '-', s)
    # Remove chars that are not alphanumeric or hyphen
    s = re.sub(r'[^a-zA-Z0-9\-]', '', s)
    return s.strip('-')

# Create tables
models.Base.metadata.create_all(bind=engine)

app = FastAPI()

DATA_DIR = "/data" if os.path.exists("/data") else "."
UPLOADS_DIR = os.path.join(DATA_DIR, "uploads")
os.makedirs(UPLOADS_DIR, exist_ok=True)

if DATA_DIR == "/data" and os.path.exists("./uploads"):
    for item in os.listdir("./uploads"):
        s = os.path.join("./uploads", item)
        d = os.path.join(UPLOADS_DIR, item)
        if os.path.isfile(s) and not os.path.exists(d):
            try:
                shutil.copy2(s, d)
            except Exception:
                pass

os.makedirs("static", exist_ok=True)
os.makedirs("templates", exist_ok=True)
os.makedirs("uploads", exist_ok=True)

app.mount("/static", StaticFiles(directory="static"), name="static")
app.mount("/uploads", StaticFiles(directory=UPLOADS_DIR), name="uploads")
templates = Jinja2Templates(directory="templates")

def get_local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

FLOORS = {
    "makam": "Makam Katı Mutfağı",
    "kat-3": "3. Kat Mutfağı",
    "kat-4": "4. Kat Mutfağı",
    "kat-5": "5. Kat Mutfağı",
    "kat-6": "6. Kat Mutfağı"
}

def is_admin_authenticated(request: Request) -> bool:
    return request.cookies.get("admin_session") == "authenticated"

class ConnectionManager:
    def __init__(self):
        # floor -> list of websocket connections
        self.active_connections: dict[str, list[WebSocket]] = {}

    async def connect(self, websocket: WebSocket, floor: str = "all"):
        await websocket.accept()
        if floor not in self.active_connections:
            self.active_connections[floor] = []
        self.active_connections[floor].append(websocket)

    def disconnect(self, websocket: WebSocket, floor: str = "all"):
        if floor in self.active_connections and websocket in self.active_connections[floor]:
            self.active_connections[floor].remove(websocket)

    async def broadcast(self, message: str, floor: str = None):
        targets = []
        # Target specific floor
        if floor and floor in self.active_connections:
            targets.extend(self.active_connections[floor])
        # Also always broadcast to "all" (admin or master kitchen monitors)
        if "all" in self.active_connections:
            for ws in self.active_connections["all"]:
                if ws not in targets:
                    targets.append(ws)
        
        for connection in targets:
            try:
                await connection.send_text(message)
            except Exception:
                pass

manager = ConnectionManager()

def check_schema():
    with engine.connect() as conn:
        try:
            conn.execute(text("ALTER TABLE products ADD COLUMN category VARCHAR DEFAULT 'icecek'"))
            conn.commit()
        except Exception:
            pass
        try:
            conn.execute(text("ALTER TABLE products ADD COLUMN display_order INTEGER DEFAULT 0"))
            conn.commit()
        except Exception:
            pass
        try:
            conn.execute(text("ALTER TABLE rooms ADD COLUMN floor VARCHAR DEFAULT 'makam'"))
            conn.commit()
        except Exception:
            pass
        try:
            conn.execute(text("ALTER TABLE rooms ADD COLUMN display_order INTEGER DEFAULT 0"))
            conn.commit()
        except Exception:
            pass
        try:
            conn.execute(text("ALTER TABLE orders ADD COLUMN floor VARCHAR DEFAULT 'makam'"))
            conn.commit()
        except Exception:
            pass

def init_db(db: Session):
    check_schema()
    # Add default rooms if empty
    if not db.query(models.Room).first():
        rooms = [
            {"name": "Özel Kalem", "slug": "ozel-kalem", "floor": "makam"},
            {"name": "Büro Amiri", "slug": "buro-amiri", "floor": "makam"},
            {"name": "Makam 1", "slug": "makam-1", "floor": "makam"},
            {"name": "Makam 2", "slug": "makam-2", "floor": "makam"},
            {"name": "3. Kat Toplantı Odası", "slug": "kat3-toplanti", "floor": "kat-3"},
            {"name": "4. Kat Çalışma Odası", "slug": "kat4-calisma", "floor": "kat-4"},
            {"name": "5. Kat Koordinasyon", "slug": "kat5-koordinasyon", "floor": "kat-5"},
            {"name": "6. Kat Yönetim", "slug": "kat6-yonetim", "floor": "kat-6"},
        ]
        for r in rooms:
            db.add(models.Room(**r))
        db.commit()
    else:
        # Ensure rooms without a floor are assigned to 'makam'
        try:
            db.execute(text("UPDATE rooms SET floor = 'makam' WHERE floor IS NULL OR floor = ''"))
            db.commit()
        except Exception:
            pass

    # Add default products if empty
    if not db.query(models.Product).first():
        default_products = [
            {"name": "Çay", "category": "icecek", "display_order": 1},
            {"name": "Türk Kahvesi", "category": "icecek", "display_order": 2},
            {"name": "Su", "category": "icecek", "display_order": 3},
            {"name": "Soda", "category": "icecek", "display_order": 4},
            {"name": "Meyve Suyu", "category": "icecek", "display_order": 5},
            {"name": "Limonata", "category": "icecek", "display_order": 6},
            {"name": "Filtre Kahve", "category": "icecek", "display_order": 7},
            {"name": "Bitki Çayı", "category": "icecek", "display_order": 8},
            {"name": "Kuru Pasta", "category": "yemek", "display_order": 9},
            {"name": "Sandviç", "category": "yemek", "display_order": 10},
        ]
        for p in default_products:
            db.add(models.Product(**p, is_available=True))
        db.commit()

    image_mappings = {
        "cay": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSlGNwlpe34Oj7He48Oy7Wa5QA1OEeiJ6UpToPvoCfIZg&s=10",
        "çay": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSlGNwlpe34Oj7He48Oy7Wa5QA1OEeiJ6UpToPvoCfIZg&s=10",
        "demli çay": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSlGNwlpe34Oj7He48Oy7Wa5QA1OEeiJ6UpToPvoCfIZg&s=10",
        "bitki çayı": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQEXNmWp67mm_4Hi6JoeasPrxM1xiMGiBlO0PQ8DbRulQ&s=10",
        "türk kahvesi": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTUgCboBOZFPhBi9udSk_gJo-8XY2QNQONJGZWtZhPn7g&s=10",
        "filtre kahve": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTVkpW2BpREHE2O88079DWeDHC4r3Bkqi3NzjjUroMqng&s=10",
        "soda": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSvY_FUgKLvwZcGDORJfCHccQLRZ-rMpLbU_msMP8d1Pg&s=10",
        "maden suyu": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSvY_FUgKLvwZcGDORJfCHccQLRZ-rMpLbU_msMP8d1Pg&s=10",
        "su": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQRqpgO8FvQrLRAM7t0yRl33VwMpSN97-CxBqNO6eQe_g&s=10",
        "meyve suyu": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTvW3q7xxHwj_M9kpMbeCCukaiMgXUA7IP5fohUblsbIA&s=10",
        "taze portakal suyu": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTvW3q7xxHwj_M9kpMbeCCukaiMgXUA7IP5fohUblsbIA&s=10",
        "portakal suyu": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTvW3q7xxHwj_M9kpMbeCCukaiMgXUA7IP5fohUblsbIA&s=10",
        "limonata": "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTtDaMdWsZt0aQVqoyX9NVQqJv3Z3rPEYEa-sgMV2NxXQ&s=10",
    }

    for prod in db.query(models.Product).all():
        p_name_lower = prod.name.strip().lower()
        if p_name_lower in image_mappings:
            prod.image_url = image_mappings[p_name_lower]
        elif "çay" in p_name_lower and "bitki" not in p_name_lower:
            prod.image_url = image_mappings["cay"]
        elif "soda" in p_name_lower or "maden" in p_name_lower:
            prod.image_url = image_mappings["soda"]
        elif "meyve" in p_name_lower or "portakal" in p_name_lower:
            prod.image_url = image_mappings["meyve suyu"]
    db.commit()

@app.on_event("startup")
def startup_event():
    db = next(get_db())
    init_db(db)

# ==============================================================================
# PAGES & ROUTES (Fixed system routes MUST be defined before wildcards!)
# ==============================================================================

@app.get("/", response_class=HTMLResponse)
async def read_root(request: Request, room: str = None, db: Session = Depends(get_db)):
    if room:
        return await show_room_page(request, room, db)
    rooms = db.query(models.Room).order_by(models.Room.display_order.asc(), models.Room.id.asc()).all()
    return templates.TemplateResponse(request=request, name="select_room.html", context={"rooms": rooms, "floors": FLOORS})

@app.get("/login", response_class=HTMLResponse)
async def login_page(request: Request):
    if is_admin_authenticated(request):
        return RedirectResponse(url="/admin", status_code=303)
    return templates.TemplateResponse(request=request, name="login.html", context={})

@app.post("/login")
async def login_submit(request: Request, username: str = Form(...), password: str = Form(...)):
    if username == "hasan" and password == "zxcvbnm":
        response = RedirectResponse(url="/admin", status_code=303)
        response.set_cookie(key="admin_session", value="authenticated", httponly=True)
        return response
    return templates.TemplateResponse(request=request, name="login.html", context={"error": "Hatalı kullanıcı adı veya şifre!"})

@app.get("/logout")
async def logout():
    response = RedirectResponse(url="/login", status_code=303)
    response.delete_cookie(key="admin_session")
    return response

@app.get("/indir")
@app.get("/app")
async def download_apk():
    apk_path = "static/makam-servis.apk"
    if os.path.exists(apk_path):
        return FileResponse(
            apk_path, 
            media_type="application/vnd.android.package-archive", 
            filename="MakamServis-v1.0.5.apk",
            headers={"Cache-Control": "no-cache, no-store, must-revalidate"}
        )
    raise HTTPException(status_code=404, detail="Uygulama paketi henüz hazırlanmadı")


@app.get("/mutfak", response_class=HTMLResponse)
async def kitchen_selector(request: Request):
    return templates.TemplateResponse(request=request, name="kitchen_select.html", context={"floors": FLOORS})

@app.get("/mutfak/{floor}", response_class=HTMLResponse)
async def kitchen_dashboard(request: Request, floor: str):
    if floor not in FLOORS and floor != "all":
        floor = "makam"
    floor_name = FLOORS.get(floor, "Tüm Mutfaklar" if floor == "all" else "Mutfak Paneli")
    return templates.TemplateResponse(request=request, name="kitchen.html", context={
        "floor": floor,
        "floor_name": floor_name,
        "floors": FLOORS
    })

@app.get("/admin", response_class=HTMLResponse)
async def admin_panel(request: Request, db: Session = Depends(get_db)):
    if not is_admin_authenticated(request):
        return RedirectResponse(url="/login", status_code=303)
    products = db.query(models.Product).order_by(models.Product.display_order.asc(), models.Product.id.asc()).all()
    rooms = db.query(models.Room).order_by(models.Room.display_order.asc(), models.Room.id.asc()).all()
    server_ip = get_local_ip()
    return templates.TemplateResponse(request=request, name="admin.html", context={
        "products": products, 
        "rooms": rooms, 
        "server_ip": server_ip,
        "floors": FLOORS
    })

# Catch-all room slug route MUST be at the end of page routes
@app.get("/{room_slug}", response_class=HTMLResponse)
async def show_room_page(request: Request, room_slug: str, db: Session = Depends(get_db)):
    # Case-insensitive lookup so both /kat5-C and /kat5-c work
    room_obj = db.query(models.Room).filter(func.lower(models.Room.slug) == room_slug.lower()).first()
    if not room_obj:
        rooms = db.query(models.Room).all()
        return templates.TemplateResponse(request=request, name="select_room.html", context={"rooms": rooms, "error": "Geçersiz oda!"})
        
    products = db.query(models.Product).filter(models.Product.is_available == True).order_by(models.Product.display_order.asc(), models.Product.id.asc()).all()
    food_products = [p for p in products if p.category == "yemek"]
    drink_products = [p for p in products if p.category != "yemek"]
    
    return templates.TemplateResponse(request=request, name="index.html", context={
        "room": room_obj, 
        "food_products": food_products,
        "drink_products": drink_products
    })

# ==============================================================================
# API ENDPOINTS
# ==============================================================================

@app.post("/api/rooms/{room_id}/update")
async def update_room(
    request: Request,
    room_id: int,
    name: str = Form(...),
    slug: str = Form(None),
    floor: str = Form("makam"),
    db: Session = Depends(get_db)
):
    if not is_admin_authenticated(request):
        return {"error": "Yetkisiz erişim"}
    room = db.query(models.Room).filter(models.Room.id == room_id).first()
    if not room:
        return {"error": "Oda bulunamadı"}
    room.name = name.strip()
    room.floor = floor
    if slug:
        clean_slug = normalize_slug(slug)
        if not clean_slug:
            return {"error": "Geçerli bir bağlantı linki giriniz."}
        if clean_slug.lower() in RESERVED_SLUGS:
            return {"error": f"'{clean_slug}' sistem tarafından kullanılan özel bir linktir. Lütfen başka bir link belirleyin."}
        # Check if slug exists in another room
        exists = db.query(models.Room).filter(func.lower(models.Room.slug) == clean_slug.lower(), models.Room.id != room_id).first()
        if exists:
            return {"error": f"'{clean_slug}' linki zaten başka bir odada kullanımda!"}
        room.slug = clean_slug
    db.commit()
    return {"status": "success"}

@app.post("/api/rooms/create")
async def create_room(
    request: Request,
    name: str = Form(...),
    slug: str = Form(...),
    floor: str = Form("makam"),
    db: Session = Depends(get_db)
):
    if not is_admin_authenticated(request):
        return {"error": "Yetkisiz erişim"}
    
    clean_slug = normalize_slug(slug)
    if not clean_slug:
        return {"error": "Geçerli bir bağlantı linki giriniz."}
    if clean_slug.lower() in RESERVED_SLUGS:
        return {"error": f"'{clean_slug}' sistem tarafından kullanılan özel bir linktir. Lütfen başka bir link belirleyin."}
    exists = db.query(models.Room).filter(func.lower(models.Room.slug) == clean_slug.lower()).first()
    if exists:
        return {"error": f"'{clean_slug}' linki zaten kullanımda!"}
        
    # Set display_order to end of list
    max_order = db.query(func.max(models.Room.display_order)).scalar() or 0
    new_room = models.Room(name=name.strip(), slug=clean_slug, floor=floor, display_order=max_order + 1)
    db.add(new_room)
    db.commit()
    return {"status": "success"}

@app.delete("/api/rooms/{room_id}")
async def delete_room(
    request: Request,
    room_id: int,
    db: Session = Depends(get_db)
):
    if not is_admin_authenticated(request):
        return {"error": "Yetkisiz erişim"}
    room = db.query(models.Room).filter(models.Room.id == room_id).first()
    if not room:
        return {"error": "Oda bulunamadı"}
    db.delete(room)
    db.commit()
    return {"status": "success"}

@app.post("/api/rooms/reorder")
async def reorder_rooms(request: Request, db: Session = Depends(get_db)):
    if not is_admin_authenticated(request):
        return {"error": "Yetkisiz erişim"}
    data = await request.json()
    order_ids = data.get("order", [])
    for index, rid in enumerate(order_ids):
        room = db.query(models.Room).filter(models.Room.id == rid).first()
        if room:
            room.display_order = index
    db.commit()
    return {"status": "success"}

@app.post("/api/orders")
async def create_order(request: Request, db: Session = Depends(get_db)):
    data = await request.json()
    room_id = data.get("room_id")
    items = data.get("items", [])
    
    if not room_id or not items:
        return {"error": "Eksik veri"}
        
    room = db.query(models.Room).filter(models.Room.id == room_id).first()
    floor = room.floor if room and room.floor else "makam"
    
    new_order = models.Order(room_id=room_id, floor=floor)
    db.add(new_order)
    db.commit()
    db.refresh(new_order)
    
    for item in items:
        order_item = models.OrderItem(
            order_id=new_order.id,
            product_id=item["product_id"],
            quantity=item["quantity"],
            notes=item.get("notes", "")
        )
        db.add(order_item)
    
    db.commit()
    
    # Broadcast to specific floor's kitchen and admin
    await manager.broadcast("new_order", floor=floor)
    
    return {"status": "success", "order_id": new_order.id, "floor": floor}

@app.get("/api/orders/active")
async def get_active_orders(floor: str = None, db: Session = Depends(get_db)):
    query = db.query(models.Order).filter(models.Order.status == "pending")
    if floor and floor != "all":
        query = query.filter(models.Order.floor == floor)
    orders = query.order_by(models.Order.created_at.desc()).all()
    result = []
    for o in orders:
        items = []
        for i in o.items:
            items.append({
                "product_name": i.product.name if i.product else "Bilinmeyen Ürün",
                "quantity": i.quantity,
                "notes": i.notes
            })
        floor_key = o.floor or "makam"
        floor_name = FLOORS.get(floor_key, "Makam Katı Mutfağı")
        result.append({
            "id": o.id,
            "room_name": o.room.name if o.room else "Bilinmeyen Oda",
            "floor": floor_key,
            "floor_name": floor_name,
            "created_at": o.created_at.isoformat(),
            "items": items
        })
    return result

@app.get("/api/floors")
async def get_floors():
    return FLOORS

@app.get("/api/rooms")
async def get_rooms(floor: str = None, db: Session = Depends(get_db)):
    query = db.query(models.Room)
    if floor and floor != "all":
        query = query.filter(models.Room.floor == floor)
    rooms = query.order_by(models.Room.display_order.asc(), models.Room.id.asc()).all()
    return [{"id": r.id, "name": r.name, "slug": r.slug, "floor": r.floor or "makam"} for r in rooms]

@app.post("/api/orders/{order_id}/complete")
async def complete_order(order_id: int, db: Session = Depends(get_db)):
    order = db.query(models.Order).filter(models.Order.id == order_id).first()
    if order:
        order.status = "completed"
        order.completed_at = datetime.datetime.utcnow()
        floor = order.floor or "makam"
        db.commit()
        await manager.broadcast("order_completed", floor=floor)
        return {"status": "success"}
    return {"error": "Sipariş bulunamadı"}

@app.post("/api/products")
async def add_product(
    name: str = Form(...), 
    category: str = Form("icecek"),
    image: UploadFile = File(None),
    db: Session = Depends(get_db)
):
    image_url = None
    if image and image.filename:
        file_location = os.path.join(UPLOADS_DIR, image.filename)
        with open(file_location, "wb+") as file_object:
            shutil.copyfileobj(image.file, file_object)
        image_url = f"/uploads/{image.filename}"
        
    product = models.Product(name=name, category=category, image_url=image_url)
    db.add(product)
    db.commit()
    return {"status": "success"}

@app.post("/api/products/reorder")
async def reorder_products(request: Request, db: Session = Depends(get_db)):
    data = await request.json()
    order_ids = data.get("order", [])
    for index, pid in enumerate(order_ids):
        product = db.query(models.Product).filter(models.Product.id == pid).first()
        if product:
            product.display_order = index
    db.commit()
    return {"status": "success"}

@app.post("/api/products/{product_id}/delete")
async def delete_product(product_id: int, db: Session = Depends(get_db)):
    product = db.query(models.Product).filter(models.Product.id == product_id).first()
    if product:
        db.delete(product)
        db.commit()
    return {"status": "success"}

@app.post("/api/products/{product_id}/update")
async def update_product(
    product_id: int,
    name: str = Form(...),
    category: str = Form("icecek"),
    image: UploadFile = File(None),
    db: Session = Depends(get_db)
):
    product = db.query(models.Product).filter(models.Product.id == product_id).first()
    if not product:
        return {"error": "Ürün bulunamadı"}
    
    product.name = name
    product.category = category
    if image and image.filename:
        file_location = os.path.join(UPLOADS_DIR, image.filename)
        with open(file_location, "wb+") as file_object:
            shutil.copyfileobj(image.file, file_object)
        product.image_url = f"/uploads/{image.filename}"
        
    db.commit()
    return {"status": "success"}

# ==============================================================================
# WEBSOCKET
# ==============================================================================

@app.websocket("/ws/mutfak/{floor}")
async def websocket_floor_endpoint(websocket: WebSocket, floor: str):
    await manager.connect(websocket, floor)
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        manager.disconnect(websocket, floor)

@app.websocket("/ws/mutfak")
async def websocket_all_endpoint(websocket: WebSocket):
    await manager.connect(websocket, "all")
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        manager.disconnect(websocket, "all")
