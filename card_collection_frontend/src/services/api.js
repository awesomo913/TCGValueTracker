import axios from 'axios';

const API_BASE_URL = 'http://localhost:8000';

const api = axios.create({
  baseURL: API_BASE_URL,
});

// Cards
export const getCards = () => api.get('/cards/');
export const getCard = (id) => api.get(`/cards/${id}`);
export const createCard = (card) => api.post('/cards/', card);
export const updateCard = (id, card) => api.put(`/cards/${id}`, card);
export const deleteCard = (id) => api.delete(`/cards/${id}`);

// Sealed Products
export const getSealedProducts = () => api.get('/sealed/');
export const getSealedProduct = (id) => api.get(`/sealed/${id}`);
export const createSealedProduct = (sealed) => api.post('/sealed/', sealed);
export const updateSealedProduct = (id, sealed) => api.put(`/sealed/${id}`, sealed);
export const deleteSealedProduct = (id) => api.delete(`/sealed/${id}`);

// Collection value
export const getCollectionValue = () => api.get('/collection/value');

// Price snapshots (FastAPI route is /snapshot — no trailing slash)
export const getSnapshot = () => api.get('/snapshot');

// Price update (manual trigger)
export const triggerPriceUpdate = () => api.post('/prices/update');

export default api;
