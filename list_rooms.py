import os
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
import models

def list_rooms():
    postgres_url = "postgresql://neondb_owner:npg_fuDOYASvX7B2@ep-patient-bonus-b2hgypkc.c-6.eu-central-1.aws.neon.tech/neondb?sslmode=require"
    pg_engine = create_engine(postgres_url)
    PgSession = sessionmaker(bind=pg_engine)
    pg_session = PgSession()
    
    try:
        rooms = pg_session.query(models.Room).all()
        for r in rooms:
            print(f"Room: {r.name}, Slug: {r.slug}")
    except Exception as e:
        print("Hata:", e)
    finally:
        pg_session.close()

if __name__ == "__main__":
    list_rooms()
