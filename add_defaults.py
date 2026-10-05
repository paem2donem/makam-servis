import os
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
import models

def add_defaults():
    postgres_url = "postgresql://neondb_owner:npg_fuDOYASvX7B2@ep-patient-bonus-b2hgypkc.c-6.eu-central-1.aws.neon.tech/neondb?sslmode=require"
    pg_engine = create_engine(postgres_url)
    PgSession = sessionmaker(bind=pg_engine)
    pg_session = PgSession()
    
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
    
    try:
        count = 0
        for p in default_products:
            exists = pg_session.query(models.Product).filter_by(name=p["name"]).first()
            if not exists:
                new_product = models.Product(
                    name=p["name"],
                    category=p["category"],
                    display_order=p["display_order"],
                    is_available=True
                )
                pg_session.add(new_product)
                count += 1
        
        # Add a default room too
        if not pg_session.query(models.Room).filter_by(name="Makam Odası").first():
            pg_session.add(models.Room(name="Makam Odası", slug="makam"))
            
        pg_session.commit()
        print(f"{count} adet varsayılan ürün ve 1 oda eklendi.")
    except Exception as e:
        print("Hata:", e)
    finally:
        pg_session.close()

if __name__ == "__main__":
    add_defaults()
