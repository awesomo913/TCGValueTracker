import React from 'react';
import { Link } from 'react-router-dom';

// Anime-inspired tile for cards/sealed products
const TileCard = ({ item, onDelete }) => {
  const isSealed = !!item.product_type;
  const editPath = isSealed ? `/sealed/edit/${item.id}` : `/cards/edit/${item.id}`;
  const price = item.current_price;
  const priceSources = item.price_sources || {};

  return (
    <div className="anime-tile" aria-label={item.name}>
      <div className="tile-header">
        <div className="tile-title">{item.name}</div>
        <div className="tile-subtitle">{item.set_name || ''} • {item.game || ''}</div>
      </div>
      <div className="tile-body">
        <div className="tile-price">
          <span className="label">Current Price</span>
          <span className="value">{price != null ? `$${price.toFixed(2)}` : 'N/A'}</span>
        </div>
        <div className="price-sources">
          {Object.entries(priceSources).length > 0 && (
            Object.entries(priceSources).map(([src, p]) => (
              <div className="source-bar" key={src}>
                <span className="src-name">{src}</span>
                <div className="bar" style={{ width: `${Math.max(6, Math.min(100, (p / (price || p)) * 100))}%` }} />
                <span className="src-price">{p.toFixed ? `$${p.toFixed(2)}` : p}</span>
              </div>
            ))
          )}
        </div>
      </div>
      <div className="tile-actions">
        <Link to={editPath}>Edit</Link>
        <button className="ghost" onClick={() => onDelete(item.id)}>Delete</button>
      </div>
    </div>
  );
};

export default TileCard;
