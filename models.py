from sqlalchemy import Column, Integer, String, Float, Boolean, ForeignKey, DateTime
from sqlalchemy.orm import relationship
from database import Base
import datetime

class Room(Base):
    __tablename__ = "rooms"
    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, unique=True, index=True)
    slug = Column(String, unique=True, index=True)
    floor = Column(String, default="makam", index=True) # 'makam', 'kat-3', 'kat-4', 'kat-5', 'kat-6'

class Product(Base):
    __tablename__ = "products"
    id = Column(Integer, primary_key=True, index=True)
    name = Column(String, index=True)
    image_url = Column(String)
    category = Column(String, default="icecek") # 'icecek' or 'yemek'
    display_order = Column(Integer, default=0)
    is_available = Column(Boolean, default=True)

class Order(Base):
    __tablename__ = "orders"
    id = Column(Integer, primary_key=True, index=True)
    room_id = Column(Integer, ForeignKey("rooms.id"))
    floor = Column(String, default="makam", index=True) # 'makam', 'kat-3', 'kat-4', 'kat-5', 'kat-6'
    status = Column(String, default="pending") # pending, completed
    created_at = Column(DateTime, default=datetime.datetime.utcnow)
    completed_at = Column(DateTime, nullable=True)

    room = relationship("Room")
    items = relationship("OrderItem", back_populates="order")

class OrderItem(Base):
    __tablename__ = "order_items"
    id = Column(Integer, primary_key=True, index=True)
    order_id = Column(Integer, ForeignKey("orders.id"))
    product_id = Column(Integer, ForeignKey("products.id"))
    quantity = Column(Integer, default=1)
    notes = Column(String, nullable=True)

    order = relationship("Order", back_populates="items")
    product = relationship("Product")
