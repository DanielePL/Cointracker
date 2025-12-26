#!/bin/bash
# CoinTracker Pro - Run Script

set -e

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}"
echo "╔═══════════════════════════════════════════╗"
echo "║          CoinTracker Pro                  ║"
echo "║   Vollautomatischer Crypto Trading Bot    ║"
echo "╚═══════════════════════════════════════════╝"
echo -e "${NC}"

# Check if virtual environment exists
if [ ! -d "venv" ]; then
    echo -e "${GREEN}Creating virtual environment...${NC}"
    python3 -m venv venv
fi

# Activate virtual environment
source venv/bin/activate

# Install dependencies
echo -e "${GREEN}Installing dependencies...${NC}"
pip install -r requirements.txt --quiet

# Create logs directory
mkdir -p logs

# Copy .env if not exists
if [ ! -f ".env" ]; then
    echo -e "${GREEN}Creating .env from example...${NC}"
    cp .env.example .env
    echo -e "${BLUE}Please edit .env with your API keys!${NC}"
fi

# Run the application
echo -e "${GREEN}Starting CoinTracker Pro...${NC}"
echo ""
PORT=${1:-8080}

echo "API Docs: http://localhost:$PORT/docs"
echo "ReDoc:    http://localhost:$PORT/redoc"
echo ""

uvicorn app.main:app --host 0.0.0.0 --port $PORT --reload
