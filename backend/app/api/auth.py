"""
JWT Authentication System
Simple but secure authentication for the trading bot
"""

from datetime import datetime, timedelta
from typing import Optional
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from pydantic import BaseModel
from jose import JWTError, jwt
from passlib.context import CryptContext
from loguru import logger

from app.config import settings


# Password hashing
pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

# JWT settings
SECRET_KEY = settings.secret_key
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = 60 * 24  # 24 hours

# Security scheme
security = HTTPBearer()


class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_at: datetime


class TokenData(BaseModel):
    username: Optional[str] = None
    user_id: Optional[str] = None


class UserCreate(BaseModel):
    username: str
    password: str
    email: Optional[str] = None


class UserLogin(BaseModel):
    username: str
    password: str


class User(BaseModel):
    id: str
    username: str
    email: Optional[str] = None
    is_active: bool = True
    created_at: datetime


# Simple in-memory user store (replace with database in production)
_users_db: dict = {}


def verify_password(plain_password: str, hashed_password: str) -> bool:
    """Verify a password against its hash"""
    return pwd_context.verify(plain_password, hashed_password)


def get_password_hash(password: str) -> str:
    """Hash a password"""
    return pwd_context.hash(password)


def create_access_token(data: dict, expires_delta: Optional[timedelta] = None) -> Token:
    """Create a new JWT access token"""
    to_encode = data.copy()
    expire = datetime.utcnow() + (expires_delta or timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES))
    to_encode.update({"exp": expire})

    encoded_jwt = jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)

    return Token(
        access_token=encoded_jwt,
        expires_at=expire
    )


def decode_token(token: str) -> Optional[TokenData]:
    """Decode and validate a JWT token"""
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        username: str = payload.get("sub")
        user_id: str = payload.get("user_id")

        if username is None:
            return None

        return TokenData(username=username, user_id=user_id)
    except JWTError as e:
        logger.error(f"JWT decode error: {e}")
        return None


async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(security)
) -> User:
    """Dependency to get current authenticated user"""
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )

    token = credentials.credentials
    token_data = decode_token(token)

    if token_data is None or token_data.username is None:
        raise credentials_exception

    user = _users_db.get(token_data.username)
    if user is None:
        raise credentials_exception

    return User(**user)


async def get_current_active_user(
    current_user: User = Depends(get_current_user)
) -> User:
    """Dependency to get current active user"""
    if not current_user.is_active:
        raise HTTPException(status_code=400, detail="Inactive user")
    return current_user


def create_user(user_create: UserCreate) -> User:
    """Create a new user"""
    import uuid

    if user_create.username in _users_db:
        raise HTTPException(
            status_code=400,
            detail="Username already registered"
        )

    user_id = str(uuid.uuid4())
    hashed_password = get_password_hash(user_create.password)

    user_data = {
        "id": user_id,
        "username": user_create.username,
        "email": user_create.email,
        "hashed_password": hashed_password,
        "is_active": True,
        "created_at": datetime.utcnow()
    }

    _users_db[user_create.username] = user_data
    logger.info(f"Created user: {user_create.username}")

    return User(**user_data)


def authenticate_user(username: str, password: str) -> Optional[User]:
    """Authenticate a user by username and password"""
    user_data = _users_db.get(username)

    if not user_data:
        return None

    if not verify_password(password, user_data["hashed_password"]):
        return None

    return User(**user_data)


# Create a default admin user on startup
def init_default_user():
    """Initialize default admin user if none exists"""
    if "admin" not in _users_db:
        try:
            create_user(UserCreate(
                username="admin",
                password="admin123",  # Change this in production!
                email="admin@cointracker.local"
            ))
            logger.info("Created default admin user (username: admin, password: admin123)")
        except Exception as e:
            logger.warning(f"Could not create default user: {e}")
