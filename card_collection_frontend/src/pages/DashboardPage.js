import React, { useState, useEffect } from 'react';
import api from '../services/api';

const DashboardPage = () => {
  const [collectionValue, setCollectionValue] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    const fetchCollectionValue = async () => {
      try {
        setLoading(true);
        const response = await api.getCollectionValue();
        setCollectionValue(response.data.total_value);
      } catch (err) {
        setError('Failed to fetch collection value');
        console.error(err);
      } finally {
        setLoading(false);
      }
    };

    fetchCollectionValue();
  }, []);

  if (loading) return <div>Loading...</div>;
  if (error) return <div>Error: {error}</div>;

  return (
    <div>
      <h2>Collection Dashboard</h2>
      <div className="dashboard-stats">
        <div className="stat-card">
          <h3>Total Collection Value</h3>
          <p>${collectionValue.toFixed(2)}</p>
        </div>
        {/* Additional stats can be added here */}
      </div>
      <div className="quick-actions">
        <h3>Quick Actions</h3>
        <button onClick={() => window.location.href = '/cards/add'}>Add New Card</button>
        <button onClick={() => window.location.href = '/sealed/add'}>Add New Sealed Product</button>
        <button onClick={() => window.location.href = '/cards'}>View All Cards</button>
        <button onClick={() => window.location.href = '/sealed'}>View All Sealed Products</button>
      </div>
    </div>
  );
};

export default DashboardPage;