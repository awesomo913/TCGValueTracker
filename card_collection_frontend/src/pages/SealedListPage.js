import React, { useState, useEffect } from 'react';
import api from '../services/api';
import { Link } from 'react-router-dom';
import TileCard from '../components/TileCard';

const SealedListPage = () => {
  const [sealedProducts, setSealedProducts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [searchTerm, setSearchTerm] = useState('');

  useEffect(() => {
    const fetchSealedProducts = async () => {
      try {
        setLoading(true);
        const response = await api.getSealedProducts();
        setSealedProducts(response.data);
      } catch (err) {
        setError('Failed to fetch sealed products');
        console.error(err);
      } finally {
        setLoading(false);
      }
    };

    fetchSealedProducts();
  }, [searchTerm]);

  const filteredProducts = sealedProducts.filter(product => 
    product.name.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const handleDelete = async (id) => {
    if (window.confirm('Are you sure you want to delete this sealed product?')) {
      try {
        await api.deleteSealedProduct(id);
        setSealedProducts(sealedProducts.filter(product => product.id !== id));
      } catch (err) {
        setError('Failed to delete sealed product');
        console.error(err);
      }
    }
  };

  if (loading) return <div>Loading...</div>;
  if (error) return <div>Error: {error}</div>;

  return (
    <div>
      <h2>Sealed Products</h2>
      <div className="search-bar">
        <input 
          type="text" 
          placeholder="Search sealed products..." 
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
        />
        <Link to="/sealed/add" className="add-button">Add New Sealed Product</Link>
      </div>
      
      {filteredProducts.length === 0 && (
        <p>No sealed products found. <Link to="/sealed/add">Add your first sealed product</Link>.</p>
      )}
      <div className="sealed-grid">
        {filteredProducts.map(product => (
          <TileCard key={product.id} item={product} onDelete={handleDelete} />
        ))}
      </div>
      {sealedProducts.length > 0 && (
        <div className="price-sources-section">
          <h4>Price by Source</h4>
          {sealedProducts.map((p) => (
            p.price_sources ? (
              Object.entries(p.price_sources).map(([src, price]) => (
                <div key={`${p.id}-${src}`}>
                  {src}: ${price?.toFixed ? price.toFixed(2) : price}
                </div>
              ))
            ) : null
          ))}
        </div>
      )}
    </div>
  );
};

export default SealedListPage;
