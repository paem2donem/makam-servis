import os
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
import models

def migrate():
    # Eski SQLite Veritabanı
    sqlite_url = "sqlite:///makam_servis.db"
    sqlite_engine = create_engine(sqlite_url)
    SqliteSession = sessionmaker(bind=sqlite_engine)
    
    # Yeni Postgres Veritabanı
    postgres_url = "postgresql://neondb_owner:npg_fuDOYASvX7B2@ep-patient-bonus-b2hgypkc.c-6.eu-central-1.aws.neon.tech/neondb?sslmode=require"
    pg_engine = create_engine(postgres_url)
    PgSession = sessionmaker(bind=pg_engine)
    
    try:
        sqlite_session = SqliteSession()
        pg_session = PgSession()
        
        # Odaları Aktar
        rooms = sqlite_session.query(models.Room).all()
        for r in rooms:
            exists = pg_session.query(models.Room).filter_by(slug=r.slug).first()
            if not exists:
                new_room = models.Room(name=r.name, slug=r.slug)
                pg_session.add(new_room)
        pg_session.commit()
        print(f"{len(rooms)} oda başarıyla aktarıldı.")
        
        # Ürünleri Aktar
        products = sqlite_session.query(models.Product).all()
        for p in products:
            exists = pg_session.query(models.Product).filter_by(name=p.name).first()
            if not exists:
                new_product = models.Product(
                    name=p.name,
                    image_url=p.image_url,
                    category=p.category,
                    display_order=p.display_order,
                    is_available=p.is_available
                )
                pg_session.add(new_product)
        pg_session.commit()
        print(f"{len(products)} ürün başarıyla aktarıldı.")
        print("Bütün veriler Neon'a taşındı!")
        
    except Exception as e:
        print("Hata oluştu:", e)
    finally:
        sqlite_session.close()
        pg_session.close()

if __name__ == "__main__":
    migrate()
