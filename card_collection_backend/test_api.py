"""
Simple test to verify the API works
"""
from fastapi.testclient import TestClient
from main import app

client = TestClient(app)

def test_root():
    response = client.get("/")
    assert response.status_code == 200
    assert response.json() == {"message": "Card Collection API"}

def test_create_card():
    response = client.post(
        "/cards/",
        json={
            "name": "Black Lotus",
            "set_name": "Alpha",
            "game": "magic",
            "is_foil": True
        }
    )
    assert response.status_code == 200
    data = response.json()
    assert data["name"] == "Black Lotus"
    assert data["set_name"] == "Alpha"
    assert data["game"] == "magic"
    assert data["is_foil"] == True
    assert "id" in data
    assert "current_price" in data
    print("Card created successfully:", data)

def test_price_snapshot_endpoint():
    response = client.get('/snapshot')
    assert response.status_code == 200
    data = response.json()
    assert 'total_value' in data
    assert 'by_source' in data
    assert isinstance(data['by_source'], dict)

def test_price_history_card():
    response = client.get('/price-history/card/1')
    assert response.status_code == 200
    data = response.json()
    assert isinstance(data, list)

def test_price_history_sealed():
    response = client.get('/price-history/sealed/1')
    assert response.status_code == 200
    data = response.json()
    assert isinstance(data, list)
def test_read_cards():
    response = client.get("/cards/")
    assert response.status_code == 200
    data = response.json()
    assert isinstance(data, list)

def test_snapshot_includes_history():
    response = client.get('/snapshot')
    assert response.status_code == 200
    data = response.json()
    assert 'history' in data
    assert isinstance(data['history'], list)

def test_trigger_price_update():
    response = client.post('/prices/update')
    assert response.status_code == 200
    data = response.json()
    assert 'message' in data
if __name__ == "__main__":
    test_root()
    test_create_card()
    print("All tests passed!")
