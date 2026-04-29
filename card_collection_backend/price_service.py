import requests
import time
from datetime import datetime
import os
import logging
from typing import Optional, Dict
from database import SessionLocal
import models

# Mock price fetching functions
# In a real application, you would integrate with APIs from TCGPlayer, eBay, Cardmarket, etc.

def fetch_card_price(name: str, set_name: str, game: str, is_foil: bool = False) -> Optional[float]:
    """
    Fetch the current price for a single card.
    This is a mock implementation. Replace with actual API calls.
    """
    # Simulate API delay
    time.sleep(0.1)
    
    # Attempt live multi-source price if credentials are provided
    try:
        live_sources = fetch_card_prices_all_sources(name, set_name, game, is_foil)
        if live_sources:
            # Calculate average of live sources if available
            avg = sum(live_sources.values()) / len(live_sources)
            return round(avg, 2)
    except Exception as e:
        logging.getLogger(__name__).warning(f"Live price fetch failed for card {name}: {e}")

    # Mock price based on game and rarity (simplified)
    base_prices = {
        "magic": 10.0,
        "pokemon": 15.0,
        "yugioh": 8.0
    }
    
    # Adjust for foil
    multiplier = 2.0 if is_foil else 1.0
    
    # Add some variation based on set name (mock)
    set_modifier = hash(set_name) % 100 / 100.0  # 0 to 1
    price = base_prices.get(game, 5.0) * multiplier * (1 + set_modifier)
    
    return round(price, 2)

def fetch_card_prices_all_sources(name: str, set_name: str, game: str, is_foil: bool = False) -> Dict[str, float]:
    """Return prices from multiple sources for a card.
    This function will attempt real provider lookups if credentials are provided via environment variables.
    If providers fail or are disabled, it falls back to mock prices (for offline testing).
    """
    # Providers enabled (comma-separated, e.g. "TCGPlayer,eBay,CardMarket")
    enabled = os.getenv("PRICE_SOURCES_ENABLED", "TCGPlayer,eBay,CardMarket").split(",")
    enabled = [p.strip() for p in enabled if p.strip()]

    results: Dict[str, float] = {}

    # Internal helpers to fetch each provider
    def _tcgplayer_price():
        key = os.getenv("TCGPLAYER_API_KEY")
        if not key:
            return None
        # Heuristic endpoint (may change with API version)
        url = "https://api.tcgplayer.com/pricing/card"
        headers = {"Authorization": f"Bearer {key}", "Accept": "application/json"}
        try:
            resp = requests.get(url, headers=headers, timeout=5)
            if resp.status_code == 200:
                j = resp.json()
                price = j.get("price") or j.get("prices", {}).get("market") or j.get("low_price")
                return float(price) if price is not None else None
        except Exception:
            pass
        return None

    def _ebay_price():
        app_id = os.getenv("EBAY_APP_ID")
        if not app_id:
            return None
        url = "https://api.ebay.com/buy/browse/v1/item"
        headers = {"X-EBAY-C-MARKETPLACE-ID": "EBAY_US", "Authorization": f"Bearer {os.getenv('EBAY_OAUTH_TOKEN', '')}"}
        try:
            resp = requests.get(url, headers=headers, timeout=5, params={"q": f"{name} {set_name} {game}"})
            if resp.status_code == 200:
                j = resp.json()
                # This is a placeholder; actual structure depends on eBay API usage
                price = None
                items = j.get("itemSummaries") or []
                if items:
                    price = items[0].get("price", {}).get("value")
                if price:
                    return float(price)
        except Exception:
            pass
        return None

    def _cardmarket_price():
        key = os.getenv("CARDMARKET_API_KEY")
        if not key:
            return None
        url = "https://www.cardmarket.com/en/Carte"
        try:
            resp = requests.get(url, timeout=5, headers={"Authorization": f"Bearer {key}"})
            if resp.status_code == 200:
                j = resp.json()
                price = j.get("price") or j.get("lowest_price")
                if price is not None:
                    return float(price)
        except Exception:
            pass
        return None

    # Try live providers in the configured order
    if "TCGPlayer" in enabled:
        p = _tcgplayer_price()
        if p is not None:
            results["TCGPlayer"] = p
    if "eBay" in enabled:
        p = _ebay_price()
        if p is not None:
            results["eBay"] = p
    if "CardMarket" in enabled:
        p = _cardmarket_price()
        if p is not None:
            results["CardMarket"] = p

    # Fallback to mock prices if no live prices available
    if not results:
        time.sleep(0.05)
        base_prices = {
            "magic": 10.0,
            "pokemon": 15.0,
            "yugioh": 8.0
        }
        multiplier = 2.0 if is_foil else 1.0
        set_modifier = (hash(set_name) % 100) / 100.0
        base = base_prices.get(game, 5.0)
        tcg = round(base * multiplier * (1 + set_modifier), 2)
        results = {
            "TCGPlayer": tcg,
            "eBay": round(tcg * 0.92, 2),
            "CardMarket": round(tcg * 1.08, 2),
        }
    return results

def fetch_sealed_prices_all_sources(name: str, set_name: str, product_type: str, game: str) -> Dict[str, float]:
    time.sleep(0.05)
    enabled = os.getenv("PRICE_SOURCES_ENABLED", "TCGPlayer,eBay,CardMarket").split(",")
    enabled = [p.strip() for p in enabled if p.strip()]
    game_prices = {
        "magic": {"booster box": 120.0, "pack": 4.0, "deck": 25.0},
        "pokemon": {"booster box": 150.0, "pack": 5.0, "deck": 30.0},
        "yugioh": {"booster box": 100.0, "pack": 3.5, "deck": 20.0},
    }
    gp = game_prices.get(game, {})
    base_price = gp.get(product_type.lower(), 10.0)
    set_modifier = (hash(set_name) % 50) / 100.0
    price = round(base_price * (1 + set_modifier), 2)
    price_tcg = price
    price_ebay = round(price * 0.92, 2)
    price_cardmarket = round(price * 1.08, 2)
    # Live provider hooks same as card; if none configured, use mock
    live = {}
    if "TCGPlayer" in enabled:
        p = None
        # Attempt to call live fetch (reuse name-based function)
        try:
            prices = fetch_card_prices_all_sources(name, set_name, game, False)
            p = prices.get("TCGPlayer")
        except Exception:
            p = None
        if p is not None:
            live["TCGPlayer"] = p
    if "eBay" in enabled:
        p = price_tcg  # fallback to mock price for compatibility
        live["eBay"] = price_ebay
    if "CardMarket" in enabled:
        p = price_tcg
        live["CardMarket"] = price_cardmarket
    if live:
        return live
    return {
        "TCGPlayer": price_tcg,
        "eBay": price_ebay,
        "CardMarket": price_cardmarket,
    }

def fetch_sealed_price(name: str, set_name: str, product_type: str, game: str) -> Optional[float]:
    """
    Fetch the current price for a sealed product.
    This is a mock implementation. Replace with actual API calls.
    """
    # Simulate API delay
    time.sleep(0.1)
    
    # Mock price based on game and product type
    base_prices = {
        "magic": {"booster box": 120.0, "pack": 4.0, "deck": 25.0},
        "pokemon": {"booster box": 150.0, "pack": 5.0, "deck": 30.0},
        "yugioh": {"booster box": 100.0, "pack": 3.5, "deck": 20.0}
    }
    
    game_prices = base_prices.get(game, {})
    base_price = game_prices.get(product_type.lower(), 10.0)
    
    # Add some variation based on set name (mock)
    set_modifier = hash(set_name) % 50 / 100.0  # 0 to 0.5
    price = base_price * (1 + set_modifier)
    
    return round(price, 2)

def update_all_prices():
    """
    Update prices for all cards and sealed products in the database.
    This function would be called by the scheduler.
    """
    from crud import log_price_history  # defer to avoid circular import with crud
    # In a real implementation, you would:
    # 1. Get all cards and sealed products from the database
    # 2. For each, fetch the current price from the price service
    # 3. Update the database with the new price and timestamp
    #
    db = None
    try:
        db = SessionLocal()
        cards = db.query(models.Card).all()
        now = datetime.utcnow()
        for c in cards:
            prices = fetch_card_prices_all_sources(c.name, c.set_name, c.game, c.is_foil)
            # Log price history per provider
            for provider, price in prices.items():
                try:
                    log_price_history(db, "card", c.id, provider, price, ts=now)
                except Exception:
                    pass
            avg = sum(prices.values()) / len(prices) if prices else None
            c.current_price = avg
            c.price_sources = prices
            c.last_price_update = now
        sealed_list = db.query(models.SealedProduct).all()
        for s in sealed_list:
            prices = fetch_sealed_prices_all_sources(s.name, s.set_name, s.product_type, s.game)
            for provider, price in prices.items():
                try:
                    log_price_history(db, "sealed", s.id, provider, price, ts=now)
                except Exception:
                    pass
            avg = sum(prices.values()) / len(prices) if prices else None
            s.current_price = avg
            s.price_sources = prices
            s.last_price_update = now
        db.commit()
    finally:
        if db:
            db.close()
