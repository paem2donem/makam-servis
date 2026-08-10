import os
import shutil
from sqlalchemy import create_engine
from sqlalchemy.orm import declarative_base
from sqlalchemy.orm import sessionmaker

# 1. Ortam değişkeninden (Environment Variable) veritabanı URL'sini al (Render/Neon için)
SQLALCHEMY_DATABASE_URL = os.environ.get("DATABASE_URL")

if not SQLALCHEMY_DATABASE_URL:
    # 2. Eğer DATABASE_URL yoksa (yerel çalışma), eski SQLite sistemini kullan
    DATA_DIR = "/data" if os.path.exists("/data") else "."
    DB_PATH = os.path.join(DATA_DIR, "makam_servis.db")
    
    if DATA_DIR == "/data" and not os.path.exists(DB_PATH) and os.path.exists("./makam_servis.db"):
        try:
            shutil.copy("./makam_servis.db", DB_PATH)
        except Exception as e:
            print(f"Veritabanı kalıcı diske kopyalanamadı: {e}")
            
    SQLALCHEMY_DATABASE_URL = f"sqlite:///{DB_PATH}"

# Postgres URL'leri "postgres://" ile başlayabilir, SQLAlchemy "postgresql://" bekler. Bunu düzeltelim.
if SQLALCHEMY_DATABASE_URL.startswith("postgres://"):
    SQLALCHEMY_DATABASE_URL = SQLALCHEMY_DATABASE_URL.replace("postgres://", "postgresql://", 1)

# SQLite için thread check kapatılır, Postgres için buna gerek yoktur
connect_args = {"check_same_thread": False} if SQLALCHEMY_DATABASE_URL.startswith("sqlite") else {}

engine = create_engine(
    SQLALCHEMY_DATABASE_URL, connect_args=connect_args
)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

Base = declarative_base()

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
