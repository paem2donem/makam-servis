from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect, Depends, Form, File, UploadFile, HTTPException, status
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from sqlalchemy import text
from sqlalchemy.orm import Session
from database import engine, Base, get_db
import models
import os
import shutil
import datetime
import socket

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

def is_admin_authenticated(request: Request) -> bool:
    return request.cookies.get("admin_session") == "authenticated"

class ConnectionManager:
    def __init__(self):
        self.active_connections: list[WebSocket] = []

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.append(websocket)

    def disconnect(self, websocket: WebSocket):
        self.active_connections.remove(websocket)

    async def broadcast(self, message: str):
        for connection in self.active_connections:
            await connection.send_text(message)

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

def init_db(db: Session):
    check_schema()
    # Add default rooms if empty
    if not db.query(models.Room).first():
        rooms = [
            {"name": "Özel Kalem", "slug": "ozel-kalem"},
            {"name": "Büro Amiri", "slug": "buro-amiri"},
            {"name": "Makam 1", "slug": "makam-1"},
            {"name": "Makam 2", "slug": "makam-2"},
            {"name": "Makam 3", "slug": "makam-3"},
            {"name": "Makam 4", "slug": "makam-4"},
        ]
        for r in rooms:
            db.add(models.Room(**r))
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
    rooms = db.query(models.Room).all()
    return templates.TemplateResponse(request=request, name="select_room.html", context={"rooms": rooms})

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

@app.get("/mutfak", response_class=HTMLResponse)
async def kitchen_dashboard(request: Request):
    return templates.TemplateResponse(request=request, name="kitchen.html", context={})

@app.get("/admin", response_class=HTMLResponse)
async def admin_panel(request: Request, db: Session = Depends(get_db)):
    if not is_admin_authenticated(request):
        return RedirectResponse(url="/login", status_code=303)
    products = db.query(models.Product).order_by(models.Product.display_order.asc(), models.Product.id.asc()).all()
    rooms = db.query(models.Room).all()
    server_ip = get_local_ip()
    return templates.TemplateResponse(request=request, name="admin.html", context={"products": products, "rooms": rooms, "server_ip": server_ip})

# Catch-all room slug route MUST be at the end of page routes
@app.get("/{room_slug}", response_class=HTMLResponse)
async def show_room_page(request: Request, room_slug: str, db: Session = Depends(get_db)):
    room_obj = db.query(models.Room).filter(models.Room.slug == room_slug).first()
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
    db: Session = Depends(get_db)
):
    if not is_admin_authenticated(request):
        return {"error": "Yetkisiz erişim"}
    room = db.query(models.Room).filter(models.Room.id == room_id).first()
    if not room:
        return {"error": "Oda bulunamadı"}
    room.name = name
    db.commit()
    return {"status": "success"}

@app.post("/api/rooms/create")
async def create_room(
    request: Request,
    name: str = Form(...),
    slug: str = Form(...),
    db: Session = Depends(get_db)
):
    if not is_admin_authenticated(request):
        return {"error": "Yetkisiz erişim"}
    
    # Check if slug exists
    exists = db.query(models.Room).filter(models.Room.slug == slug).first()
    if exists:
        return {"error": "Bu link (slug) zaten kullanımda!"}
        
    new_room = models.Room(name=name, slug=slug)
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

@app.post("/api/orders")
async def create_order(request: Request, db: Session = Depends(get_db)):
    data = await request.json()
    room_id = data.get("room_id")
    items = data.get("items", [])
    
    if not room_id or not items:
        return {"error": "Eksik veri"}
        
    new_order = models.Order(room_id=room_id)
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
    
    # Broadcast to kitchen
    await manager.broadcast("new_order")
    
    return {"status": "success", "order_id": new_order.id}

@app.get("/api/orders/active")
async def get_active_orders(db: Session = Depends(get_db)):
    orders = db.query(models.Order).filter(models.Order.status == "pending").order_by(models.Order.created_at.desc()).all()
    result = []
    for o in orders:
        items = []
        for i in o.items:
            items.append({
                "product_name": i.product.name if i.product else "Bilinmeyen Ürün",
                "quantity": i.quantity,
                "notes": i.notes
            })
        result.append({
            "id": o.id,
            "room_name": o.room.name if o.room else "Bilinmeyen Oda",
            "created_at": o.created_at.isoformat(),
            "items": items
        })
    return result

@app.post("/api/orders/{order_id}/complete")
async def complete_order(order_id: int, db: Session = Depends(get_db)):
    order = db.query(models.Order).filter(models.Order.id == order_id).first()
    if order:
        order.status = "completed"
        order.completed_at = datetime.datetime.utcnow()
        db.commit()
        await manager.broadcast("order_completed")
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

@app.websocket("/ws/mutfak")
async def websocket_endpoint(websocket: WebSocket):
    await manager.connect(websocket)
    try:
        while True:
            data = await websocket.receive_text()
    except WebSocketDisconnect:
        manager.disconnect(websocket)
