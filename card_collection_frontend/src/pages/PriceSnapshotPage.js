import React, { useState, useEffect } from 'react';
import api from '../services/api';

const PriceSnapshotPage = () => {
  const [snapshot, setSnapshot] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    const fetchSnapshot = async () => {
      try {
        setLoading(true);
        const res = await api.getSnapshot();
        setSnapshot(res.data);
      } catch (e) {
        setError('Failed to load price snapshot');
      } finally {
        setLoading(false);
      }
    };
    fetchSnapshot();
  }, []);

  if (loading) return <div>Loading snapshot...</div>;
  if (error) return <div>Error: {error}</div>;

  const bySource = snapshot?.by_source || {};
  const maxValue = Object.values(bySource).length ? Math.max(...Object.values(bySource)) : 1;

  return (
    <div className="anime-snapshot">
      <h2>Price Snapshot</h2>
      <p>As of: {snapshot?.timestamp || 'unknown'}</p>
      <div className="snapshot-bars">
        {Object.entries(bySource).map(([source, value]) => {
          const width = Math.max(2, Math.min(100, (value / maxValue) * 100));
          return (
            <div className="source-bar" key={source}>
              <span className="bar-label">{source}</span>
              <div className="bar" style={{ width: `${width}%` }} />
              <span className="bar-value">${value.toFixed(2)}</span>
            </div>
          );
        })}
      </div>
      <div>Total value: ${snapshot?.total_value?.toFixed(2) ?? '0.00'}</div>
    </div>
  );
};

export default PriceSnapshotPage;
