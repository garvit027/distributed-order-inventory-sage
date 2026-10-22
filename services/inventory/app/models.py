"""Inventory tables (contract section 3.4).

* ``stock(sku PK, available, reserved, version)`` with SQLAlchemy optimistic
  locking via ``version_id_col="version"``.
* ``reservations(reservation_id PK, order_id, state, created_at)`` +
  ``reservation_lines(reservation_id, sku, quantity)``.
"""
from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import BigInteger, DateTime, ForeignKey, Integer, String
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Stock(Base):
    __tablename__ = "stock"

    sku: Mapped[str] = mapped_column(String, primary_key=True)
    available: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    reserved: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    version: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)

    # Optimistic locking: SQLAlchemy bumps `version` on every UPDATE and adds
    # `WHERE version = <seen>`; a lost race raises StaleDataError, which the
    # Reserve/Release handlers retry (contract section 3.4, up to 5 times).
    __mapper_args__ = {"version_id_col": version}


class Reservation(Base):
    __tablename__ = "reservations"

    reservation_id: Mapped[str] = mapped_column(String, primary_key=True)
    order_id: Mapped[str] = mapped_column(String, nullable=False)
    # "RESERVED" or "RELEASED" (contract section 3.4).
    state: Mapped[str] = mapped_column(String, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, default=utcnow
    )

    lines: Mapped[list["ReservationLine"]] = relationship(
        back_populates="reservation", cascade="all, delete-orphan"
    )


class ReservationLine(Base):
    __tablename__ = "reservation_lines"

    reservation_id: Mapped[str] = mapped_column(
        ForeignKey("reservations.reservation_id"), primary_key=True
    )
    sku: Mapped[str] = mapped_column(String, primary_key=True)
    quantity: Mapped[int] = mapped_column(Integer, nullable=False)

    reservation: Mapped[Reservation] = relationship(back_populates="lines")
