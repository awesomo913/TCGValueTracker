import React, { useState, useEffect } from 'react';
import api from '../services/api';
import { Link } from 'react-router-dom';
import TileCard from '../components/TileCard';

const CardListPage = () => {
  const [cards, setCards] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [searchTerm, setSearchTerm] = useState('');

  useEffect(() => {
    const fetchCards = async () => {
      try {
        setLoading(true);
        const response = await api.getCards();
        setCards(response.data);
      } catch (err) {
        setError('Failed to fetch cards');
        console.error(err);
      } finally {
        setLoading(false);
      }
    };

    fetchCards();
  }, [searchTerm]);

  const filteredCards = cards.filter(card => 
    card.name.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const handleDelete = async (id) => {
    if (window.confirm('Are you sure you want to delete this card?')) {
      try {
        await api.deleteCard(id);
        setCards(cards.filter(card => card.id !== id));
      } catch (err) {
        setError('Failed to delete card');
        console.error(err);
      }
    }
  };

  if (loading) return <div>Loading...</div>;
  if (error) return <div>Error: {error}</div>;

  return (
    <div>
      <h2>My Cards</h2>
      <div className="search-bar">
        <input 
          type="text" 
          placeholder="Search cards..." 
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
        />
        <Link to="/cards/add" className="add-button">Add New Card</Link>
      </div>
      
      {filteredCards.length === 0 && (
        <p>No cards found. <Link to="/cards/add">Add your first card</Link>.</p>
      )}
      <div className="card-grid">
        {filteredCards.map(card => (
          <TileCard key={card.id} item={card} onDelete={handleDelete} />
        ))}
      </div>
      {cards.length > 0 && (
        <div className="price-sources-section">
          <h4>Price by Source</h4>
          {Object.values(cards).length > 0 && (
            <ul>
              {cards.map((c) => (
                c.price_sources ? (
                  Object.entries(c.price_sources).map(([src, price]) => (
                    <li key={`${c.id}-${src}`}>{src}: ${price?.toFixed ? price.toFixed(2) : price}</li>
                  ))
                ) : null
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
};

export default CardListPage;
